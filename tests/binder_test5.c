/*
 * binder_test5.c - Test both old and new AServiceManager_getService APIs
 *
 * Compile: aarch64-linux-android35-clang \
 *   -o binder_test5 binder_test5.c -lbinder_ndk -ldl -llog
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
/* new API: binder_status_t func(const char*, AIBinder**) */
typedef int32_t (*new_getSvc_t)(const char*, AIBinder**);

int main(void) {
    void* lib = dlopen("libbinder_ndk.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { printf("dlopen FAIL\n"); return 1; }

    void* sym = dlsym(lib, "AServiceManager_getService");
    if (!sym) { printf("dlsym FAIL\n"); return 1; }

    old_getSvc_t old_fn = (old_getSvc_t)sym;
    new_getSvc_t new_fn = (new_getSvc_t)sym;

    /* Try old API */
    printf("=== Old API (returns AIBinder*) ===\n");
    AIBinder* svc_old = old_fn(SERVICE_NAME);
    printf("svc_old = %p\n", (void*)svc_old);
    if (svc_old) {
        printf("isAlive = %d\n", AIBinder_isAlive(svc_old));
        AParcel* in = AParcel_create();
        AParcel_writeInt32(in, 0);
        AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));
        AParcel* reply = NULL;
        int32_t st = AIBinder_transact(svc_old, TR_GET_VERSION, &in, &reply, 0);
        printf("transact = %d (0x%x)\n", st, (unsigned)st);
    }

    /* Try new API */
    printf("\n=== New API (returns status via AIBinder**) ===\n");
    AIBinder* svc_new = NULL;
    int32_t ret = new_fn(SERVICE_NAME, &svc_new);
    printf("ret = %d, svc_new = %p\n", ret, (void*)svc_new);
    if (ret == 0 && svc_new) {
        printf("isAlive = %d\n", AIBinder_isAlive(svc_new));
        AParcel* in = AParcel_create();
        AParcel_writeInt32(in, 0);
        AParcel_writeString(in, DESCRIPTOR, strlen(DESCRIPTOR));
        AParcel* reply = NULL;
        int32_t st = AIBinder_transact(svc_new, TR_GET_VERSION, &in, &reply, 0);
        printf("transact = %d (0x%x)\n", st, (unsigned)st);
    }

    /* Also check if there are multiple AServiceManager_getService symbols */
    printf("\n=== Check for symbols ===\n");
    void* sym2 = dlsym(lib, "AServiceManager_getService");
    printf("AServiceManager_getService = %p\n", sym2);
    void* wait_sym = dlsym(lib, "AServiceManager_waitForService");
    printf("AServiceManager_waitForService = %p\n", wait_sym);

    printf("\nDONE\n");
    return 0;
}
