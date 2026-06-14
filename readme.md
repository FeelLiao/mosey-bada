# Mosey Implementation via KernelSU

> **Experimental & In Active Development. For research purposes only.**

A KernelSU module that enables AWDL (Apple Wireless Direct Link) discovery and AirDrop-compatible file transfer on non-Pixel Android devices. This project provides the native bridge, BLE scanning shim, and control infrastructure needed to run `mosey_server` — the Rust binary that handles Apple AWDL radio discovery via NL80211 and a Qualcomm `wonder.ko` driver.

---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Building](#building)
- [Deployment](#deployment)
- [Bridge Protocol API](#bridge-protocol-api)
- [Control Interface API](#control-interface-api)
- [Boot Sequence](#boot-sequence)
- [Source Code Reference](#source-code-reference)
- [Known Limitations](#known-limitations)

---

## Overview

This project provides everything needed to bring Apple AirDrop-style AWDL discovery to Android devices with a Qualcomm WiFi chipset and KernelSU root:

| Component | What it does |
|-----------|--------------|
| **KSU module** (`module_mosey/`) | An installable KernelSU module that injects `mosey_server`, `mosey_bridge`, and `MoseyBridgeShim` into the system via magic mount |
| **mosey_bridge** (C) | A TCP↔Binder bridge daemon that lets Android apps (running as `untrusted_app`) send commands to `mosey_server` (running as root) over loopback TCP |
| **MoseyBridgeShim** (Java APK) | A privileged Android app that provides a foreground service with BLE scanning, AWDL HTTPS listener, and event forwarding |
| **mosey-control.sh** | Shell-based control interface for enabling/disabling mosey, checking status, and automatic watchdog |
| **Build infrastructure** | `build.sh` compiles all C sources, Java shim, and APK into a single KSU module zip |

The project does **not** bundle `mosey_server` or `libmosey_daemon_ffi.so` — those must be extracted from a OnePlus GLO or Pixel vendor image.

---

## Architecture

```
                    TCP 127.0.0.1:19539
┌─────────────────────┐      │      ┌──────────────────────┐
│  External Client    │◄─────┴─────►│  MoseyBridgeShim     │
│  (Bada / any app)   │             │  - FGS + BLE scan    │
│  ┌───────────────┐  │             │  - HTTS :19541       │
│  │ ControlClient │  │             │  - event subscriber  │
│  │ enable/disable│  │             └──────────┬───────────┘
│  │ status/wake   │  │                        │
│  └───────────────┘  │                        │ BLE events
│  ┌───────────────┐  │                        │
│  │ SocketClient  │  │                        │
│  │ getVersion/   │  │                        │
│  │ start/stop/   │  │                        │
│  │ update/sub    │  │                        │
│  └───────┬───────┘  │                        │
└──────────┼──────────┘                        │
           │ TCP :19539                        │ TCP :19539
┌──────────┴───────────────────────────────────┴──────────┐
│  mosey_bridge (root, C/NDK)                              │
│                                                          │
│  ┌─ CMD_ENABLE/DISABLE/STATUS → exec mosey-control.sh  │
│  ├─ CMD_GETVERSION/START/STOP/UPDATE → Binder → server │
│  ├─ CMD_WAKE_BADA → broadcast to AirDropWakeReceiver   │
│  └─ Event forwarding → all TCP subscribers              │
└──────────────────────┬───────────────────────────────────┘
                       │ Binder
┌──────────────────────┴───────────────────────────────────┐
│  mosey_server (Rust, root)                                │
│  - Registers: com.google.android.moseyservice.IMoseyService│
│  - dlopen("libmosey_daemon_ffi.so")                       │
│  - PF_PACKET + NL80211 → wonder.ko → mosey0              │
│  - AWDL BLE + 802.11 frame discovery                     │
└──────────────────────────────────────────────────────────┘
```

### Key design decisions

| Decision | Rationale |
|----------|-----------|
| **TCP binary protocol** for app↔bridge | Simple frame format (type+length+payload), easy to implement from any language |
| **mosey-control.sh** for enable/disable | Shell script separates control logic from C code; no recompilation needed |
| **Async CMD_DISABLE** via fork/exec | Bridge pre-acknowledges then backgrounds the disable; avoids self-kill |
| **Operation lock** with timeout | Prevents concurrent enable/disable races; watchdog uses non-blocking try-lock |
| **Two-phase backend wait** | 20s socket check + 3 probe attempts; avoids blocking on Binder polling |
| **Separate control client** | `MoseyControlClient` makes short-lived TCP connections without subscribing to events |

---

## Project Structure

```
mosey-extended/
├── README.md
├── build.sh                         ← One-command module build
│
├── module_mosey/                    ← KernelSU module (deployed to device)
│   ├── module.prop                  ← Module metadata
│   ├── customize.sh                 ← Install-time setup
│   ├── post-fs-data.sh              ← Early boot: linker fix, file verify
│   ├── service.sh                   ← Late boot: start mosey_server + bridge
│   ├── boot-completed.sh            ← Post-boot: start shim FGS
│   ├── mosey-control.sh             ← Control: enable/disable/status/watchdog
│   ├── uninstall.sh                 ← Module removal cleanup
│   ├── sepolicy.rule                ← SELinux rules for ksu domain
│   ├── action.sh                    ← KernelSU action button
│   ├── webroot/index.html           ← KernelSU WebUI
│   ├── odm/                         ← Magic-mounts to /odm/
│   │   ├── bin/mosey_server         ← Rust binary (external)
│   │   ├── bin/mosey_bridge         ← C TCP↔Binder bridge
│   │   ├── lib64/libmosey_preload.so
│   │   ├── lib64/libmosey_daemon_ffi.so  ← (external)
│   │   ├── framework/mosey-shim.jar
│   │   └── etc/init/mosey.rc, vintf/, mosey-shim/
│   ├── system_ext/                  ← Magic-mounts to /system_ext/
│   │   └── priv-app/MoseyBridgeShim/
│   └── payload/MoseyBridgeShim.apk
│
└── src/                             ← Source code
    ├── bridge/
    │   ├── mosey_bridge.c           ← TCP↔Binder bridge daemon
    │   ├── mosey_launcher.c         ← Service pre-registration tool (legacy)
    │   └── mosey_preload.c          ← LD_PRELOAD for AServiceManager intercept
    └── shim/
        ├── dl_shim.c                ← LD_PRELOAD for dlopen path redirect
        ├── java/MoseyShim.java      ← Java shim framework
        └── app/                     ← MoseyBridgeShim APK
            ├── AndroidManifest.xml
            ├── res/
            ├── src/.../moseyshim/
            └── stubs/android/net/   ← Hidden API stubs
```

---

## Building

### Prerequisites

| Tool | Required for |
|------|--------------|
| Android NDK (`aarch64-linux-android35-clang`) | Compiling `mosey_bridge`, `mosey_preload` |
| Android SDK (`platforms/android-36`, `build-tools;36.0.0`) | `d8`, `aapt2`, `zipalign`, `apksigner` |
| Java 8 (`javac`) | Compiling `MoseyShim.java` and shim APK |
| OpenSSL (`openssl`) | Generating TLS keystore for shim HTTPS |

Environment variables:
- `ANDROID_HOME` — path to Android SDK
- `ANDROID_NDK_HOME` — path to Android NDK (optional, auto-detected from SDK)

### Quick build

```bash
cd /path/to/mosey-extended
bash build.sh [output_path.zip]
```

The script:
1. Compiles `mosey_bridge.c` → `module_mosey/odm/bin/mosey_bridge`
2. Compiles `mosey_preload.c` → `module_mosey/odm/lib64/libmosey_preload.so`
3. Compiles `MoseyShim.java` → `module_mosey/odm/framework/mosey-shim.jar`
4. Compiles shim APK sources → `MoseyBridgeShim.apk` (base + update)
5. Generates TLS keystore (if missing)
6. Verifies all required files exist
7. Packages as `mosey-enabler.zip`

### Manual steps

```bash
# Build bridge
aarch64-linux-android35-clang -O2 -Wall -Wextra \
  -o module_mosey/odm/bin/mosey_bridge \
  src/bridge/mosey_bridge.c -lbinder_ndk -ldl -llog -pthread

# Build LD_PRELOAD
aarch64-linux-android35-clang -O2 -Wall -Wextra -shared -fPIC \
  -o module_mosey/odm/lib64/libmosey_preload.so \
  src/bridge/mosey_preload.c -ldl -llog -pthread

# Build Java shim
javac -source 8 -target 8 -bootclasspath $ANDROID_JAR \
  -d build/classes src/shim/java/MoseyShim.java
d8 --min-api 31 --output module_mosey/odm/framework/mosey-shim.jar build/classes
```

---

## Deployment

### Install the module

```bash
adb push mosey-enabler.zip /data/local/tmp/
adb shell su -c 'ksud module install /data/local/tmp/mosey-enabler.zip'
adb reboot
```

### Verify

```bash
# Module loaded
adb shell su -c 'ksud module list | grep mosey'

# Binaries in place
adb shell ls -la /odm/bin/mosey_server /odm/bin/mosey_bridge

# mosey_server running
adb shell ps -ef | grep mosey

# Binder service registered
adb shell service check com.google.android.moseyservice.IMoseyService

# Bridge listening
adb shell su -c 'ss -tlnp | grep 19539'
```

### Quick status check

```bash
adb shell su -c '/data/adb/modules/mosey-enabler/mosey-control.sh webui status'
```

---

## Bridge Protocol API

The bridge listens on **TCP 127.0.0.1:19539** and uses a binary frame protocol.

### Frame format

```
Offset  Size  Field
────────────────────────────────
  0      1    type        (0x01=Request, 0x02=Reply, 0x03=Event)
  1      4    payload_len (unsigned 32-bit LE)
  5      N    payload     (type-specific bytes)
```

All multi-byte integers are **little-endian**.

### Commands (type=0x01 Request → type=0x02 Reply)

| Cmd | Name | Request payload | Reply payload | Description |
|-----|------|----------------|---------------|-------------|
| 0 | `getVersion` | `[0x00]` | `[status:i32][version:i32]` | Returns bridge protocol version |
| 1 | `start` | `[0x01][filters_len:i32][filters:i32[]]` | `[status:i32]` | Start AWDL discovery with channel filters |
| 2 | `stop` | `[0x02]` | `[status:i32]` | Stop AWDL discovery |
| 3 | `update` | `[0x03][cc_len:i32][cc:utf8]` | `[status:i32]` | Update country code (e.g. "CN", "US") |
| 4 | `subscribe` | `[0x04]` | `[status:i32]` | Subscribe to bridge events (only 1 subscriber at a time) |
| 5 | `wakeBada` | `[0x05]` | `[status:i32]` | Send broadcast to Bada's AirDropWakeReceiver |
| 6 | `enable` | `[0x06]` | `[status:i32]` | Enable mosey via mosey-control.sh (runs `webui enable`) |
| 7 | `disable` | `[0x07]` | `[status:i32]` | Disable mosey via mosey-control.sh (runs `webui disable`) |
| 8 | `status` | `[0x08]` | `[status:i32][json_len:i32][json:utf8]` | Query full status as JSON |

### Events (type=0x03 Event)

Sent by the bridge to the subscribed client. Format:

```
[tx_code:u32][event_data...]
```

| tx_code | Name | Payload | Description |
|---------|------|---------|-------------|
| 1 | Event | JSON string | Forwarded Binder callback from mosey_server |
| 3 | Apple BLE seen | JSON `{"deviceName":"...","mac":"..."}` | BLE beacon from Apple device detected |

### Reply status codes

| Status | Meaning |
|--------|---------|
| 0 | Success |
| -1 | General failure |
| -2 | Command not recognized |
| -3 | Bridge not connected to mosey_server |

### Wire examples (hex)

```
→ getVersion:     01 00 00 00 01 00
← reply:         02 08 00 00 00 00 00 00 00 01 00 00 00
                              └─status=0 └─version=1

→ start [149,44]: 01 0D 00 00 00 01 02 00 00 00 95 00 00 00 2C 00 00 00
                              └─cmd └─len=2 └─149    └─44
← reply:         02 04 00 00 00 00 00 00 00
                              └─status=0

→ status:         01 01 00 00 00 08
← reply:         02 XX XX XX 00 ...json...
```

### Protocol notes

- **Connection model**: Simple request-response. The client sends one request and reads one reply. No pipelining.
- **Subscription**: Sending `subscribe` (cmd 4) registers this connection as the event subscriber. Only one subscriber is allowed. If a new connection subscribes, the old one is silently replaced.
- **Timeout**: The bridge sets `SO_RCVTIMEO` and `SO_SNDTIMEO` to 2 seconds on its UNIX control socket. TCP clients should also set read timeouts.
- **Status JSON format** (returned by cmd 8):

```json
{
  "enabled": true,
  "nativeRunning": true,
  "bridgeRunning": true,
  "shimRunning": true,
  "wifiConnected": false,
  "mosey0Exists": true,
  "wonderLoaded": true,
  "countryCode": "CN",
  "countryMode": "fixed"
}
```

---

## Control Interface API

The shell-based control interface is at `/data/adb/modules/mosey-enabler/mosey-control.sh` on the device.

### Commands

```bash
# Enable mosey (starts server + bridge + shim)
mosey-control.sh webui enable

# Disable mosey (stops all components)
mosey-control.sh webui disable

# Query status (returns JSON)
mosey-control.sh webui status

# Show status in human-readable format
mosey-control.sh webui show
```

### Status JSON fields

| Field | Type | Meaning |
|-------|------|---------|
| `enabled` | bool | Mosey is enabled (mosey-enable flag file exists) |
| `nativeRunning` | bool | `mosey_server` process is running |
| `bridgeRunning` | bool | `mosey_bridge` process is running |
| `shimRunning` | bool | `MoseyBridgeShim` foreground service is running |
| `wifiConnected` | bool | Device has an active WiFi connection (STA mode) |
| `mosey0Exists` | bool | `mosey0` network interface exists |
| `wonderLoaded` | bool | `wonder.ko` kernel module is loaded |
| `countryCode` | string | Current country code (e.g. "CN") |
| `countryMode` | string | How country code is set: "fixed" or "dynamic" |

### Watchdog

The control script runs a built-in watchdog loop that:
- Checks every 30 seconds that all components are healthy
- Recovers failed components (restarts server/bridge/shim)
- Uses a non-blocking operation lock to avoid races with manual enable/disable
- Gracefully handles SHIM failures (up to 5 consecutive failures before giving up)

### WebUI

The module includes a KernelSU WebUI at `webroot/index.html` that provides:
- On/off toggle for mosey
- Real-time status grid
- Log viewer

---

## Boot Sequence

```
Power on
  │
  ├─ init scans /odm/etc/init/*.rc
  │   └─ mosey.rc NOT YET visible (KSU overlay not mounted)
  │
  ├─ KernelSU post-fs-data
  │   ├─ KSU magic mount activates (odm/ → /odm/, system_ext/ → /system_ext/)
  │   └─ post-fs-data.sh:
  │       ├─ chmod 755 mosey_server, mosey_bridge
  │       └─ Append /odm/${LIB} to linker namespace → mosey_server can dlopen
  │
  ├─ KernelSU late_start (service.sh)
  │   ├─ Start mosey_server (Rust, AWDL radio daemon)
  │   │   └─ Registers Binder service on default binder
  │   ├─ Wait for mosey_server ready (socket check + Binder probe)
  │   └─ Start mosey_bridge (TCP listener on :19539)
  │
  ├─ Boot completed (boot-completed.sh)
  │   └─ Start MoseyBridgeShim foreground service (BLE scan + HTTPS)
  │
  └─ User unlocks device
      └─ External apps (Bada) connect to bridge via TCP :19539
```

---

## Source Code Reference

### `src/bridge/mosey_bridge.c`

The core TCP↔Binder bridge. Key implementation details:

- **Thread model**: Accept thread accepts new TCP connections. Each client gets a dedicated handler thread (`client_thread`).
- **Binder connection**: Connects to `mosey_server` via `AServiceManager_checkService` (non-blocking, unlike `getService`).
- **Command dispatching**: Incoming commands are dispatched by `handle_cmd()`:
  - CMD 0-3: Forwarded to mosey_server via Binder transact
  - CMD 5: `am broadcast` to `AirDropWakeReceiver`
  - CMD 6-8: `popen`/`system` to `mosey-control.sh`
- **Event forwarding**: A single `g_subscriber` TCP client receives forwarded Binder callbacks as type-0x03 frames.
- **Backend selection**: Supports `--backend-probe unix|binder|auto` and `MOSEY_BACKEND` env var. Default is `auto` (Binder, fallback to UNIX socket).
- **Async disable**: CMD_DISABLE forks, calls `setsid()`, then `execl()` to avoid killing the bridge process itself.

### `src/bridge/mosey_preload.c`

LD_PRELOAD library that intercepts `AServiceManager_addService` and returns `STATUS_OK` without actually registering. Used when `mosey_launcher` has already pre-registered the service.

### `src/shim/java/MoseyShim.java`

Java shim that is loaded as `mosey-shim.jar` from `/odm/framework/`. Provides:
- `MoseyNative` — JNI bridge to native methods
- Service initialization callbacks for `mosey_server`

### `src/shim/app/` — MoseyBridgeShim APK

An Android priv-app that provides:
- `MoseyShimService` — Foreground service with BLE scanning and AWDL HTTPS listener
- `RawMdnsEngine` — Direct mDNS parsing on `mosey0` interface for Apple device discovery
- TLS listener on TCP :19541 for AirDrop `/Discover`, `/Ask`, `/Upload`

### `module_mosey/mosey-control.sh`

Shell-based control (701 lines). Key functions:

| Function | Purpose |
|----------|---------|
| `mosey_enable()` | Start mosey_server, bridge, shim; create mosey0; configure WiFi |
| `mosey_disable()` | Stop mosey_server, bridge, shim; restore WiFi; clean up |
| `check_backend_fast()` | Quick `ss`-based check if bridge is listening |
| `check_backend_probe()` | Connect to bridge and probe via Binder or UNIX socket |
| `wait_for_backend()` | Two-phase wait: 20s socket poll + 3 probe attempts |
| `with_operation_lock()` | Acquire lock file, run a command, release (blocking) |
| `try_with_operation_lock()` | Non-blocking lock attempt (returns 75 if busy) |
| `run_watchdog()` | Background health check loop (30s interval) |

---

## Known Limitations

1. **Requires mosey_server binary**: The Rust `mosey_server` and `libmosey_daemon_ffi.so` are not bundled — they must be extracted from a OnePlus GLO or Pixel vendor image.
2. **Qualcomm wonder.ko required**: This project targets devices with Qualcomm WiFi chipsets that have `wonder.ko` with `wondertap` support. Other chipsets (MediaTek, BCM without wondertap) are not supported.
3. **WiFi disconnection during mosey use**: Qualcomm's concurrency policy prevents STA + wondertap simultaneously. The shim disconnects WiFi before starting mosey and reconnects on stop.
4. **SELinux**: The KSU module's `sepolicy.rule` provides rules for the `ksu` domain. If `mosey_server` runs under a different SELinux context, additional rules may be needed.
5. **ColorOS/OPlus freezer**: Devices with OPlus freezer may trap Bada processes in `do_freezer_trap`. The module configures `RUN_IN_BACKGROUND` whitelist as a workaround.
6. **One subscriber limit**: The bridge supports only one event subscriber at a time. Use `MoseyControlClient` (short-lived, no subscription) for operational commands.

---

*For research and debugging purposes only. Not for commercial use.*

# Reference

- [mosey-extended](https://github.com/thelok1s/mosey-extended)
