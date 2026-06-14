/* TCP/UNIX bridge for the Mosey AIDL service. */
#define _GNU_SOURCE
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <netinet/in.h>
#include <pthread.h>
#include <signal.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#define TAG "MoseyBridge"
#define BRIDGE_LOG_PATH "/data/adb/mosey-enabler/bridge.log"
#define BRIDGE_LOG_OLD_PATH "/data/adb/mosey-enabler/bridge.log.1"
#define BRIDGE_LOG_MAX_BYTES (256 * 1024)
#define SERVICE_NAME "com.google.android.moseyservice.IMoseyService/default"
#define UNIX_SOCKET_PATH "/data/local/tmp/mosey-p2b.sock"
#define BRIDGE_PORT 19539
#define EVENT_PORT 19540
#define MAX_FRAME_SIZE (64 * 1024)

#define FRAME_REQUEST 0x01
#define FRAME_REPLY 0x02
#define FRAME_EVENT 0x03
#define CMD_GET_VERSION 0
#define CMD_START 1
#define CMD_STOP 2
#define CMD_UPDATE 3
#define CMD_SUBSCRIBE 4
#define CMD_WAKE_BADA 5
#define CMD_ENABLE 6
#define CMD_DISABLE 7
#define CMD_STATUS 8
#define TR_GET_VERSION 16777215
#define TR_START 1
#define TR_STOP 2
#define TR_UPDATE 3

typedef enum { BACKEND_NONE, BACKEND_BINDER, BACKEND_UNIX } backend_t;

typedef struct client_ctx {
    int fd;
    bool subscribed;
    pthread_mutex_t write_lock;
} client_ctx_t;

static pthread_mutex_t g_log_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_backend_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_subscriber_lock = PTHREAD_MUTEX_INITIALIZER;
static volatile sig_atomic_t g_running = 1;
static backend_t g_backend = BACKEND_NONE;
static AIBinder* g_service = NULL;
static AIBinder* g_callback = NULL;
static const AIBinder_Class* g_service_class = NULL;
static const AIBinder_Class* g_callback_class = NULL;
static int g_unix_fd = -1;
static client_ctx_t* g_subscriber = NULL;
static bool g_binder_pool_started = false;

static void bridge_log(int priority, const char* level, const char* format, ...) {
    char message[1024];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    __android_log_print(priority, TAG, "%s", message);
    pthread_mutex_lock(&g_log_lock);
    struct stat st;
    if (stat(BRIDGE_LOG_PATH, &st) == 0 && st.st_size >= BRIDGE_LOG_MAX_BYTES) {
        unlink(BRIDGE_LOG_OLD_PATH);
        rename(BRIDGE_LOG_PATH, BRIDGE_LOG_OLD_PATH);
    }
    FILE* file = fopen(BRIDGE_LOG_PATH, "a");
    if (file) {
        time_t now = time(NULL);
        struct tm tm_now;
        char timestamp[32];
        localtime_r(&now, &tm_now);
        strftime(timestamp, sizeof(timestamp), "%Y-%m-%d %H:%M:%S", &tm_now);
        fprintf(file, "%s %s %s\n", timestamp, level, message);
        fclose(file);
    }
    pthread_mutex_unlock(&g_log_lock);
}

#define LOGI(...) bridge_log(ANDROID_LOG_INFO, "I", __VA_ARGS__)
#define LOGW(...) bridge_log(ANDROID_LOG_WARN, "W", __VA_ARGS__)
#define LOGE(...) bridge_log(ANDROID_LOG_ERROR, "E", __VA_ARGS__)
#define LOGD(...) bridge_log(ANDROID_LOG_DEBUG, "D", __VA_ARGS__)

static int32_t read_i32(const uint8_t* p) {
    return (int32_t)((uint32_t)p[0] | ((uint32_t)p[1] << 8) |
                     ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24));
}

static void write_i32(uint8_t* p, int32_t value) {
    p[0] = (uint8_t)value;
    p[1] = (uint8_t)(value >> 8);
    p[2] = (uint8_t)(value >> 16);
    p[3] = (uint8_t)(value >> 24);
}

static bool read_exact(int fd, void* buffer, size_t len) {
    uint8_t* p = buffer;
    while (len) {
        ssize_t n = read(fd, p, len);
        if (n == 0) return false;
        if (n < 0) {
            if (errno == EINTR) continue;
            return false;
        }
        p += n;
        len -= (size_t)n;
    }
    return true;
}

static bool write_exact(int fd, const void* buffer, size_t len) {
    const uint8_t* p = buffer;
    while (len) {
        ssize_t n = send(fd, p, len, MSG_NOSIGNAL);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        p += n;
        len -= (size_t)n;
    }
    return true;
}

static bool send_frame_locked(client_ctx_t* client, uint8_t type,
                              const uint8_t* payload, size_t len) {
    uint8_t header[5] = {type, 0, 0, 0, 0};
    write_i32(header + 1, (int32_t)len);
    pthread_mutex_lock(&client->write_lock);
    bool ok = write_exact(client->fd, header, sizeof(header)) &&
              (!len || write_exact(client->fd, payload, len));
    pthread_mutex_unlock(&client->write_lock);
    return ok;
}

static bool send_raw_frame(int fd, uint8_t type, const uint8_t* payload, size_t len) {
    uint8_t header[5] = {type, 0, 0, 0, 0};
    write_i32(header + 1, (int32_t)len);
    return write_exact(fd, header, sizeof(header)) &&
           (!len || write_exact(fd, payload, len));
}

static void configure_socket_timeout(int fd) {
    struct timeval tv = { .tv_sec = 2, .tv_usec = 0 };
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
}

static int connect_unix(void) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, UNIX_SOCKET_PATH, sizeof(addr.sun_path) - 1);
    if (connect(fd, (struct sockaddr*)&addr, sizeof(addr)) != 0) {
        close(fd);
        return -1;
    }
    configure_socket_timeout(fd);
    return fd;
}

/* The preload reply is [binder_status:i32][result:i32]. Void calls use result=0. */
static int32_t unix_call(uint8_t cmd, const uint8_t* params, size_t len) {
    size_t request_len = len + 1;
    uint8_t* request = malloc(request_len);
    if (!request) return -1;
    request[0] = cmd;
    if (len) memcpy(request + 1, params, len);

    if (g_unix_fd < 0) g_unix_fd = connect_unix();
    bool sent = g_unix_fd >= 0 &&
                send_raw_frame(g_unix_fd, FRAME_REQUEST, request, request_len);
    free(request);
    if (!sent) goto failed;

    uint8_t header[5];
    if (!read_exact(g_unix_fd, header, sizeof(header)) ||
        header[0] != FRAME_REPLY) goto failed;
    uint32_t reply_len = (uint32_t)read_i32(header + 1);
    if (reply_len < 8 || reply_len > MAX_FRAME_SIZE) goto failed;
    uint8_t* reply = malloc(reply_len);
    if (!reply || !read_exact(g_unix_fd, reply, reply_len)) {
        free(reply);
        goto failed;
    }
    int32_t binder_status = read_i32(reply);
    int32_t result = read_i32(reply + 4);
    free(reply);
    LOGI("backend=unix cmd=%u binder_status=%d result=%d", cmd,
         binder_status, result);
    return binder_status == STATUS_OK ? result : -1;

failed:
    LOGE("backend=unix cmd=%u transport failed: %s", cmd, strerror(errno));
    if (g_unix_fd >= 0) close(g_unix_fd);
    g_unix_fd = -1;
    return -1;
}

static void* callback_create(void* args) { (void)args; return NULL; }
static void callback_destroy(void* user) { (void)user; }
static binder_status_t callback_transact(AIBinder* binder, transaction_code_t code,
                                         const AParcel* in, AParcel* out) {
    (void)binder; (void)in; (void)out;
    LOGW("Unexpected transaction on Mosey lifecycle token code=%u", code);
    return STATUS_UNKNOWN_TRANSACTION;
}

static void start_binder_thread_pool(void) {
    if (g_binder_pool_started) return;
    void (*set_max_threads)(uint32_t) =
        (void (*)(uint32_t))dlsym(RTLD_DEFAULT,
                                  "ABinderProcess_setThreadPoolMaxThreadCount");
    void (*start_pool)(void) =
        (void (*)(void))dlsym(RTLD_DEFAULT, "ABinderProcess_startThreadPool");
    if (!set_max_threads || !start_pool) {
        LOGW("Binder thread-pool APIs unavailable; direct backend will still be probed");
        return;
    }
    set_max_threads(4);
    start_pool();
    g_binder_pool_started = true;
    LOGI("Binder thread pool started max_threads=4");
}

static void* service_create(void* args) { (void)args; return NULL; }
static void service_destroy(void* user) { (void)user; }
static binder_status_t service_transact(AIBinder* binder, transaction_code_t code,
                                        const AParcel* in, AParcel* out) {
    (void)binder; (void)code; (void)in; (void)out;
    return STATUS_UNKNOWN_TRANSACTION;
}

static bool associate_service_class(AIBinder* service) {
    if (!g_service_class) {
        g_service_class = AIBinder_Class_define(
            "com.google.android.moseyservice.IMoseyService",
            service_create, service_destroy, service_transact);
    }
    if (!g_service_class) {
        LOGE("Unable to define IMoseyService Binder class");
        return false;
    }
    if (!AIBinder_associateClass(service, g_service_class)) {
        LOGE("AIBinder_associateClass(IMoseyService) failed");
        return false;
    }
    LOGI("Associated remote Binder with IMoseyService descriptor");
    return true;
}

static AIBinder* callback_binder(void) {
    if (g_callback) return g_callback;
    if (!g_callback_class) {
        g_callback_class = AIBinder_Class_define(
            "", callback_create, callback_destroy, callback_transact);
    }
    if (g_callback_class) g_callback = AIBinder_new(g_callback_class, NULL);
    return g_callback;
}

static bool direct_transact(AParcel** in, transaction_code_t code,
                            AParcel** reply_out) {
    AParcel* reply = NULL;
    binder_status_t status = AIBinder_transact(g_service, code, in, &reply, 0);
    if (status != STATUS_OK || !reply) {
        LOGE("backend=binder transaction=%u status=%d", code, status);
        if (reply) AParcel_delete(reply);
        return false;
    }
    int32_t exception = -1;
    if (AParcel_readInt32(reply, &exception) != STATUS_OK || exception != 0) {
        if (exception == -4) {
            int32_t service_error = 0;
            if (AParcel_readInt32(reply, &service_error) == STATUS_OK) {
                LOGE("backend=binder transaction=%u service_error=%d", code,
                     service_error);
            } else {
                LOGE("backend=binder transaction=%u service exception without code", code);
            }
        } else {
            LOGE("backend=binder transaction=%u exception=%d", code, exception);
        }
        AParcel_delete(reply);
        return false;
    }
    *reply_out = reply;
    return true;
}

static int32_t direct_call(uint8_t cmd, const uint8_t* params, size_t len) {
    if (cmd == CMD_START &&
        (len < 1 || params[0] > 32 || len < 1 + (size_t)params[0] * 4)) {
        return -1;
    }
    if (cmd == CMD_UPDATE && !len) return -1;

    AParcel* in = NULL;
    binder_status_t prepare_status = AIBinder_prepareTransaction(g_service, &in);
    if (prepare_status != STATUS_OK || !in) {
        LOGE("backend=binder prepareTransaction cmd=%u status=%d", cmd, prepare_status);
        return -1;
    }
    transaction_code_t code = 0;
    if (cmd == CMD_GET_VERSION) {
        code = TR_GET_VERSION;
    } else if (cmd == CMD_START) {
        code = TR_START;
        int32_t count = params[0];
        int32_t channels[32];
        for (int32_t i = 0; i < count; ++i) channels[i] = read_i32(params + 1 + i * 4);
        AParcel_writeInt32(in, 1);
        int32_t start = AParcel_getDataPosition(in);
        AParcel_writeInt32(in, 0);
        AParcel_writeInt32Array(in, channels, count);
        AIBinder* callback = callback_binder();
        if (!callback || AParcel_writeStrongBinder(in, callback) != STATUS_OK) return -1;
        AParcel_writeInt32(in, 0x7fffffff);
        int32_t end = AParcel_getDataPosition(in);
        AParcel_setDataPosition(in, start);
        AParcel_writeInt32(in, end - start);
        AParcel_setDataPosition(in, end);
    } else if (cmd == CMD_STOP) {
        code = TR_STOP;
        AParcel_writeInt32(in, 1);
        AParcel_writeInt32(in, 4);
    } else if (cmd == CMD_UPDATE) {
        code = TR_UPDATE;
        AParcel_writeInt32(in, 1);
        int32_t start = AParcel_getDataPosition(in);
        AParcel_writeInt32(in, 0);
        AParcel_writeString(in, (const char*)params, (int32_t)len);
        int32_t end = AParcel_getDataPosition(in);
        AParcel_setDataPosition(in, start);
        AParcel_writeInt32(in, end - start);
        AParcel_setDataPosition(in, end);
    } else {
        return -1;
    }

    AParcel* reply = NULL;
    if (!direct_transact(&in, code, &reply)) return -1;
    int32_t result = 0;
    if (cmd == CMD_GET_VERSION && AParcel_readInt32(reply, &result) != STATUS_OK) {
        result = -1;
    }
    AParcel_delete(reply);
    LOGI("backend=binder cmd=%u result=%d", cmd, result);
    return result;
}

static int32_t backend_call(uint8_t cmd, const uint8_t* params, size_t len) {
    struct timespec before, after;
    clock_gettime(CLOCK_MONOTONIC, &before);
    pthread_mutex_lock(&g_backend_lock);
    int32_t result = g_backend == BACKEND_BINDER
        ? direct_call(cmd, params, len) : unix_call(cmd, params, len);
    pthread_mutex_unlock(&g_backend_lock);
    clock_gettime(CLOCK_MONOTONIC, &after);
    long elapsed = (after.tv_sec - before.tv_sec) * 1000L +
                   (after.tv_nsec - before.tv_nsec) / 1000000L;
    LOGI("command=%u backend=%s result=%d elapsed=%ldms", cmd,
         g_backend == BACKEND_BINDER ? "binder" : "unix", result, elapsed);
    return result;
}

static bool init_backend(void) {
    start_binder_thread_pool();
    const char* forced_backend = getenv("MOSEY_BACKEND");
    bool force_unix = forced_backend && strcmp(forced_backend, "unix") == 0;
    bool force_binder = forced_backend && strcmp(forced_backend, "binder") == 0;
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    /* 使用 checkService 而非 getService：后者在服务不存在时轮询 ~5 秒 */
    AIBinder* (*check_service)(const char*) = lib
        ? (AIBinder* (*)(const char*))dlsym(lib, "AServiceManager_checkService") : NULL;
    if (check_service && !force_unix) {
        g_service = check_service(SERVICE_NAME);
        if (g_service || force_binder) {
            /* checkService 返回 NULL 时仍可能重试；force_binder 模式强制等待 */
            if (!g_service && force_binder) {
                AIBinder* (*get_service)(const char*) = lib
                    ? (AIBinder* (*)(const char*))dlsym(lib, "AServiceManager_getService") : NULL;
                if (get_service) g_service = get_service(SERVICE_NAME);
            }
            if (g_service && associate_service_class(g_service)) {
                g_backend = BACKEND_BINDER;
                int32_t version = backend_call(CMD_GET_VERSION, NULL, 0);
                if (version >= 0) {
                    LOGI("backend=binder getVersion=%d", version);
                    return true;
                }
            }
            if (g_service) { AIBinder_decStrong(g_service); g_service = NULL; }
        }
    }
    g_unix_fd = connect_unix();
    if (g_unix_fd >= 0) {
        g_backend = BACKEND_UNIX;
        int32_t version = backend_call(CMD_GET_VERSION, NULL, 0);
        if (version >= 0) {
            LOGI("backend=unix getVersion=%d", version);
            return true;
        }
    }
    g_backend = BACKEND_NONE;
    LOGE("No usable Mosey backend");
    return false;
}

static void set_subscriber(client_ctx_t* client) {
    pthread_mutex_lock(&g_subscriber_lock);
    if (g_subscriber && g_subscriber != client) {
        g_subscriber->subscribed = false;
        LOGI("Subscriber replaced old_fd=%d new_fd=%d", g_subscriber->fd, client->fd);
    }
    client->subscribed = true;
    g_subscriber = client;
    pthread_mutex_unlock(&g_subscriber_lock);
}

static void emit_event(uint8_t event_type, const uint8_t* json, size_t json_len) {
    uint8_t* payload = malloc(json_len + 1);
    if (!payload) return;
    payload[0] = event_type;
    memcpy(payload + 1, json, json_len);
    pthread_mutex_lock(&g_subscriber_lock);
    if (g_subscriber && g_subscriber->subscribed) {
        if (!send_frame_locked(g_subscriber, FRAME_EVENT, payload, json_len + 1)) {
            LOGW("Dropping disconnected/slow subscriber fd=%d", g_subscriber->fd);
            g_subscriber->subscribed = false;
            g_subscriber = NULL;
        }
    } else {
        LOGD("No subscriber for event type=%u", event_type);
    }
    pthread_mutex_unlock(&g_subscriber_lock);
    free(payload);
}

static uint8_t json_event_type(const char* json) {
    const char* type = strstr(json, "\"type\"");
    if (!type) return 0;
    const char* colon = strchr(type, ':');
    if (!colon) return 0;
    if (strstr(colon, "\"airdrop_found\"") == colon + strspn(colon, ": \t")) return 0x01;
    if (strstr(colon, "\"airdrop_lost\"") == colon + strspn(colon, ": \t")) return 0x02;
    if (strstr(colon, "\"apple_ble_seen\"") == colon + strspn(colon, ": \t")) return 0x03;
    return 0;
}

static void* event_listener(void* unused) {
    (void)unused;
    int listener = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in addr = {.sin_family = AF_INET, .sin_port = htons(EVENT_PORT)};
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (listener < 0 || bind(listener, (struct sockaddr*)&addr, sizeof(addr)) != 0 ||
        listen(listener, 8) != 0) {
        LOGE("Event listener startup failed: %s", strerror(errno));
        if (listener >= 0) close(listener);
        return NULL;
    }
    LOGI("Shim event listener ready port=%d", EVENT_PORT);
    while (g_running) {
        int fd = accept(listener, NULL, NULL);
        if (fd < 0) { if (errno == EINTR) continue; break; }
        uint8_t len_bytes[4];
        if (read_exact(fd, len_bytes, 4)) {
            uint32_t len = (uint32_t)read_i32(len_bytes);
            if (len > 0 && len <= MAX_FRAME_SIZE) {
                char* json = calloc(1, len + 1);
                if (json && read_exact(fd, json, len)) {
                    uint8_t event_type = json_event_type(json);
                    if (event_type) {
                        LOGI("Shim event type=%u bytes=%u", event_type, len);
                        emit_event(event_type, (uint8_t*)json, len);
                    } else {
                        LOGW("Unknown shim event: %.160s", json);
                    }
                }
                free(json);
            } else {
                LOGW("Rejected shim event length=%u", len);
            }
        }
        close(fd);
    }
    close(listener);
    return NULL;
}

static void* client_thread(void* arg) {
    client_ctx_t* client = arg;
    struct timeval timeout = {.tv_sec = 3};
    setsockopt(client->fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));
    LOGI("Client connected fd=%d", client->fd);
    while (g_running) {
        uint8_t header[5];
        if (!read_exact(client->fd, header, 5)) break;
        uint32_t len = (uint32_t)read_i32(header + 1);
        if (header[0] != FRAME_REQUEST || len < 1 || len > MAX_FRAME_SIZE) {
            LOGW("Invalid client frame fd=%d type=%u len=%u", client->fd, header[0], len);
            break;
        }
        uint8_t* payload = malloc(len);
        if (!payload || !read_exact(client->fd, payload, len)) { free(payload); break; }
        uint8_t cmd = payload[0];
        int32_t value = 0;
        int32_t status = 0;
        if (cmd == CMD_SUBSCRIBE) {
            set_subscriber(client);
        } else if (cmd == CMD_WAKE_BADA) {
            /* 发送广播到 AirDropWakeReceiver（exported=true，可跨包访问）*/
            int rc = system(
                "/system/bin/am broadcast -a dev.bluehouse.bada.airdrop.WAKE "
                "-n dev.bluehouse.bada.debug/dev.bluehouse.bada.service.airdrop.AirDropWakeReceiver "
                "--include-stopped-packages >/dev/null 2>&1 || "
                "/system/bin/am broadcast -a dev.bluehouse.bada.airdrop.WAKE "
                "-n dev.bluehouse.bada/dev.bluehouse.bada.service.airdrop.AirDropWakeReceiver "
                "--include-stopped-packages >/dev/null 2>&1");
            value = (rc == 0) ? 0 : -1;
            status = value;
            LOGI("command=wake_bada result=%d", rc);
        } else if (cmd == CMD_ENABLE) {
            int rc = system(
                "/system/bin/sh /data/adb/modules/mosey-enabler/mosey-control.sh "
                "webui enable >/dev/null 2>&1");
            value = (rc == 0) ? 0 : -1;
            status = value;
            LOGI("command=enable result=%d", rc);
        } else if (cmd == CMD_DISABLE) {
            /* 先回复"命令已接受"，再异步执行 disable
             * （避免同步 system() 杀死 bridge 自己导致无响应） */
            uint8_t ok_reply[8];
            write_i32(ok_reply, 0);
            write_i32(ok_reply + 4, 0);
            send_frame_locked(client, FRAME_REPLY, ok_reply, sizeof(ok_reply));
            LOGI("command=disable accepted");
            pid_t dpid = fork();
            if (dpid == 0) {
                setsid();
                usleep(300 * 1000);
                execl("/system/bin/sh", "sh",
                    "/data/adb/modules/mosey-enabler/mosey-control.sh",
                    "webui", "disable",
                    (char*)NULL);
                _exit(127);
            }
            free(payload);
            continue;  /* 跳过默认 reply */
        } else if (cmd == CMD_STATUS) {
            FILE* fp = popen(
                "/system/bin/sh /data/adb/modules/mosey-enabler/mosey-control.sh "
                "webui status 2>/dev/null", "re");
            if (fp) {
                char buf[4096];
                size_t n = fread(buf, 1, sizeof(buf) - 1, fp);
                int rc = pclose(fp);
                if (rc == 0 && n > 0) {
                    /* reply: [status:i32=0][json_len:u32][json_bytes...] */
                    uint32_t json_len = (uint32_t)n;
                    uint8_t* reply = malloc(8 + json_len);
                    if (reply) {
                        write_i32(reply, 0);           /* status = 0 */
                        write_i32(reply + 4, (int32_t)json_len);
                        memcpy(reply + 8, buf, json_len);
                        send_frame_locked(client, FRAME_REPLY, reply, 8 + json_len);
                        free(reply);
                        free(payload);
                        continue;  /* skip default reply */
                    }
                }
                status = -1;
                value = -1;
            } else {
                status = -1;
                value = -1;
                LOGE("popen failed for CMD_STATUS");
            }
        } else if (cmd <= CMD_UPDATE) {
            value = backend_call(cmd, payload + 1, len - 1);
            status = value >= 0 ? 0 : -1;
        } else {
            status = -1;
            value = -1;
        }
        free(payload);
        uint8_t reply[8];
        write_i32(reply, status);
        write_i32(reply + 4, value);
        if (!send_frame_locked(client, FRAME_REPLY, reply, sizeof(reply))) break;
    }
    pthread_mutex_lock(&g_subscriber_lock);
    if (g_subscriber == client) g_subscriber = NULL;
    client->subscribed = false;
    pthread_mutex_unlock(&g_subscriber_lock);
    LOGI("Client disconnected fd=%d", client->fd);
    close(client->fd);
    pthread_mutex_destroy(&client->write_lock);
    free(client);
    return NULL;
}

static int probe(void) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    struct sockaddr_in addr = {.sin_family = AF_INET, .sin_port = htons(BRIDGE_PORT)};
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (fd < 0 || connect(fd, (struct sockaddr*)&addr, sizeof(addr)) != 0) return 1;
    uint8_t command = CMD_GET_VERSION;
    if (!send_raw_frame(fd, FRAME_REQUEST, &command, 1)) { close(fd); return 1; }
    uint8_t header[5], reply[8];
    bool ok = read_exact(fd, header, 5) && header[0] == FRAME_REPLY &&
              read_i32(header + 1) == 8 && read_exact(fd, reply, 8) &&
              read_i32(reply) == 0 && read_i32(reply + 4) >= 0;
    close(fd);
    return ok ? 0 : 1;
}

static void handle_signal(int sig) { (void)sig; g_running = 0; }

int main(int argc, char** argv) {
    signal(SIGPIPE, SIG_IGN);
    if (argc == 2 && strcmp(argv[1], "--probe") == 0) return probe();
    if ((argc == 2 || argc == 3) && strcmp(argv[1], "--backend-probe") == 0) {
        /* 支持 --backend-probe unix|binder|auto */
        if (argc == 3) {
            setenv("MOSEY_BACKEND", argv[2], 1);
        }
        return init_backend() ? 0 : 1;
    }
    signal(SIGTERM, handle_signal);
    signal(SIGINT, handle_signal);
    if (!init_backend()) return 2;

    pthread_t event_thread;
    if (pthread_create(&event_thread, NULL, event_listener, NULL) != 0) return 3;
    pthread_detach(event_thread);

    int listener = socket(AF_INET, SOCK_STREAM, 0);
    int one = 1;
    setsockopt(listener, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));
    struct sockaddr_in addr = {.sin_family = AF_INET, .sin_port = htons(BRIDGE_PORT)};
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (listener < 0 || bind(listener, (struct sockaddr*)&addr, sizeof(addr)) != 0 ||
        listen(listener, 16) != 0) {
        LOGE("TCP listener startup failed: %s", strerror(errno));
        return 4;
    }
    LOGI("Bridge listening port=%d backend=%s", BRIDGE_PORT,
         g_backend == BACKEND_BINDER ? "binder" : "unix");
    while (g_running) {
        int fd = accept(listener, NULL, NULL);
        if (fd < 0) { if (errno == EINTR) continue; break; }
        client_ctx_t* client = calloc(1, sizeof(*client));
        if (!client) { close(fd); continue; }
        client->fd = fd;
        pthread_mutex_init(&client->write_lock, NULL);
        pthread_t thread;
        if (pthread_create(&thread, NULL, client_thread, client) != 0) {
            close(fd);
            pthread_mutex_destroy(&client->write_lock);
            free(client);
            continue;
        }
        pthread_detach(thread);
    }
    close(listener);
    return 0;
}
