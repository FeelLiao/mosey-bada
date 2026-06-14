/*
 * test_mosey_direct.c — Directly call mosey_start_4 from libmosey_daemon_ffi.so
 *
 * Bypasses the Rust Binder server and calls the FFI function directly.
 *
 * mosey_start_4 signature (from ARM64 disassembly):
 *   int32_t mosey_start_4(void* channels, int32_t channels_len,
 *                          int32_t max_mdns, const char* cc,
 *                          int32_t config)
 *
 *   x0 = channels (pointer to int32[] channel list)
 *   w1 = channels_len (number of channels)
 *   w2 = max_mdns (max mDNS entries)
 *   x3 = cc (2-letter country code string)
 *   w4 = config (byte-level enum: 0/1/2?)
 *
 * Compile:
 *   aarch64-linux-android35-clang -o test_mosey_direct test_mosey_direct.c -ldl
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <stdint.h>

typedef int32_t (*mosey_start_4_fn)(void* channels, int32_t channels_len,
                                     int32_t max_mdns,
                                     const char* cc,
                                     int32_t config);
typedef int32_t (*mosey_stop_fn)(void);

static void test_start(const char* label, mosey_start_4_fn fn,
                       int32_t* channels, int32_t channels_len,
                       int32_t max_mdns, const char* cc, int32_t config) {
    printf("[*] %s\n", label);
    printf("    channels=%p(%d), max_mdns=%d, cc=%s, config=%d\n",
           (void*)channels, channels_len, max_mdns, cc ? cc : "NULL", config);
    int32_t result = fn((void*)channels, channels_len, max_mdns, cc, config);
    printf("    result = %d (0x%08x)\n\n", result, result);
}

int main(int argc, char* argv[]) {
    printf("=== test_mosey_direct ===\n\n");

    void* handle = dlopen("/odm/lib64/libmosey_daemon_ffi.so", RTLD_NOW | RTLD_LOCAL);
    if (!handle) {
        printf("[!] dlopen failed: %s\n", dlerror());
        return 1;
    }
    printf("[+] libmosey_daemon_ffi.so loaded\n");

    mosey_start_4_fn start_fn = (mosey_start_4_fn)dlsym(handle, "mosey_start_4");
    mosey_stop_fn stop_fn = (mosey_stop_fn)dlsym(handle, "mosey_stop");
    void* dump_fn = dlsym(handle, "mosey_dump");
    void* reset_fn = dlsym(handle, "mosey_reset");

    printf("  mosey_start_4: %p\n", (void*)start_fn);
    printf("  mosey_stop:    %p\n", (void*)stop_fn);
    printf("  mosey_dump:    %p\n", dump_fn);
    printf("  mosey_reset:   %p\n\n", reset_fn);

    if (!start_fn) {
        printf("[!] mosey_start_4 not found\n");
        dlclose(handle);
        return 1;
    }

    // Test 1: NULL channels, 0 length, "US" country, config=0
    test_start("Test 1: NULL, 0, 0, \"US\", 0",
               start_fn, NULL, 0, 0, "US", 0);
    if (stop_fn) { printf("[*] stop=%d\n\n", stop_fn()); }

    // Test 2: Single channel [13], "US", config=0
    int32_t ch1[] = {13};
    test_start("Test 2: [13], 1, 0, \"US\", 0",
               start_fn, ch1, 1, 0, "US", 0);
    if (stop_fn) { printf("[*] stop=%d\n\n", stop_fn()); }

    // Test 3: Multiple AWDL channels, "US"
    int32_t ch2[] = {6, 44, 149, 36, 40, 11};
    test_start("Test 3: [6,44,149,36,40,11], 6, 0, \"US\", 0",
               start_fn, ch2, 6, 0, "US", 0);
    if (stop_fn) { printf("[*] stop=%d\n\n", stop_fn()); }

    // Test 4: With country code "CN" (matching device)
    test_start("Test 4: [13], 1, 0, \"CN\", 0",
               start_fn, ch1, 1, 0, "CN", 0);
    if (stop_fn) { printf("[*] stop=%d\n\n", stop_fn()); }

    // Test 5: With config=1
    test_start("Test 5: [13], 1, 0, \"US\", 1",
               start_fn, ch1, 1, 0, "US", 1);
    if (stop_fn) { printf("[*] stop=%d\n\n", stop_fn()); }

    // Test 6: Config=2 (byte values 0,1,2 are used as state)
    test_start("Test 6: [13], 1, 0, \"US\", 2",
               start_fn, ch1, 1, 0, "US", 2);
    if (stop_fn) { printf("[*] stop=%d\n\n", stop_fn()); }

    dlclose(handle);
    printf("\n=== Done ===\n");
    return 0;
}
