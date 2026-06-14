/*
 * mosey_preload.c - LD_PRELOAD shim for mosey_server
 *
 * Problem: Android 14+ servicemanager requires VINTF manifest for addService().
 * On CN ROM with KernelSU overlay, our manifest.xml is not in the pre-compiled
 * VINTF data, so addService() fails with UNKNOWN_ERROR.
 *
 * Solution:
 *   Intercept AServiceManager_addService() to:
 *   1. Cache the AIBinder* pointer for the mosey service
 *   2. Start a UNIX socket listener thread that proxies Binder calls
 *   3. Return STATUS_OK so mosey_server doesn't crash
 *
 *   mosey_bridge connects to the UNIX socket instead of using Binder directly.
 *
 * Architecture:
 *   Bada ←TCP:19539→ mosey_bridge ←UNIX socket→ mosey_preload → AIBinder → mosey_server
 *
 * Compile:
 *   aarch64-linux-android35-clang -shared -fPIC -o libmosey_preload.so \
 *     mosey_preload.c -ldl -llog -lpthread
 */

#define _GNU_SOURCE
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>
#include <dlfcn.h>
#include <sys/socket.h>
#include <signal.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <errno.h>
#include <stdint.h>
#include <android/log.h>

#define TAG "MoseyPreload"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

/* NDK Binder types */
typedef struct AIBinder AIBinder;
typedef struct AIBinder_Class AIBinder_Class;
typedef struct AParcel AParcel;
typedef int32_t binder_status_t;
typedef uint32_t transaction_code_t;

#define STATUS_OK            0
#define STATUS_UNKNOWN_ERROR (-2147483647 - 1)

/* Socket path for mosey_bridge ↔ mosey_server communication */
#define SOCKET_PATH "/data/local/tmp/mosey-p2b.sock"

/* Globals */
static AIBinder* g_cached_binder = NULL;
static char g_service_name[256] = {0};
static int g_listener_fd = -1;
static volatile int g_running = 0;
static volatile int g_listener_starting = 0;

/* ── Real function pointers ── */
typedef binder_status_t (*AIBinder_transact_t)(
    AIBinder*, transaction_code_t, AParcel**, AParcel**, int);
typedef binder_status_t (*AIBinder_prepareTransaction_t)(AIBinder*, AParcel**);
typedef AParcel* (*AParcel_create_t)(void);
typedef void (*AParcel_delete_t)(AParcel*);
typedef binder_status_t (*AParcel_writeInt32_t)(AParcel*, int32_t);
typedef binder_status_t (*AParcel_readInt32_t)(const AParcel*, int32_t*);
typedef binder_status_t (*AParcel_writeString_t)(AParcel*, const char*, int32_t);
typedef binder_status_t (*AParcel_writeInt32Array_t)(AParcel*, const int32_t*, int32_t);
typedef binder_status_t (*AParcel_writeStrongBinder_t)(AParcel*, AIBinder*);
typedef int32_t (*AParcel_getDataPosition_t)(const AParcel*);
typedef binder_status_t (*AParcel_setDataPosition_t)(const AParcel*, int32_t);
typedef void* (*AIBinder_onCreate_t)(void* args);
typedef void (*AIBinder_onDestroy_t)(void* userData);
typedef binder_status_t (*AIBinder_onTransact_t)(AIBinder*, transaction_code_t,
                                                  const AParcel*, AParcel*);
typedef const AIBinder_Class* (*AIBinder_Class_define_t)(
    const char*, AIBinder_onCreate_t, AIBinder_onDestroy_t, AIBinder_onTransact_t);
typedef AIBinder* (*AIBinder_new_t)(const AIBinder_Class*, void*);
typedef void (*AIBinder_incStrong_t)(AIBinder*);
typedef void (*AIBinder_decStrong_t)(AIBinder*);

static AIBinder_transact_t real_AIBinder_transact = NULL;
static AIBinder_prepareTransaction_t real_AIBinder_prepareTransaction = NULL;
static AParcel_create_t    real_AParcel_create = NULL;
static AParcel_delete_t    real_AParcel_delete = NULL;
static AParcel_writeInt32_t real_AParcel_writeInt32 = NULL;
static AParcel_readInt32_t  real_AParcel_readInt32 = NULL;
static AParcel_writeString_t real_AParcel_writeString = NULL;
static AParcel_writeInt32Array_t real_AParcel_writeInt32Array = NULL;
static AParcel_writeStrongBinder_t real_AParcel_writeStrongBinder = NULL;
static AParcel_getDataPosition_t real_AParcel_getDataPosition = NULL;
static AParcel_setDataPosition_t real_AParcel_setDataPosition = NULL;
static AIBinder_Class_define_t real_AIBinder_Class_define = NULL;
static AIBinder_new_t real_AIBinder_new = NULL;
static AIBinder_incStrong_t real_AIBinder_incStrong = NULL;
static AIBinder_decStrong_t real_AIBinder_decStrong = NULL;
static const AIBinder_Class* g_callback_class = NULL;
static AIBinder* g_callback_binder = NULL;

/* ── Protocol constants ── */
#define FRAME_REQUEST   0x01
#define FRAME_REPLY     0x02
#define CMD_GET_VERSION 0
#define CMD_START       1
#define CMD_STOP        2
#define CMD_UPDATE      3

#define TR_GET_VERSION  16777215  /* 0xFFFFFF */
#define TR_START        1
#define TR_STOP         2
#define TR_UPDATE       3

/* Resolve Binder API functions */
static int resolve_binder_api(void) {
    if (real_AIBinder_transact) return 0;
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        lib = dlopen("libbinder_ndk.so", RTLD_LAZY | RTLD_LOCAL);
    }
    if (!lib) {
        LOGE("dlopen(libbinder_ndk.so) failed: %s", dlerror());
        return -1;
    }
    real_AIBinder_transact  = dlsym(lib, "AIBinder_transact");
    real_AIBinder_prepareTransaction = dlsym(lib, "AIBinder_prepareTransaction");
    real_AParcel_create     = dlsym(lib, "AParcel_create");
    real_AParcel_delete     = dlsym(lib, "AParcel_delete");
    real_AParcel_writeInt32 = dlsym(lib, "AParcel_writeInt32");
    real_AParcel_readInt32  = dlsym(lib, "AParcel_readInt32");
    real_AParcel_writeString = dlsym(lib, "AParcel_writeString");
    real_AParcel_writeInt32Array = dlsym(lib, "AParcel_writeInt32Array");
    real_AParcel_writeStrongBinder = dlsym(lib, "AParcel_writeStrongBinder");
    real_AParcel_getDataPosition = dlsym(lib, "AParcel_getDataPosition");
    real_AParcel_setDataPosition = dlsym(lib, "AParcel_setDataPosition");
    real_AIBinder_Class_define = dlsym(lib, "AIBinder_Class_define");
    real_AIBinder_new = dlsym(lib, "AIBinder_new");
    real_AIBinder_incStrong = dlsym(lib, "AIBinder_incStrong");
    real_AIBinder_decStrong = dlsym(lib, "AIBinder_decStrong");
    if (!real_AIBinder_transact || !real_AIBinder_prepareTransaction ||
        !real_AParcel_delete || !real_AParcel_writeInt32 ||
        !real_AParcel_readInt32 || !real_AParcel_writeString ||
        !real_AParcel_writeInt32Array || !real_AParcel_writeStrongBinder ||
        !real_AParcel_getDataPosition || !real_AParcel_setDataPosition ||
        !real_AIBinder_Class_define || !real_AIBinder_new ||
        !real_AIBinder_incStrong || !real_AIBinder_decStrong) {
        LOGE("Failed to resolve Binder API symbols");
        return -1;
    }
    LOGI("Binder API resolved successfully");
    return 0;
}

static void* callback_on_create(void* args) {
    (void)args;
    return NULL;
}

static void callback_on_destroy(void* user_data) {
    (void)user_data;
    LOGI("Callback Binder destroyed");
}

static binder_status_t callback_on_transact(AIBinder* binder,
                                             transaction_code_t code,
                                             const AParcel* in,
                                             AParcel* out) {
    (void)binder;
    (void)in;
    (void)out;
    LOGD("Callback Binder transaction code=%u", code);
    return STATUS_OK;
}

static AIBinder* get_callback_binder(void) {
    if (g_callback_binder) return g_callback_binder;
    if (!g_callback_class) {
        /* Matches boj.i = new Binder(): no attached interface descriptor. */
        g_callback_class = real_AIBinder_Class_define(
            "", callback_on_create, callback_on_destroy, callback_on_transact);
    }
    if (!g_callback_class) return NULL;
    g_callback_binder = real_AIBinder_new(g_callback_class, NULL);
    return g_callback_binder;
}

/* ── Frame helpers ── */
static int send_frame(int fd, uint8_t type, const uint8_t* payload, uint32_t len) {
    uint8_t hdr[5] = { type,
        (uint8_t)(len & 0xFF), (uint8_t)((len>>8)&0xFF),
        (uint8_t)((len>>16)&0xFF), (uint8_t)((len>>24)&0xFF) };
    size_t offset = 0;
    while (offset < sizeof(hdr)) {
        ssize_t written = send(fd, hdr + offset, sizeof(hdr) - offset, MSG_NOSIGNAL);
        if (written <= 0) return -1;
        offset += (size_t)written;
    }
    offset = 0;
    while (offset < len) {
        ssize_t written = send(fd, payload + offset, len - offset, MSG_NOSIGNAL);
        if (written <= 0) return -1;
        offset += (size_t)written;
    }
    return 0;
}

static int recv_n(int fd, uint8_t* buf, size_t len) {
    while (len > 0) {
        ssize_t n = read(fd, buf, len);
        if (n <= 0) return -1;
        buf += n; len -= n;
    }
    return 0;
}

static int32_t read_i32_le(const uint8_t* buf) {
    return (int32_t)((uint32_t)buf[0] |
                     ((uint32_t)buf[1] << 8) |
                     ((uint32_t)buf[2] << 16) |
                     ((uint32_t)buf[3] << 24));
}

static void patch_parcel_size(AParcel* in, int32_t pos_before) {
    int32_t pos_after = real_AParcel_getDataPosition(in);
    real_AParcel_setDataPosition(in, pos_before);
    real_AParcel_writeInt32(in, pos_after - pos_before);
    real_AParcel_setDataPosition(in, pos_after);
}

/* Execute Binder transaction via cached AIBinder */
static int exec_binder_call(uint8_t cmd, const uint8_t* params, uint32_t params_len,
                            uint8_t** out, uint32_t* out_len) {
    if (!g_cached_binder || resolve_binder_api() != 0) return -1;

    transaction_code_t tr;
    switch (cmd) {
        case CMD_GET_VERSION: tr = TR_GET_VERSION; break;
        case CMD_START:       tr = TR_START;       break;
        case CMD_STOP:        tr = TR_STOP;        break;
        case CMD_UPDATE:      tr = TR_UPDATE;      break;
        default: return -1;
    }

    AParcel* in = NULL;
    AParcel* out_p = NULL;
    if (real_AIBinder_prepareTransaction(g_cached_binder, &in) != STATUS_OK || !in) {
        LOGE("AIBinder_prepareTransaction failed");
        return -1;
    }

    if (cmd == CMD_START) {
        real_AParcel_writeInt32(in, 1); /* hasValue */
        int32_t pos = real_AParcel_getDataPosition(in);
        real_AParcel_writeInt32(in, 0); /* size placeholder */

        int32_t count = 0;
        int32_t channels[16];
        if (params_len >= 1) {
            count = params[0];
            if (count > 16) count = 16;
            if (params_len < 1 + (uint32_t)count * 4) count = 0;
            for (int32_t i = 0; i < count; i++) {
                channels[i] = read_i32_le(params + 1 + i * 4);
                LOGI("start channel[%d]=%d", i, channels[i]);
            }
        }
        real_AParcel_writeInt32Array(in, channels, count);
        AIBinder* callback = get_callback_binder();
        if (!callback || real_AParcel_writeStrongBinder(in, callback) != STATUS_OK) {
            LOGE("Failed to write non-null callback Binder");
            real_AParcel_delete(in);
            return -1;
        }
        real_AParcel_writeInt32(in, 0x7fffffff);
        patch_parcel_size(in, pos);
    } else if (cmd == CMD_UPDATE) {
        real_AParcel_writeInt32(in, 1); /* hasValue */
        int32_t pos = real_AParcel_getDataPosition(in);
        real_AParcel_writeInt32(in, 0); /* size placeholder */
        const char* country = params_len > 0 ? (const char*)params : "US";
        int32_t country_len = params_len > 0 ? (int32_t)params_len : 2;
        LOGI("update country=%.*s", country_len, country);
        real_AParcel_writeString(in, country, country_len);
        patch_parcel_size(in, pos);
    } else if (cmd == CMD_STOP) {
        real_AParcel_writeInt32(in, 1); /* hasValue */
        real_AParcel_writeInt32(in, 4); /* empty Parcelable size */
    }

    binder_status_t st = real_AIBinder_transact(g_cached_binder, tr, &in, &out_p, 0);
    int32_t result = 0;
    if (st == STATUS_OK && out_p) {
        int32_t exc = 0;
        if (real_AParcel_readInt32(out_p, &exc) == STATUS_OK && exc == 0) {
            if (cmd == CMD_GET_VERSION &&
                real_AParcel_readInt32(out_p, &result) != STATUS_OK) {
                LOGE("getVersion reply is missing int32 result");
                st = STATUS_UNKNOWN_ERROR;
                result = -1;
            }
        } else {
            LOGE("remote exception=%d", exc);
            st = STATUS_UNKNOWN_ERROR;
        }
    }

    if (in) real_AParcel_delete(in);
    if (out_p) real_AParcel_delete(out_p);

    *out_len = 8;
    *out = malloc(8);
    if (!*out) return -1;
    for (int i = 0; i < 4; i++) {
        (*out)[i]   = (uint8_t)((uint32_t)st >> (i*8));
        (*out)[4+i] = (uint8_t)((uint32_t)result >> (i*8));
    }
    return 0;
}

/* UNIX socket listener thread */
static void* listener_thread(void* arg) {
    (void)arg;
    LOGI("Listener starting on %s", SOCKET_PATH);

    unlink(SOCKET_PATH);
    g_listener_fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (g_listener_fd < 0) {
        LOGE("socket: %s", strerror(errno));
        g_listener_starting = 0;
        return NULL;
    }

    struct sockaddr_un addr = { .sun_family = AF_UNIX };
    strncpy(addr.sun_path, SOCKET_PATH, sizeof(addr.sun_path)-1);

    if (bind(g_listener_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        LOGE("bind: %s", strerror(errno)); close(g_listener_fd);
        g_listener_starting = 0;
        return NULL;
    }
    if (listen(g_listener_fd, 1) < 0) {
        LOGE("listen: %s", strerror(errno)); close(g_listener_fd);
        g_listener_starting = 0;
        return NULL;
    }
    chmod(SOCKET_PATH, 0777);
    g_running = 1;
    g_listener_starting = 0;
    LOGI("Listener ready");

    while (g_running) {
        struct sockaddr_un ca;
        socklen_t cl = sizeof(ca);
        int fd = accept(g_listener_fd, (struct sockaddr*)&ca, &cl);
        if (fd < 0) { if (errno == EINTR) continue; break; }
        LOGI("Client connected");

        uint8_t hdr[5];
        while (g_running && recv_n(fd, hdr, 5) == 0) {
            uint32_t plen = (uint32_t)hdr[1]|((uint32_t)hdr[2]<<8)
                          | ((uint32_t)hdr[3]<<16)|((uint32_t)hdr[4]<<24);
            if (hdr[0] != FRAME_REQUEST || plen < 1) continue;
            uint8_t* payload = malloc(plen);
            if (!payload || recv_n(fd, payload, plen) != 0) { free(payload); break; }

            uint8_t cmd = payload[0];
            uint8_t* reply = NULL; uint32_t rlen = 0;
            if (exec_binder_call(cmd, payload + 1, plen - 1, &reply, &rlen) != 0) {
                uint8_t err[] = {0xFF,0xFF,0xFF,0xFF};
                if (send_frame(fd, FRAME_REPLY, err, 4) != 0) {
                    LOGW("Client disconnected before error reply: %s", strerror(errno));
                    free(payload);
                    break;
                }
            } else {
                if (send_frame(fd, FRAME_REPLY, reply, rlen) != 0) {
                    LOGW("Client disconnected before reply: %s", strerror(errno));
                    free(reply);
                    free(payload);
                    break;
                }
                free(reply);
            }
            free(payload);
        }
        close(fd);
        LOGI("Client disconnected");
    }
    close(g_listener_fd);
    g_listener_fd = -1;
    LOGI("Listener stopped");
    return NULL;
}

static void ensure_socket_proxy(void) {
    if (__sync_lock_test_and_set(&g_listener_starting, 1) != 0) return;
    if (g_running) {
        g_listener_starting = 0;
        return;
    }
    pthread_t thread;
    if (pthread_create(&thread, NULL, listener_thread, NULL) == 0) {
        pthread_detach(thread);
        LOGI("Socket proxy launch requested");
    } else {
        g_listener_starting = 0;
        LOGE("Failed to start socket proxy thread");
    }
}

/* ── Intercept AServiceManager_addService ── */
__attribute__((visibility("default")))
binder_status_t AServiceManager_addService(AIBinder* binder, const char* name) {
    signal(SIGPIPE, SIG_IGN);
    static binder_status_t (*real_add)(AIBinder*, const char*) = NULL;
    if (!real_add) {
        void* lib = dlopen("libbinder_ndk.so", RTLD_NOW|RTLD_LOCAL);
        if (!lib) lib = dlopen("libbinder_ndk.so", RTLD_LAZY|RTLD_LOCAL);
        real_add = lib ? dlsym(lib, "AServiceManager_addService") : NULL;
        if (!real_add) real_add = dlsym(RTLD_NEXT, "AServiceManager_addService");
    }
    LOGI("AServiceManager_addService('%s')", name);

    if (resolve_binder_api() != 0) return STATUS_UNKNOWN_ERROR;

    /* Keep the service Binder alive for the lifetime of the proxy. */
    if (!g_cached_binder) {
        g_cached_binder = binder;
        real_AIBinder_incStrong(g_cached_binder);
        strncpy(g_service_name, name, sizeof(g_service_name)-1);
    }

    /* Keep the UNIX proxy available even when VINTF registration succeeds. */
    ensure_socket_proxy();
    if (real_add && real_add(binder, name) == STATUS_OK) {
        LOGI("Real addService succeeded; UNIX proxy remains available");
        return STATUS_OK;
    }
    LOGW("Real addService failed (VINTF?); serving through UNIX proxy");
    return STATUS_OK;
}

__attribute__((visibility("default")))
binder_status_t AServiceManager_addServiceWithFlags(AIBinder* binder,
                                                     const char* name,
                                                     int32_t flags) {
    (void)flags;
    return AServiceManager_addService(binder, name);
}
