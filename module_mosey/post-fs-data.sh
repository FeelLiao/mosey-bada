#!/system/bin/sh
# post-fs-data.sh - Mosey Enabler
# 运行时机: post-fs-data 阶段。KernelSU metamodule 的 metamount.sh
# 会在普通模块 post-fs-data.sh 之后执行，因此这里不假设 /odm 或
# /system_ext 已包含本模块文件。挂载后检查放在 post-mount.sh。

LOG_TAG="mosey-enabler"
MODPATH="${MODPATH:-/data/adb/modules/mosey-enabler}"

log -t "$LOG_TAG" "=== Mosey Enabler post-fs-data ==="
log -t "$LOG_TAG" "[*] Module path: $MODPATH"
log -t "$LOG_TAG" "[*] Waiting for Hybrid Mount metamount.sh; mounted-file checks run in post-mount.sh"

# PackageManager caches parsed manifests independently from the mounted APK.
# Invalidate only that rebuildable cache when the module APK actually changes.
STATE_DIR="/data/adb/mosey-enabler"
HASH_FILE="$STATE_DIR/shim.sha256"
SHIM_APK="$MODPATH/system_ext/priv-app/MoseyBridgeShim/MoseyBridgeShim.apk"

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR" 2>/dev/null

if [ -f "$SHIM_APK" ]; then
    CURRENT_HASH=$(sha256sum "$SHIM_APK" 2>/dev/null | awk '{print $1}')
    PREVIOUS_HASH=$(cat "$HASH_FILE" 2>/dev/null)
    if [ -n "$CURRENT_HASH" ] && [ "$CURRENT_HASH" != "$PREVIOUS_HASH" ]; then
        log -t "$LOG_TAG" "[*] Shim APK changed; invalidating PackageManager parse cache"
        CACHE_CLEARED=true
        if [ -d /data/system/package_cache ]; then
            find /data/system/package_cache -mindepth 1 -maxdepth 1 \
                -exec rm -rf {} + 2>/dev/null || CACHE_CLEARED=false
        fi
        if [ "$CACHE_CLEARED" = true ]; then
            echo "$CURRENT_HASH" > "$HASH_FILE"
            chmod 600 "$HASH_FILE" 2>/dev/null
        else
            log -t "$LOG_TAG" "[!] PackageManager cache cleanup failed; will retry next boot"
        fi
    else
        log -t "$LOG_TAG" "[*] Shim APK hash unchanged; preserving PackageManager cache"
    fi
else
    log -t "$LOG_TAG" "[!] Shim payload missing: $SHIM_APK"
fi

# ========== 记录 init 服务状态 ==========
SVC_CHECK=$(getprop | grep mosey_server 2>/dev/null)
log -t "$LOG_TAG" "[*] mosey_server service prop check: $SVC_CHECK"

log -t "$LOG_TAG" "=== Mosey Enabler post-fs-data complete ==="
