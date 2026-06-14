/*
 * test_binder_direct.c — Minimal NDK Binder test for mosey_server
 *
 * Directly tests the NDK Binder interface with various parameter
 * combinations to find what makes start() work.
 *
 * Compile:
 *   aarch64-linux-android35-clang -o test_binder_direct test_binder_direct.c \
 *     -lbinder_ndk -ldl -llog
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <dlfcn.h>
#include <android/log.h>
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>

#define TAG "BinderDirect"
#define LOGI(...) fprintf(stdout, __VA_ARGS__); fprintf(stdout, "\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")

#define SERVICE_NAME "com.google.android.moseyservice.IMoseyService/default"
#define DESCRIPTOR "com.google.android.moseyservice.IMoseyService"

#define TR_GET_VERSION  16777215
#define TR_START        1
#define TR_STOP         2
#define TR_UPDATE       3

static AIBinder* g_service = NULL;
static const AIBinder_Class* g_class = NULL;

/* Stub callbacks — needed for AIBinder_Class_define */
static void* stub_on_create(void* args) { return NULL; }
static void stub_on_destroy(void* user_data) {}
static binder_status_t stub_on_transact(AIBinder* binder, transaction_code_t code,
                                         const AParcel* in, AParcel* out) {
    LOGI("← onTransact(code=%u)", code);
    return STATUS_OK;
}

/* Dynamically loaded AServiceManager_getService */
static AIBinder* (*fn_AServiceManager_getService)(const char* name) = NULL;
static void (*fn_ABinderProcess_startThreadPool)(void) = NULL;

static void load_binder_lib(void) {
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { LOGE("dlopen failed"); exit(1); }
    fn_AServiceManager_getService = dlsym(lib, "AServiceManager_getService");
    fn_ABinderProcess_startThreadPool = dlsym(lib, "ABinderProcess_startThreadPool");
    if (!fn_AServiceManager_getService) { LOGE("getService not found"); exit(1); }
}

/* Wrapper for transact: prepare, write, call, check exception */
static bool do_transact(AParcel** in_ptr, transaction_code_t code, AParcel** out_reply) {
    AIBinder_transact(g_service, code, in_ptr, out_reply, 0);
    *in_ptr = NULL;  /* consumed */
    if (!*out_reply) { LOGE("  NULL reply"); return false; }
    int32_t exc = 0;
    AParcel_readInt32(*out_reply, &exc);
    if (exc != 0) {
        LOGE("  exception: %d", exc);
        if (exc == -4) { /* EX_SERVICE_SPECIFIC */
            int32_t service_err;
            AParcel_readInt32(*out_reply, &service_err);
            LOGE("  ServiceSpecificException: code=%d", service_err);
        }
        /* Dump remaining */
        int32_t remaining = AParcel_getDataSize(*out_reply) - AParcel_getDataPosition(*out_reply);
        if (remaining > 0) {
            LOGE("  remaining %d bytes:", remaining);
            while (AParcel_getDataPosition(*out_reply) < AParcel_getDataSize(*out_reply)) {
                int32_t val;
                if (AParcel_readInt32(*out_reply, &val) != STATUS_OK) break;
                LOGE("    0x%08x", (uint32_t)val);
            }
        }
        return false;
    }
    return true;
}

static int32_t getVersion(void) {
    AParcel* in = NULL;
    AIBinder_prepareTransaction(g_service, &in);
    AParcel* reply = NULL;
    if (!do_transact(&in, TR_GET_VERSION, &reply)) return -1;
    int32_t ver;
    AParcel_readInt32(reply, &ver);
    AParcel_delete(reply);
    return ver;
}

static int update(const char* cc) {
    AParcel* in = NULL;
    AIBinder_prepareTransaction(g_service, &in);
    AParcel_writeInt32(in, 1);  // hasValue
    int32_t pos = AParcel_getDataPosition(in);
    AParcel_writeInt32(in, 0);  // size
    AParcel_writeString(in, cc, strlen(cc));
    int32_t end = AParcel_getDataPosition(in);
    AParcel_setDataPosition(in, pos);
    AParcel_writeInt32(in, end - pos);
    AParcel_setDataPosition(in, end);

    AParcel* reply = NULL;
    bool ok = do_transact(&in, TR_UPDATE, &reply);
    if (ok && reply) AParcel_delete(reply);
    return ok ? 0 : -1;
}

static int start_test(int32_t* filters, int count,
                       int use_hasvalue, int use_size_patch,
                       int use_callback, int stability) {
    AParcel* in = NULL;
    if (AIBinder_prepareTransaction(g_service, &in) != STATUS_OK) {
        LOGE("prepareTransaction FAILED");
        return -1;
    }

    if (use_hasvalue) {
        LOGI("Format: hasValue=%d sizePatch=%d callback=%d stability=0x%x",
             use_hasvalue, use_size_patch, use_callback, stability);
        AParcel_writeInt32(in, 1);  // hasValue
    }
    
    int32_t pos_before = AParcel_getDataPosition(in);
    if (use_size_patch) {
        AParcel_writeInt32(in, 0);  // size placeholder
    }

    // Write filters
    AParcel_writeInt32Array(in, filters, count);

    // Write callback binder
    if (use_callback) {
        // Create callback with same descriptor as service
        static const AIBinder_Class* cb_class = NULL;
        if (!cb_class) {
            cb_class = AIBinder_Class_define(DESCRIPTOR, stub_on_create,
                                              stub_on_destroy, stub_on_transact);
        }
        AIBinder* cb = AIBinder_new(cb_class, NULL);
        AParcel_writeStrongBinder(in, cb);
    } else {
        // Write NULL binder
        AParcel_writeStrongBinder(in, NULL);
    }

    AParcel_writeInt32(in, stability);

    if (use_size_patch) {
        int32_t pos_after = AParcel_getDataPosition(in);
        AParcel_setDataPosition(in, pos_before);
        AParcel_writeInt32(in, pos_after - pos_before);
        AParcel_setDataPosition(in, pos_after);
    }

    AParcel* reply = NULL;
    bool ok = do_transact(&in, TR_START, &reply);
    if (reply) AParcel_delete(reply);
    return ok ? 0 : -1;
}

int main() {
    load_binder_lib();

    LOGI("=== Direct Binder Test ===\n");
    LOGI("Connecting to %s...", SERVICE_NAME);
    g_service = fn_AServiceManager_getService(SERVICE_NAME);
    if (!g_service) { LOGE("Service not found!"); return 1; }
    LOGI("Connected.\n");

    // Define and associate class
    g_class = AIBinder_Class_define(DESCRIPTOR, stub_on_create, stub_on_destroy, stub_on_transact);
    AIBinder_associateClass(g_service, g_class);
    
    // Start thread pool for potential callbacks
    if (fn_ABinderProcess_startThreadPool)
        fn_ABinderProcess_startThreadPool();

    // Test getVersion
    int ver = getVersion();
    LOGI("getVersion() = %d\n", ver);

    // Test update
    LOGI("--- update(\"US\") ---");
    int up_ret = update("US");
    LOGI("update() = %d\n", up_ret);

    // Test start with various combinations
    int32_t filters[] = {13};
    
    // Test 1: hasValue + sizePatch + callback + MAX_VALUE
    LOGI("--- Test 1: Standard Java AIDL format ---");
    int r1 = start_test(filters, 1, 1, 1, 1, 0x7FFFFFFF);
    LOGI("start() = %d\n", r1);

    // Test 2: No hasValue, no sizePatch
    LOGI("--- Test 2: No hasValue, no sizePatch ---");
    int r2 = start_test(filters, 1, 0, 0, 1, 0x7FFFFFFF);
    LOGI("start() = %d\n", r2);

    // stop() to reset state
    LOGI("--- stop() ---");
    AParcel* in = NULL;
    AIBinder_prepareTransaction(g_service, &in);
    AParcel_writeInt32(in, 1);  // hasValue
    AParcel_writeInt32(in, 4);  // size
    AParcel* reply = NULL;
    do_transact(&in, TR_STOP, &reply);
    if (reply) AParcel_delete(reply);
    LOGI("stop() done\n");

    // Test 3: hasValue + sizePatch + callback=0 + stability=0
    LOGI("--- Test 3: null callback + stability=0 ---");
    int r3 = start_test(filters, 1, 1, 1, 0, 0);
    LOGI("start() = %d\n", r3);

    // Test 4: hasValue + sizePatch + callback + stability=0
    LOGI("--- Test 4: with callback + stability=0 ---");
    int r4 = start_test(filters, 1, 1, 1, 1, 0);
    LOGI("start() = %d\n", r4);

    // Test 5: hasValue + sizePatch + callback + MAX_VALUE with different filters
    int32_t filters_empty[] = {};
    LOGI("--- Test 5: empty filters ---");
    int r5 = start_test(filters_empty, 0, 1, 1, 1, 0x7FFFFFFF);
    LOGI("start() = %d\n", r5);

    LOGI("\n=== All tests done ===");
    return 0;
}
