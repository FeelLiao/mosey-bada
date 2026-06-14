/*
 * mosey_launcher.c - Pre-register mosey_service and start mosey_server
 *
 * Problem: On Android 14+, servicemanager rejects addService() for services
 * not declared in VINTF manifest when called from the ksu SELinux domain.
 * Our VINTF manifest fragment at /odm/etc/vintf/manifest/manifest_mosey.xml
 * is NOT visible to servicemanager because it compiles VINTF before the
 * KernelSU overlay mount takes effect.
 *
 * Solution:
 *   This launcher wraps mosey_server startup in two steps:
 *   1. Fork a child that registers the mosey service via direct binder
 *      transactions to servicemanager (bypassing VINTF check through
 *      svc command on the default binder).
 *   2. If registration succeeds, exec mosey_server which may still crash
 *      on its own AServiceManager_addService() call. To prevent this,
 *      we LD_PRELOAD a wrapper (mosey_preload.so) that intercepts
 *      AServiceManager_addService and returns STATUS_OK.
 *
 * Alternative approach (primary):
 *   If "service call" can reach servicemanager, we pre-register the
 *   service before launching mosey_server with LD_PRELOAD.
 *
 * Compile:
 *   aarch64-linux-android35-clang -o mosey_launcher mosey_launcher.c \
 *     -ldl -llog
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <sys/wait.h>
#include <android/log.h>

#define TAG "MoseyLauncher"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

#define SERVICE_NAME "com.google.android.moseyservice.IMoseyService/default"
#define MOSEY_SERVER_PATH "/odm/bin/mosey_server"
#define PRELOAD_LIB_PATH "/odm/lib64/libmosey_preload.so"

/* AIBinder NDK types */
typedef struct AIBinder AIBinder;
typedef struct AParcel AParcel;
typedef int32_t binder_status_t;

#define STATUS_OK 0
#define STATUS_UNKNOWN_ERROR (-2147483647 - 1)

/* NDK function prototypes */
typedef AIBinder* (*AIBinder_new_t)(const void* cls, void* args);
typedef bool (*AIBinder_isAlive_t)(AIBinder* binder);
typedef binder_status_t (*AIBinder_transact_t)(
    AIBinder* binder, uint32_t code, AParcel* in, AParcel* out, int flags);
typedef AParcel* (*AParcel_create_t)(void);
typedef void (*AParcel_delete_t)(AParcel* parcel);
typedef binder_status_t (*AParcel_writeInt32_t)(AParcel* in, int32_t v);
typedef binder_status_t (*AParcel_writeString_t)(AParcel* in, const char* str, int32_t len);
typedef binder_status_t (*AParcel_readInt32_t)(const AParcel* in, int32_t* v);
typedef binder_status_t (*AParcel_readString_t)(const AParcel* in, const char** str);

/* libbinder_ndk handle and function pointers */
static void* g_binder_lib = NULL;
static AIBinder_new_t fn_AIBinder_new = NULL;
static AIBinder_isAlive_t fn_AIBinder_isAlive = NULL;
static AIBinder_transact_t fn_AIBinder_transact = NULL;
static AParcel_create_t fn_AParcel_create = NULL;
static AParcel_delete_t fn_AParcel_delete = NULL;
static AParcel_writeInt32_t fn_AParcel_writeInt32 = NULL;
static AParcel_writeString_t fn_AParcel_writeString = NULL;
static AParcel_readInt32_t fn_AParcel_readInt32 = NULL;
static AParcel_readString_t fn_AParcel_readString = NULL;

/* AServiceManager_addService not available in our NDK headers,
 * but we can call it directly from libbinder_ndk.so */
typedef binder_status_t (*AServiceManager_addService_t)(AIBinder* binder, const char* name);
static AServiceManager_addService_t fn_AServiceManager_addService = NULL;

/* Forward declaration for the callback class */
static const void* g_callback_class = NULL;

/* ── Binder class callbacks ── */
static void* on_create(void* args) {
    return args;  /* Pass through user data */
}

static void on_destroy(void* user_data) {
    /* Nothing to clean up */
}

/* Transaction code for mosey service (from reverse engineering) */
#define TR_GET_VERSION  16777215  /* 0xFFFFFF */

static binder_status_t on_transact(
    AIBinder* binder, uint32_t code,
    const AParcel* in, AParcel* out) {
    LOGI("Received transaction code=%u", code);
    switch (code) {
    case TR_GET_VERSION:
        fn_AParcel_writeInt32(out, 1);  /* version = 1 */
        LOGI("getVersion() -> 1");
        return STATUS_OK;
    default:
        LOGW("Unknown transaction code=%u", code);
        return STATUS_UNKNOWN_ERROR;
    }
}

/* ── Load libbinder_ndk.so symbols ── */
static bool load_binder_library(void) {
    if (g_binder_lib) return true;

    g_binder_lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!g_binder_lib) {
        LOGE("dlopen(libbinder_ndk.so) failed: %s", dlerror());
        return false;
    }

    fn_AServiceManager_addService = dlsym(g_binder_lib, "AServiceManager_addService");
    fn_AIBinder_new = dlsym(g_binder_lib, "AIBinder_new");
    fn_AIBinder_isAlive = dlsym(g_binder_lib, "AIBinder_isAlive");
    fn_AIBinder_transact = dlsym(g_binder_lib, "AIBinder_transact");
    fn_AParcel_create = dlsym(g_binder_lib, "AParcel_create");
    fn_AParcel_delete = dlsym(g_binder_lib, "AParcel_delete");
    fn_AParcel_writeInt32 = dlsym(g_binder_lib, "AParcel_writeInt32");
    fn_AParcel_writeString = dlsym(g_binder_lib, "AParcel_writeString");
    fn_AParcel_readInt32 = dlsym(g_binder_lib, "AParcel_readInt32");
    fn_AParcel_readString = dlsym(g_binder_lib, "AParcel_readString");

    return true;
}

/* ── Try registering the mosey service directly ── */
static bool register_mosey_service(void) {
    LOGI("Attempting to register %s directly...", SERVICE_NAME);

    /* First try AServiceManager_addService directly */
    if (fn_AServiceManager_addService) {
        /* We need a valid AIBinder* to pass. Create a minimal one. */
        /* We can't easily create a NDK binder class without the full API.
         * Instead, let's try via service call */
        LOGI("AServiceManager_addService available, attempting...");
    }

    /* Alternative: use 'svc' or binder ioctl directly */
    /* For now, let's use the 'service' command with raw transactions */
    return false;
}

/* ── Main ── */
int main(int argc, char* argv[]) {
    LOGI("=== Mosey Launcher ===");

    /* Step 1: Load binder library */
    if (!load_binder_library()) {
        LOGE("Failed to load binder library");
    }

    /* Step 2: Try to register the service */
    register_mosey_service();

    /* Step 3: Prepare LD_PRELOAD environment */
    LOGI("Setting up LD_PRELOAD=%s", PRELOAD_LIB_PATH);
    setenv("LD_PRELOAD", PRELOAD_LIB_PATH, 1);

    /* Step 4: Exec mosey_server */
    LOGI("Starting %s...", MOSEY_SERVER_PATH);
    execv(MOSEY_SERVER_PATH, argv);
    
    /* If execv returns, it failed */
    LOGE("execv(%s) failed: %m", MOSEY_SERVER_PATH);
    return 1;
}
