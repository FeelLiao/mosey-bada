#!/system/bin/sh

MODPATH="${MODPATH:-/data/adb/modules/mosey-enabler}"
STATE_DIR="/data/adb/mosey-enabler"
/system/bin/log -t mosey-enabler "boot-completed.sh: start"

# ── 仅在用户通过 WebUI 启用后运行 ──
if [ ! -f "$STATE_DIR/enabled" ]; then
    /system/bin/log -t mosey-enabler "boot-completed.sh: not enabled; skipping"
    exit 0
fi

sh "$MODPATH/mosey-control.sh" prepare-state

# 先启动 watchdog，确保后续任何失败都能恢复。
sh "$MODPATH/mosey-control.sh" native-start
NATIVE_RC=$?
sh "$MODPATH/mosey-control.sh" watchdog-start
WATCHDOG_RC=$?

SHIM_RC=1
if [ "$NATIVE_RC" -eq 0 ]; then
    # shim-start 内部使用 operation.lock。
    sh "$MODPATH/mosey-control.sh" shim-start
    SHIM_RC=$?
fi

/system/bin/log -t mosey-enabler \
    "boot-completed.sh: complete native_rc=$NATIVE_RC shim_rc=$SHIM_RC watchdog_rc=$WATCHDOG_RC"

# shim 暂时失败时由 watchdog 恢复，不应让整个 boot 脚本失败。
exit "$NATIVE_RC"
