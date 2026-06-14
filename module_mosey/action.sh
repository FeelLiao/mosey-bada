#!/system/bin/sh

MODPATH="${MODPATH:-/data/adb/modules/mosey-enabler}"
exec sh "$MODPATH/mosey-control.sh" all-start
