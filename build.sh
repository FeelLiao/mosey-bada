#!/bin/bash
# build.sh - Build the mosey-enabler KSU module zip
# Usage: bash build.sh [output_path]
#
# The module uses KernelSU's magic mount (OverlayFS) with the following layout:
#   odm/bin/mosey_server     → /odm/bin/mosey_server
#   odm/lib64/...so          → /odm/lib64/...
#   odm/etc/init/...         → /odm/etc/init/...
#   odm/etc/vintf/...        → /odm/etc/vintf/...
#   system_ext/priv-app/...  → /system_ext/priv-app/...
#   system_ext/etc/...       → /system_ext/etc/...

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/module_mosey"

if [ ! -d "$MODULE_DIR" ]; then
  echo "[!] Module directory not found at $MODULE_DIR"
  echo "    Run this script from the repo root."
  exit 1
fi

OUTPUT="${1:-$SCRIPT_DIR/mosey-enabler.zip}"
case "$OUTPUT" in
  /*) ;;
  *) OUTPUT="$SCRIPT_DIR/$OUTPUT" ;;
esac
SDK_ROOT="${ANDROID_HOME:-/Users/feelliao/fvm/Android/sdk}"
ANDROID_PLATFORM="${ANDROID_PLATFORM:-android-36}"
ANDROID_JAR="$SDK_ROOT/platforms/$ANDROID_PLATFORM/android.jar"
D8="$SDK_ROOT/build-tools/36.0.0/d8"
AAPT2="$SDK_ROOT/build-tools/36.0.0/aapt2"
ZIPALIGN="$SDK_ROOT/build-tools/36.0.0/zipalign"
APKSIGNER="$SDK_ROOT/build-tools/36.0.0/apksigner"
NDK_ROOT="${ANDROID_NDK_HOME:-}"

if [ -z "$NDK_ROOT" ]; then
  if [ -d "$SDK_ROOT/ndk" ]; then
    NDK_ROOT="$(ls -dt "$SDK_ROOT"/ndk/* 2>/dev/null | head -1)"
  else
    NDK_ROOT="$SDK_ROOT/ndk"
  fi
elif [ -d "$NDK_ROOT" ] && [ ! -d "$NDK_ROOT/toolchains" ]; then
  NDK_ROOT="$(ls -dt "$NDK_ROOT"/* 2>/dev/null | head -1)"
fi
CLANG="$NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android35-clang"

echo "[*] Mosey Enabler - Building module..."
echo "[*] Module dir: $MODULE_DIR"
echo "[*] Output: $OUTPUT"

echo "[*] Building native bridge..."
if [ ! -x "$CLANG" ]; then
  echo "[!] NDK clang not found at $CLANG"
  exit 1
fi
"$CLANG" -O2 -Wall -Wextra -o "$MODULE_DIR/odm/bin/mosey_bridge" \
  "$SCRIPT_DIR/src/bridge/mosey_bridge.c" -lbinder_ndk -ldl -llog -pthread
"$CLANG" -O2 -Wall -Wextra -shared -fPIC -o "$MODULE_DIR/odm/lib64/libmosey_preload.so" \
  "$SCRIPT_DIR/src/bridge/mosey_preload.c" -ldl -llog -pthread

echo "[*] Building Java shim..."
if [ ! -f "$ANDROID_JAR" ]; then
  echo "[!] android.jar not found at $ANDROID_JAR"
  exit 1
fi
if [ ! -x "$D8" ]; then
  echo "[!] d8 not found at $D8"
  exit 1
fi
SHIM_BUILD="$SCRIPT_DIR/.build/mosey-shim"
rm -rf "$SHIM_BUILD"
mkdir -p "$SHIM_BUILD/classes" "$MODULE_DIR/odm/framework" "$MODULE_DIR/odm/etc/mosey-shim"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$SHIM_BUILD/classes" "$SCRIPT_DIR/src/shim/java/MoseyShim.java"
jar cf "$SHIM_BUILD/mosey-shim-classes.jar" -C "$SHIM_BUILD/classes" .
"$D8" --min-api 31 --output "$MODULE_DIR/odm/framework/mosey-shim.jar" \
  "$SHIM_BUILD/mosey-shim-classes.jar"

echo "[*] Building MoseyShim priv-app APK..."
if [ ! -x "$AAPT2" ] || [ ! -x "$ZIPALIGN" ] || [ ! -x "$APKSIGNER" ]; then
  echo "[!] Android build-tools missing aapt2/zipalign/apksigner"
  exit 1
fi
APK_BUILD="$SCRIPT_DIR/.build/mosey-shim-apk"
rm -rf "$APK_BUILD"
rm -rf "$MODULE_DIR/system_ext/priv-app/MoseyShim"
rm -f "$MODULE_DIR/payload/MoseyBridgeShim.apk"
mkdir -p "$APK_BUILD/classes" "$APK_BUILD/stub-classes" "$APK_BUILD/dex" "$APK_BUILD/compiled" \
         "$MODULE_DIR/system_ext/priv-app/MoseyBridgeShim" \
         "$MODULE_DIR/payload"
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -d "$APK_BUILD/stub-classes" \
  "$SCRIPT_DIR"/src/shim/app/stubs/android/net/*.java
jar cf "$APK_BUILD/mosey-hidden-api-stubs.jar" -C "$APK_BUILD/stub-classes" .
javac -source 8 -target 8 -bootclasspath "$ANDROID_JAR" \
  -classpath "$APK_BUILD/mosey-hidden-api-stubs.jar" \
  -d "$APK_BUILD/classes" \
  "$SCRIPT_DIR"/src/shim/app/src/dev/bluehouse/moseyshim/*.java
jar cf "$APK_BUILD/mosey-shim-apk-classes.jar" -C "$APK_BUILD/classes" .
"$D8" --min-api 31 --output "$APK_BUILD/dex" "$APK_BUILD/mosey-shim-apk-classes.jar"
"$AAPT2" compile --dir "$SCRIPT_DIR/src/shim/app/res" -o "$APK_BUILD/compiled"
build_shim_apk() {
  local VERSION_CODE="$1"
  local VERSION_NAME="$2"
  local OUTPUT_APK="$3"
  local STEM="$4"

  "$AAPT2" link -I "$ANDROID_JAR" \
    --manifest "$SCRIPT_DIR/src/shim/app/AndroidManifest.xml" \
    --min-sdk-version 31 --target-sdk-version 36 \
    --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --replace-version \
    -o "$APK_BUILD/$STEM.unsigned.apk" "$APK_BUILD/compiled"/*.flat
  (
    cd "$APK_BUILD/dex"
    zip -q "$APK_BUILD/$STEM.unsigned.apk" classes.dex
  )
  "$ZIPALIGN" -f 4 "$APK_BUILD/$STEM.unsigned.apk" "$APK_BUILD/$STEM.aligned.apk"
  "$APKSIGNER" sign --ks "$SCRIPT_DIR/.build/mosey-shim-debug.keystore" \
    --ks-key-alias moseyshim --ks-pass pass:moseyshim --key-pass pass:moseyshim \
    --out "$OUTPUT_APK" "$APK_BUILD/$STEM.aligned.apk"
}

if [ ! -f "$SCRIPT_DIR/.build/mosey-shim-debug.keystore" ]; then
  keytool -genkeypair -v -keystore "$SCRIPT_DIR/.build/mosey-shim-debug.keystore" \
    -storepass moseyshim -keypass moseyshim -alias moseyshim \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Mosey Shim,O=Bada,C=US" >/dev/null 2>&1
fi

# The mounted system APK is the stable factory baseline. The payload has a
# strictly higher version so PackageManager installs it as a system-app update.
build_shim_apk 29 1.29 \
  "$MODULE_DIR/system_ext/priv-app/MoseyBridgeShim/MoseyBridgeShim.apk" \
  "MoseyBridgeShim-base"
build_shim_apk 30 1.29 \
  "$MODULE_DIR/payload/MoseyBridgeShim.apk" \
  "MoseyBridgeShim-update"

if [ ! -f "$MODULE_DIR/odm/etc/mosey-shim/mosey-shim.p12" ]; then
  echo "[*] Generating shim TLS keystore..."
  openssl req -x509 -newkey rsa:2048 -keyout "$SHIM_BUILD/key.pem" \
    -out "$SHIM_BUILD/cert.pem" -days 3650 -nodes \
    -subj "/CN=Bada Mosey Shim" >/dev/null 2>&1
  openssl pkcs12 -export -out "$MODULE_DIR/odm/etc/mosey-shim/mosey-shim.p12" \
    -inkey "$SHIM_BUILD/key.pem" -in "$SHIM_BUILD/cert.pem" \
    -passout pass:mosey >/dev/null 2>&1
fi

# Verify critical files
CRITICAL=(
  "module.prop"
  "customize.sh"
  "post-fs-data.sh"
  "post-mount.sh"
  "service.sh"
  "boot-completed.sh"
  "mosey-control.sh"
  "sepolicy.rule"
  "odm/bin/mosey_server"
  "odm/bin/mosey_bridge"
  "odm/framework/mosey-shim.jar"
  "odm/etc/mosey-shim/mosey-shim.p12"
  "system_ext/priv-app/MoseyBridgeShim/MoseyBridgeShim.apk"
  "payload/MoseyBridgeShim.apk"
  "system_ext/etc/permissions/privapp-permissions-dev.bluehouse.moseybridgeshim.xml"
  "system_ext/etc/default-permissions/default-permissions-dev.bluehouse.moseybridgeshim.xml"
  "odm/lib64/libmosey_daemon_ffi.so"
  "odm/lib64/libmosey_preload.so"
  "odm/etc/init/mosey.rc"
  "odm/etc/init/lowi-server.rc"
  "odm/etc/permissions/android.hardware.wifi.aware.xml"
  "odm/vendor/etc/wifi/WCNSS_qcom_cfg.ini"
  "odm/etc/wifi/peach/bdwlan.elf"
  "odm/etc/wifi/peach/bdwlan.b0a"
  "odm/etc/wifi/peach/bdwlan.b0e"
  "odm/etc/wifi/peach/bdwlan.b0i"
  "odm/etc/wifi/bdwlan.b0a.txt"
  "odm/etc/wifi/bdwlan.b0e.txt"
  "odm/etc/wifi/bdwlan.b0i.txt"
  "odm/etc/vintf/manifest/manifest_mosey.xml"
  "system_ext/priv-app/MoseyApp/MoseyApp.apk"
  "system_ext/etc/permissions/privapp-permissions-com.google.android.mosey.xml"
)

echo "[*] Verifying files..."
for f in "${CRITICAL[@]}"; do
  if [ -f "$MODULE_DIR/$f" ]; then
    echo "  [✓] $f"
  else
    echo "  [✗] $f - MISSING!"
    exit 1
  fi
done

# Set proper permissions
chmod 755 "$MODULE_DIR/odm/bin/mosey_server" \
         "$MODULE_DIR/odm/bin/mosey_bridge" 2>/dev/null
chmod 755 "$MODULE_DIR/customize.sh" \
         "$MODULE_DIR/post-fs-data.sh" \
         "$MODULE_DIR/post-mount.sh" \
         "$MODULE_DIR/service.sh" \
         "$MODULE_DIR/boot-completed.sh" \
         "$MODULE_DIR/mosey-control.sh" \
         "$MODULE_DIR/action.sh" \
         "$MODULE_DIR/uninstall.sh" 2>/dev/null
rm -f "$MODULE_DIR/system_ext/priv-app/MoseyBridgeShim/"*.idsig
rm -f "$MODULE_DIR/payload/"*.idsig

# Build zip
cd "$MODULE_DIR"
rm -f "$OUTPUT"
zip -r "$OUTPUT" . -x "*.DS_Store" "*.zip" > /dev/null

echo ""
echo "═══════════════════════════════════════════"
echo "  [✓] Build complete!"
echo "  File: $(ls -lh "$OUTPUT" | awk '{print $5}')"
echo "  Path: $OUTPUT"
echo "═══════════════════════════════════════════"
