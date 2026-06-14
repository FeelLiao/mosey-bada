

# English

> [!CAUTION]
> **EXPERIMENTAL AND IN ACTIVE DEVELOPMENT!**
> 
> **FLASH ANY MODULES AT YOUR OWN RISK!** You **MUST** know exactly what you are doing.  
> For research and debugging purposes only.  

## Table of Contents

1. [What is this?](#1-what-is-this)
2. [How AirDrop (mosey) works — full stack](#2-how-airdrop-mosey-works--full-stack)
3. [Full modification tree](#3-full-modification-tree)
4. [Supported devices & Wi-Fi modems](#4-supported-devices--wi-fi-modems)
5. [Key files location table](#5-key-files-location-table)
6. [Current status](#6-current-status)
7. [Build: wonder\_mosey\_wild.ko](#7-build-wonder_mosey_wildko)
8. [Deployment & service.sh integration](#8-deployment--servicesh-integration)
9. [Known limitations](#9-known-limitations)

---

## 1. What is this?

A few weeks ago, I reverse-engineered a Pixel 10 firmware image and identified
the missing component required to enable AirDrop-style functionality in Google
Quick Share on older Pixel devices — and potentially any Android device.

I found a native binary named **`mosey_server`** (matching the APK extension
component for Quick Share). Static analysis shows it is a native Android
service, not a CLI tool. It links against `libbinder_ndk.so`, `liblog.so`,
`libc.so`, and `libdl.so`, and contains the string
`AServiceManager_addService`. The embedded source path
`vendor/google/services/QuickShareExtension/src/server.rs` confirms it is part
of the Quick Share extension and is expected to start at boot.

The binary attempts to register a native AIDL (NDK Binder) service via
`AServiceManager_addService("com.google.pixel.service.IService/default")`.

Initial attempts to inject and run this binary via KSU module failed because:
1. `AServiceManager_addService()` requires more than just SELinux `allow` rules.
2. The service name must be mapped to a valid SELinux service type in
   `vendor_service_contexts`.
3. Without that mapping, registration fails with `PERMISSION_DENIED` or
   `UNKNOWN_ERROR`.

The Pixel 10 vendor image includes all required components. This project
transplants them — along with a virtual "wonder" Wi-Fi phy — so that
`mosey_server` can run on any rooted Android device.

---

## 2. How AirDrop (mosey) works — full stack

Google's "AirDrop" (internal codename **mosey**) in Quick Share requires a
specific Wi-Fi interface named `wonder` to be present on the device. The
`mosey_server` native service communicates with this interface through the
Linux `cfg80211`/`nl80211` subsystem.

The full boot sequence:

```
Boot
 └─ init parses mosey.rc
     └─ starts mosey_server (NET_ADMIN, NET_RAW caps)
         ├─ registers "com.google.pixel.service.IService/default" with servicemanager
         ├─ sends NL80211_CMD_NEW_INTERFACE to create "wonder0" (MONITOR mode)
         ├─ sets channel 149 / 5745 MHz via NL80211_CMD_SET_CHANNEL
         ├─ sends NL80211 vendor commands (vendor_id=0x1A11):
         │   ├─ subcmd 1: set_frequency
         │   ├─ subcmd 2: set_filter
         │   ├─ subcmd 3: set_fixed_tx_rate
         │   ├─ subcmd 4: set_reg
         │   └─ subcmd 5: get_if_mac_addr → reads 6-byte MAC
         └─ opens PF_PACKET / TPACKET_V3 on wonder0 for 802.11 frame I/O
```

On Pixel 9 / 10, this chain works natively via the BCM4398 chip's
`wondertap` mechanism inside `bcmdhd`. On older Pixels (7/8) and non-Pixel
devices, this project provides the missing pieces:

- **`wonder_mosey_wild.ko`** — a standalone virtual mac80211 driver that
  creates the `wonder` phy and handles all vendor commands natively.
- **SELinux policy** — extracted from Pixel 10, injected via KSU `sepolicy.rule`.
- **`mosey.rc`** — init service definition, overlaid via KSU module.

---

## 3. Full modification tree

The following tree lists every layer that must be modified or provided to
bring AirDrop online, from high-level feature flags down to the modem driver.
Items marked ✅ are handled by this module; ⚠️ indicates partial / in-progress;
❌ indicates not yet implemented.

```
AirDrop (mosey Quick Share) — Full Stack
│
├── [Layer 0] Phenotype / Feature Flags                          ✅
│   ├── pixel_experience_YYYY.xml
│   │   └── com.google.android.feature.PIXEL_XXXX_EXPERIENCE
│   │       declares the device as Pixel-class to GMS
│   ├── phenotype.db
│   │   └── NearbyShare / QuickShare feature gates
│   └── payload/pixel_experience_*.xml
│       └── injected via KSU module overlay (install.sh)
│
├── [Layer 1] APK / GMS                                          ✅ (GMS managed)
│   ├── com.google.android.gms — Nearby/Quick Share core service
│   ├── com.google.android.apps.nearby.sharewidget — Quick Share UI
│   └── MoseyApp — vendor Quick Share extension APK
│
├── [Layer 2] Native Binary                                      ✅
│   └── /vendor/bin/mosey_server
│       ├── Language: Rust (embedded source path confirms)
│       ├── Links: libbinder_ndk, liblog, libc, libdl
│       └── Binder service: "com.google.pixel.service.IService/default"
│
├── [Layer 3] Init / Service Management                          ✅
│   └── /vendor/etc/init/mosey.rc
│       ├── on boot: start mosey_server
│       ├── user system, group system inet
│       └── capabilities: NET_ADMIN NET_RAW
│
├── [Layer 4] SELinux Policy                                     ✅ (partial)
│   ├── vendor_service_contexts
│   │   └── maps "com.google.pixel.service.IService/default" → mosey_service
│   ├── vendor_sepolicy.cil
│   │   └── allow rules: mosey_server domain permissions
│   ├── vendor_file_contexts
│   │   └── /vendor/bin/mosey_server → u:object_r:mosey_exec:s0
│   ├── product_sepolicy.cil
│   │   └── mosey_app domain, typeattributeset rules
│   ├── system_ext_sepolicy.cil
│   │   └── system_ext mosey rules
│   ├── system_ext_seapp_contexts
│   │   └── app package → SELinux domain mapping
│   └── 202504.cil
│       └── API-level compatibility mapping (API 36 / Android 16)
│
├── [Layer 5] Wi-Fi Interface ("wonder" phy)                    ⚠️ (build in progress)
│   ├── cfg80211 phy named "wonder"
│   │   └── renamed via: iw phy phyN set name wonder
│   ├── wonder0 — MONITOR mode interface (NL80211_CMD_NEW_INTERFACE)
│   ├── channel: 149 / 5745 MHz (5 GHz band)
│   └── NL80211 vendor commands (vendor_id = 0x001A11):
│       ├── subcmd 1: set_frequency  → noop (return 0)
│       ├── subcmd 2: set_filter     → noop (return 0)
│       ├── subcmd 3: set_fixed_tx_rate → noop (return 0)
│       ├── subcmd 4: set_reg        → noop (return 0)
│       └── subcmd 5: get_if_mac_addr → returns 6-byte MAC via NL attr
│
├── [Layer 6] Kernel Module                                      ⚠️ (build in progress)
│   │
│   ├── Option A — Native (Samsung wonder.ko) [Pixel 9+, S24 Exynos only]
│   │   ├── BCM wondertap interface to bcmdhd driver
│   │   ├── Requires: "wondertap-provider" DT phandle in device tree
│   │   ├── Requires: BCM4398 chip + bcmdhd with wondertap symbols
│   │   └── Available pre-built: kernel 6.1.145 (Pixel 9), 6.1.157 (S24 Exynos)
│   │
│   └── Option B — Standalone (wonder_mosey_wild.ko) ← THIS PROJECT
│       ├── Virtual mac80211 driver, no hardware dependency
│       ├── Satisfies full NL80211 init sequence natively
│       ├── Kernel: android14-6.1-2025-09 + Wild KSU patches
│       ├── vermagic: 6.1.145-android14-11-Wild-Exclusive
│       └── Runs on any KSU/Magisk-rooted device (virtual phy, no real RF)
│
└── [Layer 7] Wi-Fi Driver / Modem Firmware                     device-specific
    ├── BCM4398 (Pixel 9/10, Samsung Galaxy S24 Exynos)
    │   ├── bcmdhd4390.ko with native wondertap
    │   └── Full RF: real 802.11 frame TX/RX via wonder0
    ├── BCM4389 (Pixel 7/8, Pixel Fold)
    │   ├── bcmdhd without native wondertap
    │   └── wonder_mosey_wild.ko provides virtual phy (no real RF)
    ├── Qualcomm FastConnect (Samsung S24/S25 Snapdragon, OnePlus, etc.)
    │   └── No wondertap; standalone virtual phy only via this module
    └── MediaTek MT7925 (OPPO Find X8, Vivo X200, Xiaomi 15)
        └── No wondertap; standalone virtual phy only via this module
```

---

## 4. Supported devices & Wi-Fi modems

> **Column key**
> - **Native wonder** — device ships with BCM wondertap support in bcmdhd + wonder.ko
> - **Virtual phy** — `wonder_mosey_wild.ko` can provide the wonder interface (no real RF)
> - **mosey_server** — binary sourced from Pixel 10 vendor image; SELinux transplant required on all non-Pixel-10 devices

### Google Pixel

| Device name | Codename | SoC | Wi-Fi module | Native wonder | Virtual phy | Note |
|------------|-------------|-----|-----------|:---:|:---:|------------|
| Pixel 10 Pro XL | mustang | Tensor G5 | BCM4398 |  ✅ | ✅ | Oficiall support |
| Pixel 10 Pro | blazer | Tensor G5 | BCM4398 | ✅ | ✅ | Oficiall support  |
| Pixel 10 | frankel | Tensor G5 | BCM4398 |  ✅ | ✅ | Oficiall support |
| Pixel 9 Pro XL | komodo | Tensor G4 | BCM4390 |  ✅ | ✅ | Oficiall support, bcmdhd4390.ko has wondertap |
| Pixel 9 Pro | caiman | Tensor G4 | BCM4390 |  ✅ | ✅ | Oficiall support, bcmdhd4390.ko has wondertap |
| Pixel 9 Pro Fold | comet | Tensor G4 | BCM4390 |  ✅ | ✅ | Oficiall support, bcmdhd4390.ko has wondertap |
| Pixel 9 | tokay | Tensor G4 | BCM4390 | ✅ | ✅ | Oficiall support  |
| Pixel 9a | tegu | Tensor G4 | BCM4389 | ❌ | ? | – |
| Pixel 8 Pro | husky | Tensor G3 | BCM4389 |  ❌ | ✅ | Main target |
| Pixel 8 | shiba | Tensor G3 | BCM4389 | ❌ | ✅ | – |
| Pixel 8a | akita | Tensor G3 | BCM4383 | ❌ | ✅ | – |
| Pixel 7 Pro | cheetah | Tensor G2 | BCM4389 | ❌ | ✅ | – |
| Pixel 7 | panther | Tensor G2 | BCM4389 | ❌ | ✅ | – |
| Pixel 7a | lynx | Tensor G2 | BCM4389 | ❌ | ✅ | – |
| Pixel Fold | felix | Tensor G2 | BCM4389 | ❌ | ✅ | – |

### Samsung Galaxy S

| Device name  | SoC | Wi-Fi module |  Native wonder | Virtual phy | Note |
|------------|-----|-----------|:---:|:---:|------------|
| Galaxy S24 |  Exynos 2400 | BCM4398 | ✅ | ✅ | wonder.ko kernel 6.1.157 found|
| Galaxy S24+| Exynos 2400 | BCM4398 |  ✅ | ✅ | Same as S24 Exynos |
| Galaxy S24 |  Snapdragon 8 Gen 3 | Qualcomm WCN685x |  ✅ | ✅ | – |
| Galaxy S24+ | Snapdragon 8 Gen 3 | Qualcomm WCN685x | | ✅ | ✅ | — |
| Galaxy S24 Ultra |  Snapdragon 8 Gen 3 | Qualcomm WCN685x |  ✅ | ✅ | – |
| Galaxy S25 |  Snapdragon 8 Elite | Qualcomm FastConnect 7900 | ✅ | ✅ | – |
| Galaxy S25+ |  Snapdragon 8 Elite | Qualcomm FastConnect 7900 | ✅ | ✅ | — |
| Galaxy S25 Ultra | Snapdragon 8 Elite | Qualcomm FastConnect 7900 |  ✅ | ✅ | — |

### BBK

| Device | SoC | Wi-Fi module |  Native wonder | Virtual phy | Note |
|------------|-----|-----------|:---:|:---:|------------|
| Vivo X300 Pro | Dimensity 9500 | MediaTek MT6993 | ✅ | ✅ | — |
| OPPO Find X8 Pro | Dimensity 9400 | MediaTek MT7925 | ✅ | ✅ | — |
| OPPO Find X8 Ultra | Dimensity 9400 | MediaTek MT7925 | ✅ | ✅ | — |

> **Note on virtual phy**: `wonder_mosey_wild.ko` creates a valid `wonder0` interface and satisfies
> `mosey_server`'s full NL80211 init sequence. However, without native BCM wondertap, real
> 802.11 frame I/O will not work — proximity discovery via 802.11 scanning is unavailable.
> BLE-based discovery may still function. This is the current limitation of all non-BCM devices.

---

## 5. Key files location table

Files relevant to mosey — sourced from the Pixel 10 vendor image unless noted.
All paths are device-side (post-overlay).

| File | Partition | Device Path | Purpose | Source |
|------|-----------|-------------|---------|--------|
| `mosey_server` | vendor | `/vendor/bin/mosey_server` | Native AirDrop service binary (Rust) | Pixel 10 factory image |
| `mosey.rc` | vendor | `/vendor/etc/init/mosey.rc` | init service definition | Pixel 10 / this module |
| `vendor_service_contexts` | vendor | `/vendor/etc/selinux/vendor_service_contexts` | Binder service → SELinux type mapping | Pixel 10 vendor image |
| `vendor_sepolicy.cil` | vendor | `/vendor/etc/selinux/vendor_sepolicy.cil` | mosey_server allow rules | Pixel 10 vendor image |
| `vendor_file_contexts` | vendor | `/vendor/etc/selinux/vendor_file_contexts` | `/vendor/bin/mosey_server` file label | Pixel 10 vendor image |
| `product_sepolicy.cil` | product | `/product/etc/selinux/product_sepolicy.cil` | mosey_app domain rules | Pixel 10 product image |
| `system_ext_sepolicy.cil` | system_ext | `/system_ext/etc/selinux/system_ext_sepolicy.cil` | system_ext mosey rules | Pixel 10 system_ext |
| `system_ext_seapp_contexts` | system_ext | `/system_ext/etc/selinux/system_ext_seapp_contexts` | App package → SELinux domain | Pixel 10 system_ext |
| `202504.cil` | system | `/system/etc/selinux/mapping/202504.cil` | API 36 / Android 16 compat mapping | Pixel 10 system image |
| `compatibility_matrix.xml` | system | `/system/compatibility_matrix.device.xml` | HAL + kernel compat requirements | Pixel 10 system image |
| `pixel_experience_YYYY.xml` | system | `/system/etc/permissions/pixel_experience_YYYY.xml` | GMS feature declarations | This module (`payload/`) |
| `sepolicy.rule` | module | `$MODDIR/sepolicy.rule` | KSU runtime policy additions | This module |
| `wonder_mosey_wild.ko` | vendor | `/vendor/lib/modules/wonder_mosey_wild.ko` | Virtual wonder phy kernel module | Built by `build.sh` |
| `rename_phy` | vendor | `/vendor/bin/rename_phy` | NL80211 phy rename utility (static aarch64) | Built by `build.sh` |
| `mosey_server.pid` | data | `/data/adb/mosey-extended/mosey_server.pid` | Runtime PID file | service.sh |
| `service.log` | data | `/data/adb/mosey-extended/service.log` | Module boot log | service.sh |
| `mosey_server.log` | data | `/data/adb/mosey-extended/mosey_server.log` | mosey_server stdout/stderr | service.sh |

### Module file tree (KSU/Magisk overlay)

```
module root/
├── module.prop
├── service.sh                    ← boot-time launcher
├── sepolicy.rule                 ← runtime SELinux rules
├── customize.sh                  ← install-time setup
├── uninstall.sh
├── payload/
│   └── pixel_experience_*.xml    ← GMS feature flags by year
├── system/
│   └── vendor/
│       ├── bin/
│       │   ├── mosey_server      ← from Pixel 10 vendor image
│       │   └── rename_phy        ← built by build.sh
│       ├── etc/
│       │   └── init/
│       │       └── mosey.rc
│       └── lib/
│           └── modules/
│               └── wonder_mosey_wild.ko   ← built by build.sh
└── agy/
    ├── ksu_wonder_module/
    │   └── mosey_wonder/
    │       ├── wonder_mosey_wild.c    ← kernel module source
    │       ├── Dockerfile.kmod        ← build environment
    │       ├── build.sh               ← one-command builder
    │       ├── Kbuild
    │       └── rename_phy.c
    └── native_poc/
        └── native_poc_docs.md         ← BCM wondertap research
```

---

## 6. Current status

| Component | Status | Notes |
|-----------|--------|-------|
| mosey_server binary (Pixel 10) | ✅ Extracted | In `system/vendor/bin/mosey_server` |
| mosey.rc init definition | ✅ Working | `system/vendor/etc/init/mosey.rc` |
| SELinux policy (KSU sepolicy.rule) | ✅ Working | Minimal allow rules; full CIL files still needed for production |
| Pixel Experience feature flags | ✅ Working | `payload/pixel_experience_*.xml` injected |
| `wonder_mosey_wild.ko` (virtual phy) | ⚠️ Building | Build6 in progress; target vermagic: `6.1.145-android14-11-Wild-Exclusive` |
| service.sh boot launcher | ✅ Working | Waits for `sys.boot_completed`, starts mosey_server |
| phy rename (`iw phy phyN set name wonder`) | ⚠️ Pending ko | Waits for `wonder_mosey_wild.ko` to expose `phy_index` |
| Native BCM wondertap (Pixel 7/8) | ❌ Blocked | No wonder.ko for kernel 5.10/5.15; standalone module is the workaround |
| Full 802.11 frame I/O | ❌ Not yet | Requires real BCM4398 hardware path (Pixel 9+ only) |
| Non-Pixel devices | 🔬 Research | Theoretically works with KSU + virtual phy; untested |

**Active development target**: Pixel 8 Pro (husky) running Wild KSU
(`6.1.145-android14-11-Wild-Exclusive`).

---

## 7. Build: wonder\_mosey\_wild.ko

The kernel module is built inside Docker against the exact kernel source
that Wild KSU uses, so the vermagic matches byte-for-byte.

### Prerequisites

- Docker Desktop (macOS / Linux)
- 20 GB free disk space (Docker image is ~8 GB; first build ~15–25 min)

### Build

```bash
cd agy/ksu_wonder_module/mosey_wonder
bash build.sh
# Output: <repo-root>/out/wonder_mosey_wild.ko
# Expected: [+] vermagic: 6.1.145-android14-11-Wild-Exclusive SMP preempt mod_unload modversions aarch64
```

Subsequent builds use the Docker layer cache and take ~30 seconds.

### Kernel environment (Dockerfile.kmod)

| Item | Value |
|------|-------|
| Base image | `ubuntu:noble` |
| Compiler | `clang-17` / `LLVM=1` (required for `CONFIG_KCFI_CLANG=y`) |
| Kernel manifest | `android.googlesource.com/kernel/manifest` branch `common-android14-6.1-2025-09` |
| Wild KSU patch | `WildKernels/kernel_patches` — `ksun-5a4a718-susfs-f7ae19ef-gki-android14-6.1.patch` |
| EXTRAVERSION | `-android14-11` (injected via `sed` into kernel `Makefile`) |
| CONFIG\_LOCALVERSION | `-Wild-Exclusive` (set via hardcoded `setlocalversion` script) |
| Target vermagic | `6.1.145-android14-11-Wild-Exclusive SMP preempt mod_unload modversions aarch64` |

### Building for other kernel versions

| Target device | Kernel | Manifest branch | Change in Dockerfile |
|---------------|--------|-----------------|----------------------|
| Pixel 8 / 8 Pro (stock) | 5.15 | `android14-5.15` | Update branch + EXTRAVERSION |
| Pixel 7 / 7 Pro | 5.10 | `android13-5.10` | Update branch + EXTRAVERSION |
| Pixel 9 / 10 | 6.1 | `android14-6.1-2025-09` | Same as Wild KSU (no patch needed) |
| Samsung S24 | 6.1 | Check Samsung kernel source | Different EXTRAVERSION / CONFIG\_LOCALVERSION |

---

## 8. Deployment & service.sh integration

### Push and load manually

```bash
adb push out/wonder_mosey_wild.ko /data/local/tmp/
adb shell su -c 'insmod /data/local/tmp/wonder_mosey_wild.ko'

# Verify:
adb shell dmesg | grep wonder_mosey_wild
# Expected: wonder_mosey_wild: phy2  MAC=6a:b0:5d:c7:27:3d  →  iw phy phy2 set name wonder

# Rename phy:
WPHY=$(adb shell su -c 'cat /sys/module/wonder_mosey_wild/parameters/phy_index')
adb shell su -c "iw phy phy${WPHY} set name wonder"
```

### service.sh integration snippet

Add this block **before** the mosey_server launch in `service.sh`:

```sh
WONDER_KO="$MODDIR/system/vendor/lib/modules/wonder_mosey_wild.ko"
if [ -f "$WONDER_KO" ]; then
    insmod "$WONDER_KO"
    /system/bin/sleep 1
    WPHY=$(cat /sys/module/wonder_mosey_wild/parameters/phy_index 2>/dev/null)
    if [ -n "$WPHY" ] && [ "$WPHY" -ge 0 ] 2>/dev/null; then
        iw phy phy${WPHY} set name wonder
    fi
fi
```

> **Wild KSU warning**: Wild KSU's `service.sh` executor strips standalone
> `#` comment lines before running the script. Do not add comment-only lines.

### Module parameters

| Parameter | Default | Description |
|-----------|---------|-------------|
| `mac_addr` | `6a:b0:5d:c7:27:3d` | MAC address returned for NL80211 vendor subcmd 5. Must be locally-administered (U/L bit set). |
| `phy_index` | read-only | phy index assigned by cfg80211. Read via `/sys/module/wonder_mosey_wild/parameters/phy_index` to call `iw phy phyN set name wonder`. |

Example with custom MAC:

```bash
insmod wonder_mosey_wild.ko mac_addr=02:ab:cd:ef:12:34
```

---

## 9. Known limitations

1. **No real 802.11 RF on Pixel 7/8**: `wonder_mosey_wild.ko` creates a virtual
   phy. The `wonder0` interface exists and mosey_server's init sequence
   completes, but no actual 802.11 frames are transmitted or received. Proximity
   discovery via 802.11 scanning will not work. BLE-based discovery is unaffected.

2. **SELinux partial coverage**: `sepolicy.rule` provides the minimal rules for
   mosey_server to start. The full vendor CIL policy from Pixel 10 is not yet
   integrated. Some binder calls or capabilities may fail silently in enforcing
   mode.

3. **mosey_server binary source**: The binary must be sourced independently from
   a Pixel 10 vendor factory image. It is not redistributed in this module.

4. **BCM4389 wondertap**: No wonder.ko for kernel 5.10 or 5.15 exists in any
   public repo. This is the fundamental blocker for a native BCM4389 approach.
   The standalone `wonder_mosey_wild.ko` is the only viable workaround.

5. **Play Integrity**: Do not spoof `Build.DEVICE` or `Build.MODEL` to `blazer`
   (Pixel 10). TrickyStore + PlayIntegrityFork must remain intact.

6. **Non-Pixel devices**: Theoretically applicable to any Android phone with
   KSU/Magisk. The mosey_server binary and SELinux policy are Pixel-native;
   behavior on other OEM devices is untested and may require additional
   vendor policy adaptation.
