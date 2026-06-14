#!/system/bin/sh
# uninstall.sh - Mosey Enabler 卸载脚本
# 停止 mosey_server 进程，清理模块残留

LOG_TAG="mosey-enabler"
STATE_DIR="/data/adb/mosey-enabler"

log -t "$LOG_TAG" "=== Mosey Enabler uninstall ==="

# Return Android Wi-Fi country selection to its normal, unforced mode.
cmd wifi force-country-code disabled >/dev/null 2>&1 || true
# 恢复 Wi-Fi（如果被模块断开）
svc wifi enable 2>/dev/null || cmd wifi set-wifi-enabled enabled 2>/dev/null || true
while ip -6 rule del pref 15490 >/dev/null 2>&1; do :; done
while ip -6 rule del pref 15500 >/dev/null 2>&1; do :; done

# ========== 1. 停止服务进程 ==========
MOSEY_PID=$(pidof mosey_server 2>/dev/null)
if [ -n "$MOSEY_PID" ]; then
    kill "$MOSEY_PID" 2>/dev/null
    log -t "$LOG_TAG" "[✓] Stopped mosey_server (PID: $MOSEY_PID)"
else
    log -t "$LOG_TAG" "[*] mosey_server not running"
fi

BRIDGE_PID=$(pidof mosey_bridge 2>/dev/null)
if [ -n "$BRIDGE_PID" ]; then
    kill "$BRIDGE_PID" 2>/dev/null
    log -t "$LOG_TAG" "[✓] Stopped mosey_bridge (PID: $BRIDGE_PID)"
fi

WATCHDOG_PID=$(cat "$STATE_DIR/watchdog.pid" 2>/dev/null)
if [ -n "$WATCHDOG_PID" ]; then
    kill "$WATCHDOG_PID" 2>/dev/null
    log -t "$LOG_TAG" "[✓] Stopped shim watchdog (PID: $WATCHDOG_PID)"
fi

SESSION_ID=$(cat "$STATE_DIR/install-session.id" 2>/dev/null)
if [ -n "$SESSION_ID" ]; then
    cmd package install-abandon "$SESSION_ID" >/dev/null 2>&1 || true
    log -t "$LOG_TAG" "[✓] Abandoned install session $SESSION_ID"
fi

for PKG in dev.bluehouse.moseybridgeshim dev.bluehouse.moseyshim; do
    if pidof "$PKG" >/dev/null 2>&1; then
        am force-stop "$PKG" >/dev/null 2>&1
        kill $(pidof "$PKG") >/dev/null 2>&1
        log -t "$LOG_TAG" "[✓] Stopped $PKG"
    fi
    if [ "$PKG" = "dev.bluehouse.moseybridgeshim" ]; then
        pm uninstall-system-updates "$PKG" >/dev/null 2>&1 || true
    fi
    pm uninstall --user 0 "$PKG" >/dev/null 2>&1 || true
done

rm -f /data/local/tmp/mosey-p2b.sock \
      /data/local/tmp/mosey-shim-events.sock \
      /data/local/tmp/mosey_bridge* \
      /data/local/tmp/mosey_shim.manual.log \
      /data/local/tmp/mosey_stdout.log \
      /data/local/tmp/mosey_out.txt \
      /data/local/tmp/mosey.log \
      /data/local/tmp/mosey_strings.txt \
      /data/local/tmp/moseyvisibilityhook-v10-api101.apk \
      /data/local/tmp/mosey-package-visibility.xml \
      /data/local/tmp/mosey-shim-update.apk \
      /data/local/tmp/mosey-shim.jar >/dev/null 2>&1
rm -rf /data/local/tmp/mosey_hook >/dev/null 2>&1
rm -rf "$STATE_DIR" >/dev/null 2>&1

log -t "$LOG_TAG" "=== Mosey Enabler uninstall complete ==="
