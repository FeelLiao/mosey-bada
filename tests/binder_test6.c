/*
 * binder_test6.c - Debug: test various AIBinder_prepareTransaction approaches
 *
 * Compile: aarch64-linux-android35-clang \
 *   -o binder_test6 binder_test6.c -lbinder_ndk -ldl -llog
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

int main(void) {
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW);
    if (!lib) { printf("dlopen FAIL\n"); return 1; }
    old_getSvc_t getSvc = (old_getSvc_t)dlsym(lib, "AServiceManager_getService");
    if (!getSvc) { printf("dlsym FAIL\n"); return 1; }

    AIBinder* svc = getSvc(SERVICE_NAME);
    if (!svc) { printf("getService returned NULL\n"); return 1; }
    printf("svc=%p alive=%d\n", (void*)svc, AIBinder_isAlive(svc));

    /* Try 1: AIBinder_prepareTransaction */
    printf("\n--- Test 1: AIBinder_prepareTransaction ---\n");
    AParcel* prep = NULL;
    binder_status_t prep_st = AIBinder_prepareTransaction(svc, &prep);
    printf("prepareTransaction = %d\n", prep_st);
    if (prep_st == 0 && prep) {
        AParcel* reply = NULL;
        binder_status_t st = AIBinder_transact(svc, TR_GET_VERSION, &prep, &reply, 0);
        printf("transact = %d\n", st);
        if (st == 0 && reply) {
            int32_t exc = 0, ver = 0;
            AParcel_readInt32(reply, &exc);
            AParcel_readInt32(reply, &ver);
            printf("exc=%d ver=%d\n", exc, ver);
            AParcel_delete(reply);
        }
    }

    /* Try 2: Get BBinder and check class */
    printf("\n--- Test 2: Check AIBinder class ---\n");
    printf("AIBinder_getClass=%p\n", AIBinder_getClass(svc));

    /* Try 3: Use dlsym on the REAL AIBinder_transact symbol to see
     * if the NDK header declaration matches the actual implementation */
    printf("\n--- Test 3: dlsym AIBinder_transact and cast ---\n");
    /* Try AParcel* (old) signature via dlsym */
    typedef int32_t (*old_transact_t)(AIBinder*, uint32_t, AParcel*, AParcel*, uint32_t);
    old_transact_t old_trans = (old_transact_t)dlsym(lib, "AIBinder_transact");
    printf("old transact=%p\n", old_trans);

    if (old_trans) {
        AParcel* in = AParcel_create();
        AParcel_writeInt32(in, 0);
        AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));
        AParcel* out = AParcel_create();
        int32_t st = old_trans(svc, TR_GET_VERSION, in, out, 0);
        printf("old transact ret=%d (0x%x)\n", st, (unsigned)st);
        if (st == 0) {
            int32_t exc = 0, ver = 0;
            AParcel_readInt32(out, &exc);
            AParcel_readInt32(out, &ver);
            printf("exc=%d ver=%d\n", exc, ver);
        }
        AParcel_delete(in);
        AParcel_delete(out);
    }

    /* Try 4: No strict mode, just descriptor */
    printf("\n--- Test 4: dlsym old API, just descriptor ---\n");
    if (old_trans) {
        AParcel* in = AParcel_create();
        AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));
        AParcel* out = AParcel_create();
        int32_t st = old_trans(svc, TR_GET_VERSION, in, out, 0);
        printf("old transact ret=%d (0x%x)\n", st, (unsigned)st);
        if (st == 0) {
            int32_t exc = 0, ver = 0;
            AParcel_readInt32(out, &exc);
            AParcel_readInt32(out, &ver);
            printf("exc=%d ver=%d\n", exc, ver);
        }
        AParcel_delete(in);
        AParcel_delete(out);
    }

    /* Try 5: New API style with dlsym */
    printf("\n--- Test 5: dlsym new API (AParcel**) ---\n");
    typedef int32_t (*new_transact_t)(AIBinder*, uint32_t, AParcel**, AParcel**, uint32_t);
    new_transact_t new_trans = (new_transact_t)dlsym(lib, "AIBinder_transact");
    printf("new transact=%p\n", new_trans);
    if (new_trans) {
        AParcel* in = AParcel_create();
        AParcel_writeInt32(in, 0);
        AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));
        AParcel* reply = NULL;
        int32_t st = new_trans(svc, TR_GET_VERSION, &in, &reply, 0);
        printf("new transact ret=%d (0x%x), reply=%p\n", st, (unsigned)st, (void*)reply);
        if (reply) AParcel_delete(reply);
    }

    printf("\nDONE\n");
    return 0;
}
