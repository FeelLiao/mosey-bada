/*
 * binder_test.c - Minimal NDK Binder test for mosey_server
 *
 * Compile: aarch64-linux-android35-clang \
 *   -o binder_test binder_test.c -landroid -lbinder_ndk
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/binder_ibinder.h>
#include <android/binder_parcel.h>
#include <android/binder_status.h>

#define SERVICE_NAME "com.google.android.moseyservice.IMoseyService/default"
#define DESCRIPTOR   "com.google.android.moseyservice.IMoseyService"
#define TR_GET_VERSION 16777215  /* 0xFFFFFF */

int main(void) {
    AIBinder* service = NULL;
    binder_status_t st;

    printf("[*] Getting service %s ...\n", SERVICE_NAME);
    st = AServiceManager_getService(SERVICE_NAME, &service);

    if (st != STATUS_OK) {
        printf("[!] AServiceManager_getService failed: %d\n", st);
        return 1;
    }
    if (!service) {
        printf("[!] service is NULL\n");
        return 1;
    }
    printf("[✓] service = %p\n", (void*)service);

    /* Try old API: return AIBinder* directly */
    printf("[*] Trying old AServiceManager_getService API ...\n");
    typedef AIBinder* (*old_getService_t)(const char*);
    void* lib = dlopen("libbinder_ndk.so", RTLD_LOCAL);
    if (lib) {
        old_getService_t old_fn = (old_getService_t)dlsym(lib, "AServiceManager_getService");
        if (old_fn) {
            AIBinder* svc2 = old_fn(SERVICE_NAME);
            printf("[*] old API returned: %p\n", (void*)svc2);
            if (svc2 && svc2 != service) {
                printf("[!] old API returned DIFFERENT pointer!\n");
            }
        } else {
            printf("[!] dlsym old AServiceManager_getService failed\n");
        }
        dlclose(lib);
    }

    /* Now try transact - old style */
    printf("[*] Testing AIBinder_transact (new API, AParcel**) ...\n");

    AParcel* in = AParcel_create();
    if (!in) { printf("[!] AParcel_create failed\n"); return 1; }
    AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));

    AParcel* reply = NULL;
    st = AIBinder_transact(service, TR_GET_VERSION, &in, &reply, 0);
    in = NULL; /* API owns it now */

    printf("[*] AIBinder_transact returned: %d\n", st);
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
    AIBinder_decStrong(service);
    printf("[*] Done\n");
    return 0;
}
