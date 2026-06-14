/*
 * binder_test3.c - Debug NDK Binder test for mosey_server
 * Tests AIBinder_isAlive and various transact approaches.
 *
 * Compile: aarch64-linux-android35-clang \
 *   -o binder_test3 binder_test3.c -lbinder_ndk -ldl -llog
 */

#include <stdio.h>
#include <string.h>
#include <dlfcn.h>
#include <android/log.h>
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>

#define SERVICE_NAME "com.google.android.moseyservice.IMoseyService/default"
#define DESCRIPTOR   "com.google.android.moseyservice.IMoseyService"
#define TR_GET_VERSION 16777215  /* 0xFFFFFF */
#define TR_START 1

typedef AIBinder* (*old_getService_t)(const char* name);

int main(void) {
    printf("[*] dlopen libbinder_ndk.so...\n");
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        printf("[!] dlopen failed\n");
        return 1;
    }

    old_getService_t getService = (old_getService_t)dlsym(lib, "AServiceManager_getService");
    if (!getService) {
        printf("[!] dlsym failed\n");
        return 1;
    }

    printf("[*] Getting service...\n");
    AIBinder* service = getService(SERVICE_NAME);
    if (!service) {
        printf("[!] getService returned NULL\n");
        return 1;
    }
    printf("[✓] service = %p\n", (void*)service);

    /* Check if alive */
    printf("[*] AIBinder_isAlive = %d\n", AIBinder_isAlive(service));

    /* Try 1: Empty transact (no data, FLAG_ONEWAY) */
    printf("\n=== Test 1: Empty oneway transact ===\n");
    AParcel* in1 = AParcel_create();
    AParcel* reply1 = NULL;
    binder_status_t st1 = AIBinder_transact(service, TR_GET_VERSION, &in1, &reply1, 1 /* FLAG_ONEWAY */);
    printf("st1 = %d (0x%x)\n", st1, st1);

    /* Try 2: With strictMode + descriptor, synchronous */
    printf("\n=== Test 2: strictMode+descriptor, sync ===\n");
    AParcel* in2 = AParcel_create();
    AParcel_writeInt32(in2, 0);  /* strictModePolicy */
    AParcel_writeString(in2, DESCRIPTOR, strlen(DESCRIPTOR));
    AParcel* reply2 = NULL;
    binder_status_t st2 = AIBinder_transact(service, TR_GET_VERSION, &in2, &reply2, 0);
    printf("st2 = %d (0x%x)\n", st2, st2);
    if (st2 == 0 && reply2) {
        int32_t exc = 0;
        AParcel_readInt32(reply2, &exc);
        int32_t ver = -1;
        AParcel_readInt32(reply2, &ver);
        printf("  exc=%d version=%d\n", exc, ver);
        AParcel_delete(reply2);
    }

    /* Try 3: Just descriptor, no strictMode (old behavior) */
    printf("\n=== Test 3: Just descriptor, no strictMode ===\n");
    AParcel* in3 = AParcel_create();
    AParcel_writeString(in3, DESCRIPTOR, strlen(DESCRIPTOR));
    AParcel* reply3 = NULL;
    binder_status_t st3 = AIBinder_transact(service, TR_GET_VERSION, &in3, &reply3, 0);
    printf("st3 = %d (0x%x)\n", st3, st3);
    if (st3 == 0 && reply3) {
        int32_t exc = 0;
        AParcel_readInt32(reply3, &exc);
        int32_t ver = -1;
        AParcel_readInt32(reply3, &ver);
        printf("  exc=%d version=%d\n", exc, ver);
        AParcel_delete(reply3);
    }

    /* Try 4: Transaction code = 1 (TR_START), sync with interface token */
    printf("\n=== Test 4: TR_START with interface token ===\n");
    AParcel* in4 = AParcel_create();
    AParcel_writeInt32(in4, 0);  /* strictModePolicy */
    AParcel_writeString(in4, DESCRIPTOR, strlen(DESCRIPTOR));
    AParcel_writeInt32(in4, 1);  /* hasValue */
    /* Write our callback binder */
    AParcel_writeStrongBinder(in4, NULL);  /* null callback */
    AParcel_writeInt32(in4, 0x7FFFFFFF);  /* stability */
    AParcel* reply4 = NULL;
    binder_status_t st4 = AIBinder_transact(service, 1, &in4, &reply4, 0);
    printf("st4 = %d (0x%x)\n", st4, st4);
    if (st4 == 0 && reply4) {
        int32_t exc = 0;
        AParcel_readInt32(reply4, &exc);
        int32_t status = 0;
        AParcel_readInt32(reply4, &status);
        printf("  exc=%d status=%d\n", exc, status);
        AParcel_delete(reply4);
    }

    /* Try 5: AIBinder_associateClass + prepareTransaction */
    printf("\n=== Test 5: AIBinder_prepareTransaction ===\n");

    /* Check if we can use prepareTransaction */
    typedef AIBinder* (*AIB_new_t)(const void* cls, void* args);
    typedef void* (*AIB_def_class_t)(const char* descriptor, void* onCreate, void* onDestroy, void* onTransact);
    typedef int (*AIB_assoc_t)(AIBinder* binder, const void* cls);

    AIB_new_t AIB_new = (AIB_new_t)dlsym(lib, "AIBinder_new");
    AIB_def_class_t AIB_def = (AIB_def_class_t)dlsym(lib, "AIBinder_Class_define");
    AIB_assoc_t AIB_assoc = (AIB_assoc_t)dlsym(lib, "AIBinder_associateClass");

    printf("  AIBinder_new=%p AIBinder_Class_define=%p AIBinder_associateClass=%p\n",
           AIB_new, AIB_def, AIB_assoc);

    if (AIB_assoc && AIB_assoc(service, (void*)1) == 0) {
        printf("  associateClass succeeded\n");
        AParcel* in5 = NULL;
        binder_status_t prep_st = AIBinder_prepareTransaction(service, &in5);
        printf("  prepareTransaction = %d\n", prep_st);
        if (prep_st == 0 && in5) {
            AParcel* reply5 = NULL;
            binder_status_t st5 = AIBinder_transact(service, TR_GET_VERSION, &in5, &reply5, 0);
            printf("  transact = %d\n", st5);
            if (st5 == 0 && reply5) {
                int32_t exc = 0, ver = -1;
                AParcel_readInt32(reply5, &exc);
                AParcel_readInt32(reply5, &ver);
                printf("  exc=%d version=%d\n", exc, ver);
                AParcel_delete(reply5);
            }
        }
    } else {
        printf("  associateClass failed (expected for remote)\n");
    }

    /* Try 6: Just empty parcel with no data at all */
    printf("\n=== Test 6: Empty parcel ===\n");
    AParcel* in6 = AParcel_create();
    AParcel* reply6 = NULL;
    binder_status_t st6 = AIBinder_transact(service, TR_GET_VERSION, &in6, &reply6, 0);
    printf("st6 = %d (0x%x)\n", st6, st6);
    if (st6 == 0 && reply6) {
        int32_t exc = 0, ver = -1;
        AParcel_readInt32(reply6, &exc);
        AParcel_readInt32(reply6, &ver);
        printf("  exc=%d version=%d\n", exc, ver);
        AParcel_delete(reply6);
    }

    /* Try 7: With real callback binder */
    printf("\n=== Test 7: TR_START with real callback = NULL ===\n");
    AParcel* in7 = AParcel_create();
    AParcel_writeInt32(in7, 0);
    AParcel_writeString(in7, DESCRIPTOR, strlen(DESCRIPTOR));
    AParcel_writeInt32(in7, 0);  /* filters count = 0 */
    AParcel_writeStrongBinder(in7, NULL);
    AParcel_writeInt32(in7, 1);
    AParcel* reply7 = NULL;
    binder_status_t st7 = AIBinder_transact(service, TR_START, &in7, &reply7, 0);
    printf("st7 = %d (0x%x)\n", st7, st7);
    if (st7 == 0 && reply7) {
        int32_t exc = 0, status = -1;
        AParcel_readInt32(reply7, &exc);
        AParcel_readInt32(reply7, &status);
        printf("  exc=%d status=%d\n", exc, status);
        AParcel_delete(reply7);
    }

    printf("\n[*] Done\n");
    return 0;
}
