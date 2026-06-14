/*
 * binder_test7.c - Test: associate a class with the proxy then transact
 *
 * Compile: aarch64-linux-android35-clang \
 *   -o binder_test7 binder_test7.c -lbinder_ndk -ldl -llog
 */
#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>

#define SERVICE_NAME "com.google.android.moseyservice.IMoseyService/default"
#define DESCRIPTOR   "com.google.android.moseyservice.IMoseyService"
#define TR_GET_VERSION 16777215

typedef AIBinder* (*old_getSvc_t)(const char*);

static binder_status_t my_onTransact(AIBinder* binder, transaction_code_t code,
                                     const AParcel* in, AParcel* out) {
    (void)binder; (void)code; (void)in; (void)out;
    return STATUS_OK;
}
static void* my_onCreate(void* args) { (void)args; return NULL; }
static void my_onDestroy(void* user_data) { (void)user_data; }

int main(void) {
    printf("=== Associating class with AIBinder proxy ===\n");

    /* Define a class for mosey service */
    const AIBinder_Class* cls = AIBinder_Class_define(
        DESCRIPTOR, my_onCreate, my_onDestroy, my_onTransact);
    if (!cls) { printf("Class_define FAILED\n"); return 1; }
    printf("Class defined: %p\n", (void*)cls);

    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW);
    if (!lib) { printf("dlopen FAIL\n"); return 1; }
    old_getSvc_t getSvc = (old_getSvc_t)dlsym(lib, "AServiceManager_getService");

    AIBinder* svc = getSvc(SERVICE_NAME);
    if (!svc) { printf("getService returned NULL\n"); return 1; }
    printf("svc=%p alive=%d class_before=%p\n",
           (void*)svc, AIBinder_isAlive(svc), (void*)AIBinder_getClass(svc));

    /* Associate the class with the proxy */
    binder_status_t assoc_st = AIBinder_associateClass(svc, cls);
    printf("associateClass = %d\n", assoc_st);
    printf("class_after = %p\n", (void*)AIBinder_getClass(svc));

    /* Try NDK API transact (AParcel**) */
    printf("\n--- NDK API transact ---\n");
    AParcel* in = AParcel_create();
    AParcel_writeInt32(in, 0);
    AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));
    AParcel* reply = NULL;
    binder_status_t st = AIBinder_transact(svc, TR_GET_VERSION, &in, &reply, 0);
    printf("transact = %d (0x%x)\n", st, (unsigned)st);
    if (st == 0 && reply) {
        int32_t exc = 0, ver = -1;
        AParcel_readInt32(reply, &exc);
        AParcel_readInt32(reply, &ver);
        printf("exc=%d ver=%d\n", exc, ver);
        AParcel_delete(reply);
    } else if (reply) {
        AParcel_delete(reply);
    }

    /* Now try prepareTransaction + transact */
    printf("\n--- AIBinder_prepareTransaction ---\n");
    AParcel* prep = NULL;
    st = AIBinder_prepareTransaction(svc, &prep);
    printf("prepareTransaction = %d\n", st);
    if (st == 0 && prep) {
        reply = NULL;
        st = AIBinder_transact(svc, TR_GET_VERSION, &prep, &reply, 0);
        printf("transact = %d\n", st);
        if (st == 0 && reply) {
            int32_t exc = 0, ver = -1;
            AParcel_readInt32(reply, &exc);
            AParcel_readInt32(reply, &ver);
            printf("exc=%d ver=%d\n", exc, ver);
            AParcel_delete(reply);
        }
    }

    printf("\nDONE\n");
    return 0;
}
