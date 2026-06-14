#!/system/bin/sh
# post-mount.sh - Mosey Enabler
# Runs after the Hybrid Mount metamodule has executed metamount.sh.

LOG_TAG="mosey-enabler"
MODPATH="${MODPATH:-/data/adb/modules/mosey-enabler}"

log -t "$LOG_TAG" "=== Mosey Enabler post-mount ==="
log -t "$LOG_TAG" "[*] Module path: $MODPATH"

verify_path() {
    if [ -e "$1" ]; then
        log -t "$LOG_TAG" "  [✓] $1"
        return 0
    fi
    log -t "$LOG_TAG" "  [✗] $1 - NOT VISIBLE AFTER MOUNT"
    return 1
}

verify_hash() {
    local PATH_VALUE="$1" EXPECTED="$2" ACTUAL
    ACTUAL=$(sha256sum "$PATH_VALUE" 2>/dev/null | awk '{print $1}')
    if [ "$ACTUAL" = "$EXPECTED" ]; then
        log -t "$LOG_TAG" "  [✓] $PATH_VALUE sha256=$ACTUAL"
        return 0
    fi
    log -t "$LOG_TAG" "  [✗] $PATH_VALUE hash mismatch actual=$ACTUAL expected=$EXPECTED"
    return 1
}

verify_path "/odm/bin/mosey_server"
verify_path "/odm/bin/mosey_bridge"
verify_path "/odm/lib64/libmosey_daemon_ffi.so"
verify_path "/odm/lib64/libmosey_preload.so"
verify_path "/odm/framework/mosey-shim.jar"
verify_path "/odm/etc/mosey-shim/mosey-shim.p12"
verify_path "/odm/etc/permissions/android.hardware.wifi.aware.xml"
verify_path "/odm/vendor/etc/wifi/WCNSS_qcom_cfg.ini"
verify_hash "/odm/etc/permissions/android.hardware.wifi.aware.xml" \
    "00c217f6242bd197e49d273a1bba48aae82c03d3f1c83820a1802bdfe908e54f"
verify_hash "/odm/vendor/etc/wifi/WCNSS_qcom_cfg.ini" \
    "65b28a3639ce1c807618eff2b0e2c85a1f958acac495c0810cfbc00db588fb53"
verify_path "/odm/etc/wifi/peach/bdwlan.elf"
verify_path "/odm/etc/wifi/peach/bdwlan.b0a"
verify_path "/odm/etc/wifi/peach/bdwlan.b0e"
verify_path "/odm/etc/wifi/peach/bdwlan.b0i"
verify_path "/system_ext/priv-app/MoseyBridgeShim/MoseyBridgeShim.apk"
verify_path "/system_ext/etc/permissions/privapp-permissions-dev.bluehouse.moseybridgeshim.xml"
verify_path "/system_ext/etc/default-permissions/default-permissions-dev.bluehouse.moseybridgeshim.xml"
verify_path "/system_ext/priv-app/MoseyApp/MoseyApp.apk"

if [ -x "/data/adb/ksud" ]; then
    /data/adb/ksud feature set kernel_umount 0 >/dev/null 2>&1
    /data/adb/ksud feature save >/dev/null 2>&1
    log -t "$LOG_TAG" "[*] KernelSU kernel_umount disabled for module-backed app visibility"
fi

if [ -x "/data/adb/modules/hybrid_mount/hybrid-mount" ]; then
    STATUS=$(/data/adb/modules/hybrid_mount/hybrid-mount daemon status 2>/dev/null)
    log -t "$LOG_TAG" "[*] Hybrid Mount status: $STATUS"
fi

log -t "$LOG_TAG" "=== Mosey Enabler post-mount complete ==="
