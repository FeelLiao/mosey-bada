#!/system/bin/sh
# customize.sh - Mosey Enabler 安装脚本 (HybridMount Magic Mode)
# KernelSU module install-time setup

# HybridMount 环境检测与配置
# Magic Mode (Bind Mount): HybridMount 会将模块根目录下的 odm/、system_ext/ 等
# 文件夹中的每个文件逐个 bind-mount 到对应的系统分区路径。
# 与 OverlayFS 不同，Magic Mount 直接替换单个文件而非叠加层。
#
# 模块根目录存在空的 magic 标记文件 → HybridMount Nano 自动使用 Magic Mount 后端
# Full/Lite 版本通过 /data/adb/hybrid-mount/config.toml 配置:
#
#   [rules.mosey-enabler]
#   default_mode = "magic"
#
# 这会指导 HybridMount 对此模块使用 Magic Mount (bind mount) 后端。

# ========== KernelSU 环境 ==========
if [ "$KSU" = "true" ]; then
    ui_print "[✓] KernelSU detected: v${KSU_KERNEL_VER} (ver ${KSU_VER})"
    MAGISK=false
elif [ "$BOOTMODE" = "true" ]; then
    ui_print "[✓] Magisk detected"
    MAGISK=true
else
    ui_print "[✗] Unsupported environment"
    exit 1
fi

ui_print ""
ui_print "╔══════════════════════════════════════════╗"
ui_print "║        Mosey Enabler - AirDrop Port      ║"
ui_print "║     GLO → CN  |  HybridMount Magic Mode  ║"
ui_print "╚══════════════════════════════════════════╝"
ui_print ""

# ========== 架构检测 ==========
ARCH=$(getprop ro.product.cpu.abi)
ui_print "[*] Device architecture: $ARCH"

if [ "$ARCH" != "arm64-v8a" ]; then
    ui_print "[!] Warning: Only arm64 is supported. Found: $ARCH"
fi

# ========== 模块版本信息 ==========
MOD_VER=$(grep_prop version "$MODPATH/module.prop")
ui_print "[*] Module version: $MOD_VER"

# ========== Hybrid Mount Full/Lite 配置修复 ==========
# KernelSU metamodule 的实际挂载发生在 metamount.sh 阶段。Full/Lite 版
# Hybrid Mount 不只看 magic marker，还会优先使用 config.toml 中的规则。
# 之前设备上存在 [rules.mosey-extended]，但真实模块 ID 是 mosey-enabler，
# 导致本模块仍按默认 overlay 模式挂载。这里在安装时写入正确规则。
HYBRID_CONFIG="/data/adb/hybrid-mount/config.toml"
if [ -f "$HYBRID_CONFIG" ]; then
    ui_print "[*] Ensuring Hybrid Mount rule for mosey-enabler..."
    if grep -q '^disable_umount[[:space:]]*=' "$HYBRID_CONFIG"; then
        sed -i 's/^disable_umount[[:space:]]*=.*/disable_umount = true/' "$HYBRID_CONFIG"
    else
        {
            echo ""
            echo "disable_umount = true"
        } >> "$HYBRID_CONFIG"
    fi
    ui_print "  [✓] Hybrid Mount disable_umount=true"

    if grep -q '^\[rules\.mosey-enabler\]' "$HYBRID_CONFIG"; then
        ui_print "  [✓] Hybrid Mount rule already exists"
    else
        {
            echo ""
            echo "[rules.mosey-enabler]"
            echo 'default_mode = "magic"'
            echo ""
            echo "[rules.mosey-enabler.paths]"
        } >> "$HYBRID_CONFIG"
        ui_print "  [✓] Added [rules.mosey-enabler] default_mode=magic"
    fi
else
    ui_print "[!] Hybrid Mount config not found at $HYBRID_CONFIG"
    ui_print "[!] Install/enable Hybrid Mount Full/Lite or use Nano magic marker"
fi

# ========== KernelSU kernel umount 配置 ==========
# KernelSU App Profile 文档说明，普通应用默认可能会被 "Umount modules"，
# 这会导致系统能扫描到 MoseyShim.apk，但 app 进程 namespace 中看不到
# APK 本体并崩溃。先全局关闭 kernel_umount 以验证功能链路。
KSUD="/data/adb/ksud"
if [ -x "$KSUD" ]; then
    ui_print "[*] Disabling KernelSU kernel_umount for module-backed system app..."
    "$KSUD" feature set kernel_umount 0 >/dev/null 2>&1 && \
        "$KSUD" feature save >/dev/null 2>&1 && \
        ui_print "  [✓] KernelSU kernel_umount disabled" || \
        ui_print "  [!] Failed to update KernelSU kernel_umount"
else
    ui_print "[!] ksud CLI not found; set KernelSU App Profile manually if APK is hidden from app process"
fi

# ========== 设置文件权限 ==========
ui_print "[*] Setting binary permissions..."
set_perm "$MODPATH/odm/bin/mosey_server" 0 0 0755
set_perm "$MODPATH/odm/bin/mosey_bridge" 0 0 0755
set_perm "$MODPATH/odm/lib64/libmosey_daemon_ffi.so" 0 0 0644
set_perm "$MODPATH/odm/etc/init/mosey.rc" 0 0 0644
set_perm "$MODPATH/odm/etc/init/lowi-server.rc" 0 0 0644
set_perm "$MODPATH/odm/etc/permissions/android.hardware.wifi.aware.xml" 0 0 0644
set_perm "$MODPATH/odm/vendor/etc/wifi/WCNSS_qcom_cfg.ini" 0 0 0644
set_perm_recursive "$MODPATH/odm/etc/wifi" 0 0 0755 0644
set_perm "$MODPATH/odm/etc/vintf/manifest/manifest_mosey.xml" 0 0 0644

set_perm "$MODPATH/system_ext/priv-app/MoseyApp/MoseyApp.apk" 0 0 0644
set_perm "$MODPATH/system_ext/etc/permissions/privapp-permissions-com.google.android.mosey.xml" 0 0 0644
set_perm "$MODPATH/system_ext/etc/default-permissions/default-permissions-com.google.android.mosey.xml" 0 0 0644
set_perm "$MODPATH/system_ext/priv-app/MoseyBridgeShim/MoseyBridgeShim.apk" 0 0 0644
set_perm "$MODPATH/payload/MoseyBridgeShim.apk" 0 0 0644
set_perm "$MODPATH/system_ext/etc/permissions/privapp-permissions-dev.bluehouse.moseybridgeshim.xml" 0 0 0644
set_perm "$MODPATH/system_ext/etc/default-permissions/default-permissions-dev.bluehouse.moseybridgeshim.xml" 0 0 0644

set_perm "$MODPATH/service.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/mosey-control.sh" 0 0 0755
set_perm "$MODPATH/magic" 0 0 0644
set_perm "$MODPATH/post-fs-data.sh" 0 0 0755
set_perm "$MODPATH/post-mount.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/sepolicy.rule" 0 0 0644

# ========== 文件完整性校验 ==========
ui_print "[*] Verifying critical files..."
FILES_MISSING=0

check_file() {
    if [ -f "$MODPATH/$1" ]; then
        ui_print "  [✓] $1"
    else
        ui_print "  [✗] $1 - File not found!"
        FILES_MISSING=$((FILES_MISSING + 1))
    fi
}

check_file "odm/bin/mosey_server"
check_file "odm/bin/mosey_bridge"
check_file "odm/lib64/libmosey_daemon_ffi.so"
check_file "odm/etc/init/mosey.rc"
check_file "odm/etc/init/lowi-server.rc"
check_file "odm/etc/permissions/android.hardware.wifi.aware.xml"
check_file "odm/vendor/etc/wifi/WCNSS_qcom_cfg.ini"
check_file "odm/etc/wifi/peach/bdwlan.elf"
check_file "odm/etc/wifi/peach/bdwlan.b0a"
check_file "odm/etc/wifi/peach/bdwlan.b0e"
check_file "odm/etc/wifi/peach/bdwlan.b0i"
check_file "odm/etc/vintf/manifest/manifest_mosey.xml"
check_file "system_ext/priv-app/MoseyApp/MoseyApp.apk"
check_file "system_ext/etc/permissions/privapp-permissions-com.google.android.mosey.xml"
check_file "system_ext/etc/default-permissions/default-permissions-com.google.android.mosey.xml"
check_file "system_ext/priv-app/MoseyBridgeShim/MoseyBridgeShim.apk"
check_file "payload/MoseyBridgeShim.apk"
check_file "system_ext/etc/permissions/privapp-permissions-dev.bluehouse.moseybridgeshim.xml"
check_file "system_ext/etc/default-permissions/default-permissions-dev.bluehouse.moseybridgeshim.xml"
check_file "action.sh"
check_file "service.sh"
check_file "boot-completed.sh"
check_file "mosey-control.sh"
check_file "magic"
check_file "post-mount.sh"

if [ "$FILES_MISSING" -gt 0 ]; then
    ui_print ""
    ui_print "[!] $FILES_MISSING critical file(s) missing! Install may not work."
fi
# ========== HybridMount Magic Mode 说明 ==========
# Magic Mode (Bind Mount) 行为：
# - HybridMount 将模块目录下的 odm/、system_ext/ 中每个文件
#   逐个 bind-mount 到对应系统路径（而非 OverlayFS 叠加层）
# - Bind mount 是直接替换 — 系统访问 /odm/bin/mosey_server 时
#   实际指向模块目录中的文件
# - 模块根目录的 magic 标记文件（空文件）通知 HybridMount Nano
#   使用 Magic Mount 后端；Full/Lite 通过 config.toml 配置
#
# 关键时间线：
# 1. KernelSU 解压模块到 /data/adb/modules/mosey-enabler/
# 2. HybridMount 在 post-fs-data 阶段扫描模块目录
# 3. 识别 magic 标记 → 为 odm/ 和 system_ext/ 执行 bind mount
# 4. 所有文件对系统可见（包括 init 读取 .rc 和 VINTF manifest）
#
# 注意：init 在 early boot 阶段已扫描完成 .rc 文件，
# mosey.rc 的 on-demand 服务声明可能不会在 boot 时被解析。
# service.sh 会作为 fallback 直接启动 mosey_server
# MoseyApp 会通过 property_set("ctl.start", "mosey_server") 启动服务。

# ========== 完成 ==========
ui_print ""
ui_print "[✓] Installation structure ready!"
ui_print "[*] HybridMount Magic Mode will activate at next reboot"
ui_print "[*] Full AirDrop functionality available after reboot"
ui_print ""
ui_print "╔══════════════════════════════════════════╗"
ui_print "║   Reboot required for module to take     ║"
ui_print "║                 effect                    ║"
ui_print "╚══════════════════════════════════════════╝"
