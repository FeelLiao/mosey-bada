/*
 * dl_shim.c - LD_PRELOAD shim
 * Intercepts dlopen("/odm/lib64/libmosey_daemon_ffi.so") and redirects
 * to dlopen("libmosey_daemon_ffi.so") so LD_LIBRARY_PATH finds it.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <string.h>
#include <stddef.h>

// Ref-counted recursion guard: count how many times we're inside our
// intercepted dlopen so we don't intercept our own dlsym call.
static __thread int in_interceptor = 0;

void *dlopen(const char *filename, int flags) {
    // Resolve real dlopen from RTLD_NEXT (the "next" library after this shim)
    static void *(*real_dlopen)(const char *, int) = NULL;
    
    if (!real_dlopen) {
        // Temporarily bypass our interceptor to resolve RTLD_NEXT
        in_interceptor = 1;
        real_dlopen = (void *(*)(const char *, int))dlsym(RTLD_NEXT, "dlopen");
        in_interceptor = 0;
    }
    
    if (in_interceptor) {
        // Recursive call from within our shim - pass through
        return real_dlopen(filename, flags);
    }
    
    in_interceptor = 1;
    void *result;
    
    if (filename && strstr(filename, "/odm/lib64/libmosey_daemon_ffi.so")) {
        // Redirect to relative path so LD_LIBRARY_PATH is used
        result = real_dlopen("libmosey_daemon_ffi.so", flags);
    } else {
        result = real_dlopen(filename, flags);
    }
    
    in_interceptor = 0;
    return result;
}
