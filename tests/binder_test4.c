/*
 * binder_test4.c - Minimal debug for mosey_server binder
 *
 * Compile: aarch64-linux-android35-clang \
 *   -o binder_test4 binder_test4.c -lbinder_ndk -ldl -llog
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
#define TR_GET_VERSION 16777215

typedef AIBinder* (*old_getService_t)(const char*);

int main(void) {
    printf("1) dlopen libbinder_ndk.so\n");
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { printf("FAIL\n"); return 1; }

    old_getService_t getSvc = (old_getService_t)dlsym(lib, "AServiceManager_getService");
    if (!getSvc) { printf("dlsym getService FAIL\n"); return 1; }

    printf("2) getService\n");
    AIBinder* svc = getSvc(SERVICE_NAME);
    if (!svc) { printf("getService returned NULL\n"); return 1; }
    printf("   svc=%p\n", (void*)svc);

    printf("3) isAlive=%d\n", AIBinder_isAlive(svc));

    printf("4) Creating parcel with strictMode=0 + descriptor\n");
    AParcel* in = AParcel_create();
    if (!in) { printf("AParcel_create FAIL\n"); return 1; }
    AParcel_writeInt32(in, 0);
    AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));

    printf("5) AIBinder_transact code=0x%x\n", TR_GET_VERSION);
    AParcel* reply = NULL;
    binder_status_t st = AIBinder_transact(svc, TR_GET_VERSION, &in, &reply, 0);
    printf("   transact returned: %d (0x%x)\n", st, (unsigned)st);

    if (st == 0) {
        if (!reply) { printf("   reply is NULL!\n"); }
        else {
            int32_t exc = 0;
            AParcel_readInt32(reply, &exc);
            printf("   exception: %d\n", exc);
            int32_t ver = -1;
            AParcel_readInt32(reply, &ver);
            printf("   version: %d\n", ver);
            AParcel_delete(reply);
        }
    }

    printf("6) Try TR_START (code=1)\n");
    AParcel* in2 = AParcel_create();
    if (!in2) { printf("AParcel_create FAIL\n"); return 1; }
    AParcel_writeInt32(in2, 0);
    AParcel_writeString(in2, DESCRIPTOR, strlen(DESCRIPTOR));
    AParcel_writeInt32(in2, 0); /* empty filters */
    AParcel_writeStrongBinder(in2, NULL);
    AParcel_writeInt32(in2, 1);
    AParcel* reply2 = NULL;
    st = AIBinder_transact(svc, 1, &in2, &reply2, 0);
    printf("   transact returned: %d (0x%x)\n", st, (unsigned)st);
    if (st == 0 && reply2) {
        int32_t exc = 0;
        AParcel_readInt32(reply2, &exc);
        int32_t status = -1;
        AParcel_readInt32(reply2, &status);
        printf("   exc=%d status=%d\n", exc, status);
        AParcel_delete(reply2);
    }

    printf("7) incStrong/decStrong\n");
    AIBinder_incStrong(svc);
    AIBinder_decStrong(svc);

    printf("DONE\n");
    return 0;
}
