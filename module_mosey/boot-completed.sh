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
sh "$MODPATH/mosey-control.sh" native-start
NATIVE_RC=$?
sh "$MODPATH/mosey-control.sh" watchdog-start

/system/bin/log -t mosey-enabler \
    "boot-completed.sh: complete native_rc=$NATIVE_RC"
exit "$NATIVE_RC"
