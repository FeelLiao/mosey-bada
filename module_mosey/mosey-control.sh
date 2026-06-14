#!/system/bin/sh

LOG_TAG="mosey-enabler"
MODPATH="${MODPATH:-/data/adb/modules/mosey-enabler}"
STATE_DIR="/data/adb/mosey-enabler"
SERVICE_LOG="$STATE_DIR/service.log"
BRIDGE_LOG="$STATE_DIR/bridge.log"
SERVER_LOG="$STATE_DIR/server.log"
SHIM_PKG="dev.bluehouse.moseybridgeshim"
SHIM_COMPONENT="$SHIM_PKG/.MoseyShimService"
SHIM_PAYLOAD="$MODPATH/payload/MoseyBridgeShim.apk"
BASE_SHIM_VERSION=29
UPDATED_SHIM_VERSION=30
SESSION_FILE="$STATE_DIR/install-session.id"

# ── Mosey 固定监管域 ──
# 只传递给 Mosey shim/bridge，不修改 Android 全局 Wi-Fi 国家码。
MOSEY_COUNTRY_CODE="CN"
MOSEY_COUNTRY_MODE="fixed"
COUNTRY_CODE_FILE="$STATE_DIR/country_code"
WATCHDOG_PID_FILE="$STATE_DIR/watchdog.pid"
WATCHDOG_LOCK="$STATE_DIR/watchdog.lock"
OPERATION_LOCK="$STATE_DIR/operation.lock"

mkdir -p "$STATE_DIR"
chmod 700 "$STATE_DIR" 2>/dev/null
touch "$SERVICE_LOG"
chmod 600 "$SERVICE_LOG" 2>/dev/null

log_msg() {
    /system/bin/log -t "$LOG_TAG" "$*"
    echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$SERVICE_LOG"
}

backoff_for() {
    case "$1" in
        1) echo 10 ;;
        2) echo 30 ;;
        *) echo 60 ;;
    esac
}

wait_for_file() {
    local FILE="$1"
    local TRY
    for TRY in $(seq 1 30); do
        [ -f "$FILE" ] && return 0
        sleep 1
    done
    log_msg "[x] Timed out waiting for $FILE"
    return 1
}

check_mosey_binder() {
    service list 2>/dev/null | grep -q 'com.google.android.moseyservice.IMoseyService/default'
}

process_pid() {
    local NAME="$1" EXPECTED="$2" PID CMDLINE
    for PID in $(pidof "$NAME" 2>/dev/null); do
        [ -r "/proc/$PID/cmdline" ] || continue
        CMDLINE=$(tr '\000' ' ' < "/proc/$PID/cmdline" 2>/dev/null)
        echo "$CMDLINE" | grep -q "$EXPECTED" && {
            echo "$PID"
            return 0
        }
    done
    return 1
}

server_pid() {
    process_pid mosey_server '/odm/bin/mosey_server'
}

bridge_pid() {
    process_pid mosey_bridge '/odm/bin/mosey_bridge'
}

check_unix_backend() {
    local PID
    PID=$(server_pid) || return 1
    [ -S /data/local/tmp/mosey-p2b.sock ] || return 1
    ss -lxnp 2>/dev/null \
        | grep '/data/local/tmp/mosey-p2b.sock' \
        | grep -q "mosey_server.*,pid=$PID,"
}

check_backend() {
    /odm/bin/mosey_bridge --backend-probe >/dev/null 2>&1
}

# ── 非阻塞快速检测（用于 watchdog 循环） ──
check_backend_fast() {
    server_pid >/dev/null 2>&1 || return 1
    [ -S /data/local/tmp/mosey-p2b.sock ] || return 1
    ss -lxnp 2>/dev/null | grep -q '/data/local/tmp/mosey-p2b.sock.*mosey_server'
}

# ── 有超时的后端探测（用于初始启动） ──
# 优先使用 toybox timeout（支持 -k SIGKILL 防残留）
# 强制 UNIX 后端绕过 AServiceManager_getService 的 ~5 秒轮询
check_backend_probe() {
    local RC
    MOSEY_BACKEND=unix \
        /system/bin/toybox timeout -k 1 3 \
        /odm/bin/mosey_bridge --backend-probe \
        >/dev/null 2>&1
    RC=$?
    case "$RC" in
        0) return 0 ;;
        124|137) log_msg "[w] UNIX backend probe timed out rc=$RC" ;;
        *) log_msg "[w] UNIX backend probe failed rc=$RC" ;;
    esac
    return 1
}

# ── 没有 toybox 时的安全 fallback（两阶段 SIGTERM + SIGKILL） ──
check_backend_probe_fallback() {
    local PID KILLER RC
    MOSEY_BACKEND=unix \
        /odm/bin/mosey_bridge --backend-probe \
        >/dev/null 2>&1 &
    PID=$!
    (
        sleep 3
        kill -0 "$PID" 2>/dev/null && kill -TERM "$PID" 2>/dev/null
        sleep 1
        kill -0 "$PID" 2>/dev/null && kill -KILL "$PID" 2>/dev/null
    ) &
    KILLER=$!
    wait "$PID" 2>/dev/null
    RC=$?
    kill "$KILLER" 2>/dev/null
    wait "$KILLER" 2>/dev/null
    return "$RC"
}

check_bridge() {
    bridge_pid >/dev/null 2>&1 \
        && /odm/bin/mosey_bridge --probe >/dev/null 2>&1
}

check_shim() {
    pidof "$SHIM_PKG" >/dev/null 2>&1 \
        && dumpsys activity services "$SHIM_PKG" 2>/dev/null \
            | grep -q 'isForeground=true'
}

check_lowi() {
    [ "$(getprop init.svc.lowi-server)" = "running" ] \
        && pidof lowi-server >/dev/null 2>&1
}

clear_mosey_ipv6_rules() {
    while ip -6 rule del pref 15490 >/dev/null 2>&1; do :; done
    while ip -6 rule del pref 15500 >/dev/null 2>&1; do :; done
}

ensure_mosey_ipv6_rules() {
    local TABLE ADDRESS RULES
    [ -d /sys/class/net/mosey0 ] || return 1
    TABLE=$(ip -6 route show table all 2>/dev/null | awk '
        $1 == "fe80::/64" {
            dev = ""; table = ""
            for (i = 1; i <= NF; i++) {
                if ($i == "dev") dev = $(i + 1)
                if ($i == "table") table = $(i + 1)
            }
            if (dev == "mosey0" && table != "") { print table; exit }
        }')
    ADDRESS=$(ip -6 -o addr show dev mosey0 scope link 2>/dev/null \
        | awk '$3 == "inet6" {print $4; exit}')
    ADDRESS=${ADDRESS%/*}
    [ -n "$TABLE" ] && [ -n "$ADDRESS" ] || return 1

    RULES=$(ip -6 rule show 2>/dev/null)
    echo "$RULES" | awk -v address="$ADDRESS" -v table="$TABLE" '
        $1 == "15490:" && $2 == "from" && $3 == address && $4 == "lookup" && $5 == table { source_ok = 1 }
        $1 == "15500:" && $2 == "from" && $3 == "all" && $4 == "oif" && $5 == "mosey0" && $6 == "lookup" && $7 == table { oif_ok = 1 }
        END { exit !(source_ok && oif_ok) }' \
        && return 0

    clear_mosey_ipv6_rules
    ip -6 rule add pref 15490 from "$ADDRESS/128" lookup "$TABLE" \
        >/dev/null 2>&1 || return 1
    ip -6 rule add pref 15500 oif mosey0 lookup "$TABLE" \
        >/dev/null 2>&1 || {
            clear_mosey_ipv6_rules
            return 1
        }
    ip -6 route flush cache >/dev/null 2>&1 || true
    echo "$TABLE $ADDRESS" > "$STATE_DIR/mosey-ipv6-route"
    chmod 600 "$STATE_DIR/mosey-ipv6-route" 2>/dev/null
    log_msg "[+] Mosey IPv6 policy ready table=$TABLE source=$ADDRESS interface=mosey0"
    return 0
}

valid_country_code() {
    echo "$1" | grep -Eq '^[A-Z][A-Z]$' || return 1
    case "$1" in
        00|NU|ZZ) return 1 ;;
    esac
    return 0
}

# ── 原子写入固定国家码 ──
# 使用临时文件 + 同目录 mv 避免直接重定向导致空文件。
persist_mosey_country_code() {
    local CURRENT TMP
    if ! valid_country_code "$MOSEY_COUNTRY_CODE"; then
        log_msg "[x] Invalid fixed Mosey country code: $MOSEY_COUNTRY_CODE"
        return 1
    fi
    CURRENT=$(cat "$COUNTRY_CODE_FILE" 2>/dev/null)
    [ "$CURRENT" = "$MOSEY_COUNTRY_CODE" ] && return 0
    TMP="$STATE_DIR/.country_code.$$"
    (
        umask 077
        printf '%s\n' "$MOSEY_COUNTRY_CODE" > "$TMP"
    ) || { rm -f "$TMP"; log_msg "[x] Failed to write temporary country-code file"; return 1; }
    chmod 600 "$TMP" 2>/dev/null || { rm -f "$TMP"; log_msg "[x] Failed to set country-code file permissions"; return 1; }
    mv -f "$TMP" "$COUNTRY_CODE_FILE" || { rm -f "$TMP"; log_msg "[x] Failed to install fixed country-code file"; return 1; }
    log_msg "[+] Mosey country persisted: country=$MOSEY_COUNTRY_CODE mode=$MOSEY_COUNTRY_MODE"
    return 0
}

normalize_country_value() {
    echo "$1" | cut -d, -f1 | tr 'a-z' 'A-Z' \
        | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | cut -c1-2
}

read_wifi_field() {
    local FIELD="$1" VALUE
    VALUE=$(dumpsys wifi 2>/dev/null \
        | sed -n "s/^[[:space:]]*${FIELD}:[[:space:]]*//p" \
        | tail -n 1)
    normalize_country_value "$VALUE"
}

read_wifi_country() {
    local VALUE
    VALUE=$(cmd wifi get-country-code 2>/dev/null \
        | sed -n 's/^[[:space:]]*Wifi Country Code[[:space:]]*=[[:space:]]*//p' \
        | head -n 1)
    VALUE=$(normalize_country_value "$VALUE")
    if valid_country_code "$VALUE"; then
        echo "$VALUE|wifi-service"
        return 0
    fi

    local FIELD
    for FIELD in mDriverCountryCode mFrameworkCountryCode mTelephonyCountryCode; do
        VALUE=$(read_wifi_field "$FIELD")
        if valid_country_code "$VALUE"; then
            echo "$VALUE|$FIELD"
            return 0
        fi
    done

    VALUE=$(normalize_country_value "$(getprop gsm.operator.iso-country 2>/dev/null)")
    if valid_country_code "$VALUE"; then
        echo "$VALUE|telephony-property"
        return 0
    fi
    return 1
}

current_country_code() {
    local RESULT
    RESULT=$(read_wifi_country) || return 1
    echo "${RESULT%%|*}"
}

stable_country_code() {
    local LAST="" STABLE=0 RESULT COUNTRY
    for _TRY in 1 2 3 4 5; do
        RESULT=$(read_wifi_country) || {
            LAST=""
            STABLE=0
            sleep 2
            continue
        }
        COUNTRY=${RESULT%%|*}
        if [ "$COUNTRY" = "$LAST" ]; then
            STABLE=$((STABLE + 1))
        else
            LAST="$COUNTRY"
            STABLE=1
        fi
        [ "$STABLE" -ge 3 ] && {
            echo "$COUNTRY"
            return 0
        }
        sleep 2
    done
    return 1
}

prepare_radio_dependencies() {
    local TRY

    # 先验证固定配置，避免等待 LOWI 后才发现配置错误。
    valid_country_code "$MOSEY_COUNTRY_CODE" || {
        log_msg "[x] Invalid configured Mosey country: $MOSEY_COUNTRY_CODE"
        return 1
    }

    if ! check_lowi; then
        log_msg "[*] Starting Qualcomm LOWI dependency"
        start lowi-server >/dev/null 2>&1 || setprop ctl.start lowi-server
    fi

    for TRY in $(seq 1 20); do
        check_lowi && break
        sleep 1
    done

    if ! check_lowi; then
        log_msg "[x] LOWI dependency did not reach running state"
        return 1
    fi

    persist_mosey_country_code || return 1

    log_msg "[+] Radio dependencies ready: LOWI=running country=$MOSEY_COUNTRY_CODE mode=$MOSEY_COUNTRY_MODE"
    return 0
}

start_mosey_server() {
    local BIN="/odm/bin/mosey_server"
    local FFI="/odm/lib64/libmosey_daemon_ffi.so"
    local PRELOAD="/odm/lib64/libmosey_preload.so"
    wait_for_file "$BIN" && wait_for_file "$FFI" && wait_for_file "$PRELOAD" || return 1
    chmod 755 "$BIN" 2>/dev/null

    if server_pid >/dev/null 2>&1; then
        return 0
    fi

    log_msg "[*] Starting mosey_server with preload"
    (
        export LD_PRELOAD="$PRELOAD"
        exec "$BIN"
    ) >> "$SERVER_LOG" 2>&1 &
    local PID=$!
    sleep 2
    if ! kill -0 "$PID" 2>/dev/null; then
        log_msg "[x] mosey_server exited during startup"
        return 1
    fi
    log_msg "[+] mosey_server started pid=$PID"
    return 0
}

wait_for_backend() {
    local TRY

    # ── 第一阶段：等待 UNIX socket 就绪（最多 20 秒） ──
    for TRY in $(seq 1 20); do
        if ! server_pid >/dev/null 2>&1; then
            log_msg "[x] mosey_server exited while waiting for backend"
            return 1
        fi
        if check_backend_fast; then
            log_msg "[+] Mosey UNIX socket is listening"
            break
        fi
        sleep 1
    done

    if ! check_backend_fast; then
        log_msg "[x] Timed out waiting for Mosey UNIX socket"
        return 1
    fi

    # ── 第二阶段：最多 3 次真实事务探测 ──
    for TRY in 1 2 3; do
        if check_backend_probe; then
            if check_unix_backend; then
                log_msg "[+] Mosey backend transaction probe passed; UNIX fallback available"
            elif check_mosey_binder; then
                log_msg "[+] Mosey backend transaction probe passed: direct Binder"
            else
                log_msg "[+] Mosey backend ready: preload UNIX proxy"
            fi
            return 0
        fi
        log_msg "[w] Backend transaction probe failed attempt=$TRY/3"
        sleep 1
    done

    log_msg "[x] Mosey UNIX backend socket exists but transaction probe failed"
    return 1
}

start_mosey_bridge() {
    local BIN="/odm/bin/mosey_bridge"
    wait_for_file "$BIN" || return 1
    chmod 755 "$BIN" 2>/dev/null
    if check_bridge; then
        return 0
    fi
    local OLD_PID
    OLD_PID=$(bridge_pid)
    if [ -n "$OLD_PID" ]; then
        kill "$OLD_PID" >/dev/null 2>&1
        sleep 1
    fi

    log_msg "[*] Starting mosey_bridge"
    nohup "$BIN" >> "$BRIDGE_LOG" 2>&1 &
    local PID=$!
    local TRY
    for TRY in $(seq 1 10); do
        if kill -0 "$PID" 2>/dev/null && check_bridge; then
            log_msg "[+] mosey_bridge end-to-end probe passed pid=$PID port=19539"
            return 0
        fi
        sleep 1
    done
    log_msg "[x] mosey_bridge failed startup health check"
    return 1
}

start_native_stack() {
    start_mosey_server && wait_for_backend && start_mosey_bridge
}

# ── 停止原生栈 ──
stop_native_stack() {
    local PID
    PID=$(bridge_pid)
    [ -n "$PID" ] && kill "$PID" >/dev/null 2>&1
    PID=$(server_pid)
    [ -n "$PID" ] && kill "$PID" >/dev/null 2>&1
    rm -f /data/local/tmp/mosey-p2b.sock
    sleep 1
    PID=$(bridge_pid)
    [ -n "$PID" ] && kill -9 "$PID" >/dev/null 2>&1
    PID=$(server_pid)
    [ -n "$PID" ] && kill -9 "$PID" >/dev/null 2>&1
    log_msg "[-] Native stack stopped"
}

package_user_installed() {
    dumpsys package "$SHIM_PKG" 2>/dev/null \
        | grep -q 'User 0:.*installed=true'
}

restore_system_package() {
    local TRY OUTPUT
    for TRY in $(seq 1 60); do
        OUTPUT=$(pm install-existing --user 0 --wait "$SHIM_PKG" 2>&1)
        local RC=$?
        if [ "$RC" -eq 0 ] && package_user_installed; then
            log_msg "[+] System shim enabled for user 0: $OUTPUT"
            return 0
        fi
        log_msg "[*] Waiting for PMS user state ($TRY/60 rc=$RC): $OUTPUT"
        sleep 2
    done
    log_msg "[x] PMS never enabled $SHIM_PKG for user 0"
    return 1
}

abandon_install_session() {
    local SESSION_ID="$1"
    [ -n "$SESSION_ID" ] && cmd package install-abandon "$SESSION_ID" >/dev/null 2>&1
    rm -f "$SESSION_FILE"
}

install_updated_shim() {
    [ -f "$SHIM_PAYLOAD" ] || {
        log_msg "[x] Missing shim payload: $SHIM_PAYLOAD"
        return 1
    }

    local SIZE CREATE_OUTPUT SESSION_ID WRITE_OUTPUT COMMIT_OUTPUT TMP_APK
    SIZE=$(stat -c %s "$SHIM_PAYLOAD" 2>/dev/null)
    CREATE_OUTPUT=$(cmd package install-create -r -g --user 0 -S "$SIZE" 2>&1)
    local CREATE_RC=$?
    log_msg "[*] install-create rc=$CREATE_RC: $CREATE_OUTPUT"
    SESSION_ID=$(echo "$CREATE_OUTPUT" | sed -n 's/.*\[\([0-9][0-9]*\)\].*/\1/p' | tail -n 1)
    if [ "$CREATE_RC" -ne 0 ] || [ -z "$SESSION_ID" ]; then
        return 1
    fi
    echo "$SESSION_ID" > "$SESSION_FILE"

    WRITE_OUTPUT=$(cmd package install-write -S "$SIZE" "$SESSION_ID" base.apk - \
        < "$SHIM_PAYLOAD" 2>&1)
    local WRITE_RC=$?
    log_msg "[*] install-write(stdin) session=$SESSION_ID rc=$WRITE_RC: $WRITE_OUTPUT"

    if [ "$WRITE_RC" -ne 0 ] || ! echo "$WRITE_OUTPUT" | grep -q 'Success'; then
        abandon_install_session "$SESSION_ID"
        CREATE_OUTPUT=$(cmd package install-create -r -g --user 0 -S "$SIZE" 2>&1)
        CREATE_RC=$?
        log_msg "[*] fallback install-create rc=$CREATE_RC: $CREATE_OUTPUT"
        SESSION_ID=$(echo "$CREATE_OUTPUT" | sed -n 's/.*\[\([0-9][0-9]*\)\].*/\1/p' | tail -n 1)
        if [ "$CREATE_RC" -ne 0 ] || [ -z "$SESSION_ID" ]; then
            return 1
        fi
        echo "$SESSION_ID" > "$SESSION_FILE"
        TMP_APK="/data/local/tmp/mosey-shim-update.apk"
        cp -f "$SHIM_PAYLOAD" "$TMP_APK" || {
            abandon_install_session "$SESSION_ID"
            return 1
        }
        chmod 644 "$TMP_APK"
        chown shell:shell "$TMP_APK" 2>/dev/null
        chcon u:object_r:shell_data_file:s0 "$TMP_APK" 2>/dev/null
        WRITE_OUTPUT=$(cmd package install-write -S "$SIZE" "$SESSION_ID" base.apk "$TMP_APK" 2>&1)
        WRITE_RC=$?
        log_msg "[*] install-write(path) session=$SESSION_ID rc=$WRITE_RC: $WRITE_OUTPUT"
        rm -f "$TMP_APK"
    fi

    if [ "$WRITE_RC" -ne 0 ] || ! echo "$WRITE_OUTPUT" | grep -q 'Success'; then
        abandon_install_session "$SESSION_ID"
        return 1
    fi

    COMMIT_OUTPUT=$(cmd package install-commit "$SESSION_ID" 2>&1)
    local COMMIT_RC=$?
    log_msg "[*] install-commit session=$SESSION_ID rc=$COMMIT_RC: $COMMIT_OUTPUT"
    rm -f "$SESSION_FILE"
    [ "$COMMIT_RC" -eq 0 ] && echo "$COMMIT_OUTPUT" | grep -q 'Success'
}

package_version() {
    dumpsys package "$SHIM_PKG" 2>/dev/null \
        | sed -n 's/.*versionCode=\([0-9]*\).*/\1/p' | head -n 1
}

package_path() {
    cmd package path "$SHIM_PKG" 2>/dev/null | head -n 1
}

verify_shim_manifest() {
    local DUMP
    DUMP=$(dumpsys package "$SHIM_PKG" 2>/dev/null)
    echo "$DUMP" | grep -q 'android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE'
}

ensure_shim_package() {
    # ── 快速路径：已安装最新版本 ──
    local VERSION PATH_VALUE
    VERSION=$(package_version)
    PATH_VALUE=$(package_path)
    if [ "$VERSION" = "$UPDATED_SHIM_VERSION" ] \
            && echo "$PATH_VALUE" | grep -q '^package:/data/app/'; then
        return 0
    fi

    restore_system_package || return 1

    VERSION=$(package_version)
    PATH_VALUE=$(package_path)
    if [ "$VERSION" != "$UPDATED_SHIM_VERSION" ] \
            || ! echo "$PATH_VALUE" | grep -q '^package:/data/app/'; then
        log_msg "[*] Installing updated shim v$UPDATED_SHIM_VERSION over base v$BASE_SHIM_VERSION"
        if ! install_updated_shim; then
            log_msg "[!] Updated shim install failed; continuing with functional system base"
            restore_system_package || return 1
        fi
    fi

    local TRY
    for TRY in $(seq 1 10); do
        VERSION=$(package_version)
        PATH_VALUE=$(package_path)
        if package_user_installed && verify_shim_manifest; then
            if [ "$VERSION" = "$UPDATED_SHIM_VERSION" ] \
                    && echo "$PATH_VALUE" | grep -q '^package:/data/app/'; then
                log_msg "[+] Updated shim verified version=$VERSION path=$PATH_VALUE"
            else
                log_msg "[!] Shim running in base fallback version=$VERSION path=$PATH_VALUE"
            fi
            return 0
        fi
        sleep 1
    done
    log_msg "[x] Shim package manifest or user state is invalid version=$VERSION path=$PATH_VALUE"
    return 1
}

grant_shim_permissions() {
    local PERM OP
    for PERM in \
        android.permission.ACCESS_FINE_LOCATION \
        android.permission.ACCESS_COARSE_LOCATION \
        android.permission.ACCESS_BACKGROUND_LOCATION \
        android.permission.NEARBY_WIFI_DEVICES \
        android.permission.BLUETOOTH_SCAN \
        android.permission.BLUETOOTH_ADVERTISE \
        android.permission.BLUETOOTH_CONNECT; do
        pm grant "$SHIM_PKG" "$PERM" >/dev/null 2>&1 || true
    done
    for OP in \
        ACCESS_FINE_LOCATION ACCESS_COARSE_LOCATION ACCESS_BACKGROUND_LOCATION \
        NEARBY_WIFI_DEVICES BLUETOOTH_SCAN BLUETOOTH_ADVERTISE BLUETOOTH_CONNECT \
        START_FOREGROUND RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND; do
        cmd appops set "$SHIM_PKG" "$OP" allow >/dev/null 2>&1 \
            || appops set "$SHIM_PKG" "$OP" allow >/dev/null 2>&1 || true
    done
    cmd deviceidle whitelist +"$SHIM_PKG" >/dev/null 2>&1 \
        || dumpsys deviceidle whitelist +"$SHIM_PKG" >/dev/null 2>&1 || true
    cmd package set-stop-reason "$SHIM_PKG" 0 >/dev/null 2>&1 || true
}

grant_bada_runtime() {
    local PKG OP
    for PKG in dev.bluehouse.bada dev.bluehouse.bada.debug; do
        pm path "$PKG" >/dev/null 2>&1 || continue
        for OP in START_FOREGROUND RUN_IN_BACKGROUND RUN_ANY_IN_BACKGROUND; do
            cmd appops set "$PKG" "$OP" allow >/dev/null 2>&1 \
                || appops set "$PKG" "$OP" allow >/dev/null 2>&1 || true
        done
        cmd deviceidle whitelist +"$PKG" >/dev/null 2>&1 \
            || dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
    done
}

start_shim() {
    prepare_radio_dependencies || return 1

    local COUNTRY="$MOSEY_COUNTRY_CODE"
    local TRY ROUTE_TRY

    valid_country_code "$COUNTRY" || {
        log_msg "[x] Refusing shim startup: invalid configured country=$COUNTRY"
        return 1
    }

    ensure_shim_package || return 1
    grant_shim_permissions
    grant_bada_runtime

    log_msg "[*] Starting Mosey shim country=$COUNTRY mode=$MOSEY_COUNTRY_MODE"

    am start-foreground-service -n "$SHIM_COMPONENT" \
        --es country_code "$COUNTRY" >/dev/null 2>&1 \
        || am startservice -n "$SHIM_COMPONENT" \
            --es country_code "$COUNTRY" >/dev/null 2>&1 \
        || cmd activity start-service "$SHIM_COMPONENT" \
            --es country_code "$COUNTRY" >/dev/null 2>&1

    for TRY in $(seq 1 15); do
        if check_shim \
                && dumpsys activity services "$SHIM_PKG" 2>/dev/null \
                    | grep -Eq 'types=0x0*10'; then

            log_msg "[+] Mosey shim foreground service active type=connectedDevice country=$COUNTRY mode=$MOSEY_COUNTRY_MODE"

            for ROUTE_TRY in $(seq 1 15); do
                ensure_mosey_ipv6_rules && return 0
                sleep 1
            done

            log_msg "[!] Shim active but mosey0 IPv6 policy is not ready; watchdog will retry"
            return 0
        fi

        sleep 1
    done

    log_msg "[x] Mosey shim failed to enter foreground state country=$COUNTRY"
    return 1
}

with_operation_lock() {
    local COMMAND="$1"
    shift
    local TRY RC
    for TRY in $(seq 1 60); do
        if mkdir "$OPERATION_LOCK" 2>/dev/null; then
            (
                trap 'rmdir "$OPERATION_LOCK" 2>/dev/null' EXIT HUP INT TERM
                "$COMMAND" "$@"
            )
            RC=$?
            rmdir "$OPERATION_LOCK" 2>/dev/null
            return "$RC"
        fi
        sleep 1
    done
    log_msg "[x] Timed out waiting for operation lock"
    return 1
}

# ── 非阻塞操作锁（用于 watchdog，获取不到返回 75）──
try_with_operation_lock() {
    local COMMAND="$1"
    shift
    local RC
    if ! mkdir "$OPERATION_LOCK" 2>/dev/null; then
        return 75
    fi
    (
        trap 'rmdir "$OPERATION_LOCK" 2>/dev/null' EXIT HUP INT TERM
        "$COMMAND" "$@"
    )
    RC=$?
    rmdir "$OPERATION_LOCK" 2>/dev/null
    return "$RC"
}

run_watchdog() {
    if ! mkdir "$WATCHDOG_LOCK" 2>/dev/null; then
        log_msg "[*] Watchdog already owns lock"
        return 0
    fi
    echo $$ > "$WATCHDOG_PID_FILE"
    trap 'rm -f "$WATCHDOG_PID_FILE"; rmdir "$WATCHDOG_LOCK" 2>/dev/null' EXIT INT TERM
    log_msg "[+] Unified watchdog started pid=$$"

    local BRIDGE_FAILURES=0 SHIM_FAILURES=0 DELAY PID RC
    while true; do
        # ── 未启用时，通过操作锁安全清理 ──
        if [ ! -f "$STATE_DIR/enabled" ]; then
            try_with_operation_lock watchdog_cleanup_disabled
            RC=$?
            case "$RC" in
                0) ;;
                75) log_msg "[*] Watchdog cleanup skipped: operation in progress" ;;
                *) log_msg "[w] Watchdog disabled-state cleanup failed rc=$RC" ;;
            esac
            sleep 30
            continue
        fi

        if ! server_pid >/dev/null 2>&1 || ! check_backend_fast; then
            BRIDGE_FAILURES=$((BRIDGE_FAILURES + 1))
            DELAY=$(backoff_for "$BRIDGE_FAILURES")
            log_msg "[!] Backend probe failure $BRIDGE_FAILURES; retrying after ${DELAY}s"
            sleep "$DELAY"
            PID=$(bridge_pid); [ -n "$PID" ] && kill "$PID" >/dev/null 2>&1
            PID=$(server_pid); [ -n "$PID" ] && kill "$PID" >/dev/null 2>&1
            sleep 1
            rm -f /data/local/tmp/mosey-p2b.sock
            if start_native_stack; then
                BRIDGE_FAILURES=0
                if check_shim; then
                    log_msg "[*] Restarting shim after native control recovery"
                    am force-stop "$SHIM_PKG" >/dev/null 2>&1
                    try_with_operation_lock start_shim || true
                fi
            fi
        elif ! check_bridge; then
            BRIDGE_FAILURES=$((BRIDGE_FAILURES + 1))
            DELAY=$(backoff_for "$BRIDGE_FAILURES")
            log_msg "[!] Bridge probe failure $BRIDGE_FAILURES; retrying bridge after ${DELAY}s"
            sleep "$DELAY"
            PID=$(bridge_pid)
            [ -n "$PID" ] && kill "$PID" >/dev/null 2>&1
            if start_mosey_bridge && check_bridge; then
                BRIDGE_FAILURES=0
                if check_shim; then
                    log_msg "[*] Restarting shim after native control recovery"
                    am force-stop "$SHIM_PKG" >/dev/null 2>&1
                    try_with_operation_lock start_shim || true
                fi
            fi
        else
            BRIDGE_FAILURES=0
            ensure_mosey_ipv6_rules || true
            if ! persist_mosey_country_code; then
                log_msg "[w] Watchdog failed to maintain fixed country=$MOSEY_COUNTRY_CODE"
            fi
        fi

        if [ "$(getprop sys.boot_completed)" = "1" ] && [ -f "$STATE_DIR/enabled" ]; then
            if check_shim; then
                SHIM_FAILURES=0
            else
                SHIM_FAILURES=$((SHIM_FAILURES + 1))
                log_msg "[!] Watchdog shim recovery attempt=$SHIM_FAILURES"
                try_with_operation_lock start_shim
                RC=$?
                case "$RC" in
                    0) SHIM_FAILURES=0 ;;
                    75) log_msg "[*] Shim recovery skipped: operation in progress" ;;
                    *) log_msg "[w] Shim recovery failed attempt=$SHIM_FAILURES rc=$RC" ;;
                esac
            fi
        fi

        sleep 30
    done
}

start_watchdog() {
    local OLD_PID CMDLINE
    OLD_PID=$(cat "$WATCHDOG_PID_FILE" 2>/dev/null)
    if [ -n "$OLD_PID" ] && kill -0 "$OLD_PID" 2>/dev/null; then
        CMDLINE=$(tr '\000' ' ' < "/proc/$OLD_PID/cmdline" 2>/dev/null)
        echo "$CMDLINE" | grep -q 'mosey-control.sh watchdog' && return 0
    fi
    rm -f "$WATCHDOG_PID_FILE"
    rmdir "$WATCHDOG_LOCK" 2>/dev/null
    nohup sh "$MODPATH/mosey-control.sh" watchdog >/dev/null 2>&1 &
    sleep 1
    log_msg "[*] Watchdog launch requested pid=$!"
}

prepare_state() {
    local PID CMDLINE FILE
    for FILE in "$WATCHDOG_PID_FILE" "$STATE_DIR/server.pid" "$STATE_DIR/bridge.pid"; do
        PID=$(cat "$FILE" 2>/dev/null)
        if [ -n "$PID" ] && [ -r "/proc/$PID/cmdline" ]; then
            CMDLINE=$(tr '\000' ' ' < "/proc/$PID/cmdline" 2>/dev/null)
            echo "$CMDLINE" | grep -q 'mosey' && continue
        fi
        rm -f "$FILE"
    done
    [ -d "$WATCHDOG_LOCK" ] && [ ! -f "$WATCHDOG_PID_FILE" ] && rmdir "$WATCHDOG_LOCK" 2>/dev/null
    rmdir "$OPERATION_LOCK" 2>/dev/null
    if ! server_pid >/dev/null 2>&1; then
        rm -f /data/local/tmp/mosey-p2b.sock
    fi
    for FILE in "$SERVICE_LOG" "$SERVER_LOG" "$BRIDGE_LOG"; do
        [ -f "$FILE" ] || continue
        [ -s "$FILE" ] || continue
        mv -f "$FILE" "$FILE.boot" 2>/dev/null
        : > "$FILE"
    done
    log_msg "[+] Cleared stale pre-v1.21 runtime state"
}

# ═══════════════════════════════════════════
# WebUI 命令
# ═══════════════════════════════════════════

webui_status() {
    local ENABLED=false NATIVE=false BRIDGE=false SHIM=false WIFI=false MOSEY0=false WONDER=false

    [ -f "$STATE_DIR/enabled" ] && ENABLED=true
    server_pid >/dev/null 2>&1 && NATIVE=true
    check_bridge && BRIDGE=true
    check_shim && SHIM=true
    dumpsys wifi 2>/dev/null | grep -q 'Wi-Fi is connected' && WIFI=true
    [ -d /sys/class/net/mosey0 ] && MOSEY0=true
    lsmod 2>/dev/null | grep -q '^wonder ' && WONDER=true

    printf '{"enabled":%s,"native_running":%s,"bridge_running":%s,"shim_running":%s,"wifi_connected":%s,"mosey0_exists":%s,"wonder_loaded":%s,"country_code":"%s","country_mode":"%s"}\n' \
        "$ENABLED" "$NATIVE" "$BRIDGE" "$SHIM" "$WIFI" "$MOSEY0" "$WONDER" \
        "$MOSEY_COUNTRY_CODE" "$MOSEY_COUNTRY_MODE"
}

webui_enable_locked() {
    local WIFI_WAS_ON=false

    # enabled 已存在时检查健康状态，非健康则修复。
    if [ -f "$STATE_DIR/enabled" ]; then
        if check_bridge && check_shim && [ -d /sys/class/net/mosey0 ]; then
            log_msg "[*] Mosey already enabled and healthy"
            echo "Mosey already enabled"
            return 0
        fi
        log_msg "[!] Mosey enabled state is degraded; repairing stack"
    fi

    # 仅在 Wi-Fi 当前开启时记录恢复状态。
    if dumpsys wifi 2>/dev/null | grep -q 'Wi-Fi is enabled'; then
        WIFI_WAS_ON=true
        if [ ! -f "$STATE_DIR/wifi_was_on" ]; then
            printf '%s\n' "true" > "$STATE_DIR/wifi_was_on"
            chmod 600 "$STATE_DIR/wifi_was_on" 2>/dev/null
        fi
    fi

    if [ "$WIFI_WAS_ON" = true ]; then
        log_msg "[*] Disabling Wi-Fi to free radio for mosey wondertap"
        svc wifi disable 2>/dev/null \
            || cmd wifi set-wifi-enabled disabled 2>/dev/null \
            || true
        sleep 3
    fi

    if ! start_native_stack; then
        log_msg "[x] Native stack failed to start"
        if [ ! -f "$STATE_DIR/enabled" ]; then
            stop_native_stack
            am force-stop "$SHIM_PKG" >/dev/null 2>&1 || true
            [ "$WIFI_WAS_ON" = true ] && svc wifi enable 2>/dev/null || true
            rm -f "$STATE_DIR/wifi_was_on"
        fi
        return 1
    fi

    # 直接调用 start_shim（已持有 operation.lock，不能再次 with_operation_lock）
    if ! start_shim; then
        log_msg "[x] Shim failed to start; rolling back"
        if [ ! -f "$STATE_DIR/enabled" ]; then
            am force-stop "$SHIM_PKG" >/dev/null 2>&1 || true
            stop_native_stack
            clear_mosey_ipv6_rules
            [ "$WIFI_WAS_ON" = true ] && svc wifi enable 2>/dev/null || true
            rm -f "$STATE_DIR/wifi_was_on"
        fi
        return 1
    fi

    # 最终健康检查通过后提交 enabled 状态。
    if ! check_bridge || ! check_shim || [ ! -d /sys/class/net/mosey0 ]; then
        log_msg "[x] Mosey startup completed but final health check failed"
        return 1
    fi

    touch "$STATE_DIR/enabled" || {
        log_msg "[x] Unable to commit enabled state"
        return 1
    }
    chmod 600 "$STATE_DIR/enabled" 2>/dev/null

    log_msg "[+] Mosey enabled and healthy country=$MOSEY_COUNTRY_CODE"
    echo "Mosey enabled successfully"
    return 0
}

webui_enable() {
    with_operation_lock webui_enable_locked
}

webui_disable_locked() {
    # 首先提交“期望关闭”状态。
    rm -f "$STATE_DIR/enabled"

    am force-stop "$SHIM_PKG" >/dev/null 2>&1 || true
    sleep 1

    stop_native_stack
    clear_mosey_ipv6_rules

    log_msg "[*] Re-enabling Wi-Fi"
    svc wifi enable 2>/dev/null \
        || cmd wifi set-wifi-enabled enabled 2>/dev/null \
        || true

    cmd wifi force-country-code disabled >/dev/null 2>&1 || true
    sleep 2

    rm -f "$STATE_DIR/wifi_was_on" "$STATE_DIR/wifi_saved" 2>/dev/null

    log_msg "[-] Mosey disabled; Wi-Fi restored"
    echo "Mosey disabled, Wi-Fi restored"
    return 0
}

webui_disable() {
    with_operation_lock webui_disable_locked
}

# ── watchdog 锁内清理（仅在 enabled 不存在时执行）──
watchdog_cleanup_disabled() {
    # 获得锁后必须重新检查，避免状态在等待期间发生变化。
    [ -f "$STATE_DIR/enabled" ] && return 0

    if server_pid >/dev/null 2>&1 \
            || bridge_pid >/dev/null 2>&1 \
            || check_shim; then
        log_msg "[!] Mosey disabled; cleaning residual processes"
        am force-stop "$SHIM_PKG" >/dev/null 2>&1 || true
        stop_native_stack
        clear_mosey_ipv6_rules
        svc wifi enable 2>/dev/null || true
    fi

    return 0
}

webui_log() {
    local FILES="$SERVICE_LOG $BRIDGE_LOG"
    for F in $FILES; do
        if [ -f "$F" ] && [ -s "$F" ]; then
            tail -30 "$F"
            echo "---"
        fi
    done
}

case "$1" in
    radio-prepare) prepare_radio_dependencies ;;
    native-start) start_native_stack ;;
    native-stop) stop_native_stack ;;
    shim-start) with_operation_lock start_shim ;;
    watchdog) run_watchdog ;;
    watchdog-start) start_watchdog ;;
    prepare-state) prepare_state ;;
    all-start) start_native_stack && with_operation_lock start_shim && start_watchdog ;;
    webui) shift; case "$1" in
        status) webui_status ;;
        enable) webui_enable ;;
        disable) webui_disable ;;
        log) webui_log ;;
        *) echo '{"error":"unknown webui subcommand"}'; exit 2 ;;
    esac ;;
    *) echo "usage: $0 {prepare-state|radio-prepare|native-start|native-stop|shim-start|watchdog|watchdog-start|all-start|webui {status|enable|disable|log}}"; exit 2 ;;
esac
