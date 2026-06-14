#!/system/bin/sh

MODPATH="${MODPATH:-/data/adb/modules/mosey-enabler}"
STATE_DIR="/data/adb/mosey-enabler"
WATCHDOG_LOCK="$STATE_DIR/watchdog.lock"
OPERATION_LOCK="$STATE_DIR/operation.lock"
mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR" 2>/dev/null

/system/bin/log -t mosey-enabler "service.sh: starting v1.30"

# ── 清理旧 watchdog 和残留进程 ──
OLD_WD_PID=$(cat "$STATE_DIR/watchdog.pid" 2>/dev/null)
if [ -n "$OLD_WD_PID" ] && kill -0 "$OLD_WD_PID" 2>/dev/null; then
    /system/bin/log -t mosey-enabler "service.sh: stopping old watchdog pid=$OLD_WD_PID"
    kill "$OLD_WD_PID" 2>/dev/null
    sleep 1
fi
rm -f "$STATE_DIR/watchdog.pid" 2>/dev/null
rmdir "$WATCHDOG_LOCK" "$OPERATION_LOCK" 2>/dev/null

# ── 仅在用户通过 WebUI 启用后自动启动 ──
if [ -f "$STATE_DIR/enabled" ]; then
    /system/bin/log -t mosey-enabler "service.sh: enabled flag found, starting native stack"
    sh "$MODPATH/mosey-control.sh" prepare-state
    sh "$MODPATH/mosey-control.sh" native-start
    NATIVE_RC=$?
    sh "$MODPATH/mosey-control.sh" watchdog-start
    WATCHDOG_RC=$?
    /system/bin/log -t mosey-enabler \
        "service.sh: auto-start native_rc=$NATIVE_RC watchdog_rc=$WATCHDOG_RC"
    exit "$NATIVE_RC"
else
    # ── 未启用：确保没有 mosey 进程残留 ──
    /system/bin/log -t mosey-enabler \
        "service.sh: no enabled flag; cleaning any stale mosey processes"
    for PROC in mosey_server mosey_bridge; do
        PID=$(pidof "$PROC" 2>/dev/null)
        [ -n "$PID" ] && kill "$PID" 2>/dev/null && sleep 1
        PID=$(pidof "$PROC" 2>/dev/null)
        [ -n "$PID" ] && kill -9 "$PID" 2>/dev/null
    done
    rm -f /data/local/tmp/mosey-p2b.sock
    svc wifi enable 2>/dev/null || true
    exit 0
fi
