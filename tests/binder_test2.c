/*
 * binder_test2.c - Minimal NDK Binder test for mosey_server
 * Uses <android/binder_ibinder.h> for AIBinder_transact directly.
 *
 * Compile: aarch64-linux-android35-clang \
 *   -o binder_test2 binder_test2.c -ldl -llog
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

typedef AIBinder* (*old_getService_t)(const char* name);

int main(void) {
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        printf("[!] dlopen libbinder_ndk.so failed\n");
        return 1;
    }

    old_getService_t getService = (old_getService_t)dlsym(lib, "AServiceManager_getService");
    if (!getService) {
        printf("[!] dlsym AServiceManager_getService failed\n");
        return 1;
    }

    printf("[*] Getting service %s ...\n", SERVICE_NAME);
    AIBinder* service = getService(SERVICE_NAME);
    if (!service) {
        printf("[!] getService returned NULL\n");
        return 1;
    }
    printf("[✓] service = %p\n", (void*)service);

    /* Use official NDK AIBinder_transact (AParcel**) */
    printf("[*] Creating input parcel...\n");
    AParcel* in = AParcel_create();
    if (!in) { printf("[!] AParcel_create failed\n"); return 1; }

    /* writeInterfaceToken: strictModePolicy=0 + String descriptor */
    AParcel_writeInt32(in, 0);
    AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));

    printf("[*] Calling AIBinder_transact...\n");
    AParcel* reply = NULL;
    binder_status_t st = AIBinder_transact(service, TR_GET_VERSION, &in, &reply, 0);
    /* in is now owned by API, do not touch */

    printf("[*] AIBinder_transact returned: %d (0x%x)\n", st, (unsigned int)st);

    if (st != STATUS_OK) {
        printf("[!] Transaction failed\n");
        return 1;
    }

    if (!reply) {
        printf("[!] reply is NULL\n");
        return 1;
    }

    int32_t exc = 0;
    AParcel_readInt32(reply, &exc);
    printf("[*] exception: %d\n", exc);

    int32_t version = -1;
    AParcel_readInt32(reply, &version);
    printf("[✓] getVersion = %d\n", version);

    AParcel_delete(reply);
    /* Note: 'in' is owned by API after transact, don't delete it */
    printf("[*] Done\n");
    return 0;
}
