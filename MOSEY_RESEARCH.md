# Mosey (AirDrop) Research — OnePlus 15 GLO vs CN ROM

> **Skill purpose**: Detailed reverse-engineering research on how the OnePlus 15 Global ROM
> implements AirDrop (mosey/Quick Share) and what components must be ported to enable it on
> the China ROM. This document is generated from actual ROM extraction and comparison,
> NOT from secondary sources.

---

## 1. ROM Overview

| Property | GLO (Global) | CN (China) |
|----------|-------------|------------|
| OTA version | CPH2747_11.A.40_0400_202605071113 | PLK110_11.A.63_0630_202605061316 |
| Android | 16 | 16 |
| Security patch | 2026-05-01 | 2026-05-01 |
| AirDrop | ✅ Supported | ❌ Not supported |
| OTA file | `glo.zip` (8.4 GB) | `cn.zip` (8.3 GB) |

## 2. OnePlus 15's AirDrop Architecture (Not Pixel's!)

Unlike Pixel's implementation described in `readme.md`, OnePlus 15 uses a **completely different
stack** designed by OPPO/OnePlus:

### Key Differences from Pixel

| Aspect | Pixel (readme.md) | OnePlus 15 (Actual) |
|--------|-------------------|---------------------|
| Binary location | `/vendor/bin/mosey_server` | `/odm/bin/mosey_server` |
| Init location | `/vendor/etc/init/mosey.rc` | `/odm/etc/init/mosey.rc` |
| Service name | `com.google.pixel.service.IService/default` | `com.google.android.moseyservice.IMoseyService/default` |
| Manifest location | N/A | `/odm/etc/vintf/manifest/manifest_mosey.xml` |
| Source path | Pixel internal | `vendor/oplus/hardware/interface/quick_share_extension/` |
| AIDL binding | NDK Binder (AIBinder_*) | NDK Binder + VINTF-stable |
| Native library | None (statically linked?) | `libmosey_daemon_ffi.so` (dlopen'd) |
| Kernel module | `wonder.ko` (separate) | Uses Qualcomm `wonder.ko` (built-in chipset driver) |
| Start behavior | Auto-start at boot | **Disabled** (started on-demand by MoseyApp) |
| Extra services | None | eSIM + LOWI (ODM-level) |

### Full Boot/Activation Sequence

```
Boot
 └─ init reads ODM VINTF manifest (manifest_mosey.xml)
     └─ registers com.google.android.moseyservice as a device HAL
     │
 └─ system_ext MoseyApp (com.google.android.mosey) starts
     ├─ Requests mosey_server via AIDL binder
     ├─ init: "start mosey_server" (on-demand, service is "disabled")
     ├─ mosey_server (Rust binary) starts
     │   ├─ dlopen("libmosey_daemon_ffi.so")
     │   ├─ calls mosey_start_4()
     │   │   ├─ Opens PF_PACKET socket (raw 802.11 frame I/O)
     │   │   ├─ Sends NL80211 commands to cfg80211 via wonder.ko
     │   │   ├─ Sets channel, frequency via vendor commands (vendor_id=0x1A11)
     │   │   └─ Registers "com.google.android.moseyservice.IMoseyService"
     │   └─ Enters Binder thread pool
     │
     └─ LOWI server also activates for Wi-Fi proximity detection
```

### SELinux Context Mapping (vendor_service_contexts)

Both GLO and CN have these mappings for forward-compatibility:

```
com.google.android.moseyservice.IMoseyService/default   u:object_r:mosey_service:s0
com.google.pixel.moseyservice.IMoseyService/default     u:object_r:mosey_service:s0
```

Both ROMs also define `mosey_app` domain in `system_ext_sepolicy.cil`. The SELinux
infrastructure is **already in place** in the CN ROM — the missing pieces are the
actual components.

## 3. File-Level Comparison Methodology

### Extraction Process

```
glo.zip / cn.zip
  └─ payload.bin (OTA payload v2)
       ├─ payload-dumper-go -l → list partitions
       ├─ payload-dumper-go -p system,system_ext,vendor,odm,product,my_product,my_stock,my_region
       │   └─ Extract EROFS images (.img)
       └─ payload-dumper-go -p vendor_dlkm
           └─ Ext2 image → kernel modules
```

### Partition Layout

| Partition | Filesystem | Size (GLO) | Key Contents |
|-----------|-----------|------|-------|
| `odm` | EROFS | 2.8 GB | mosey_server, libmosey_daemon_ffi.so, mosey.rc, manifest_mosey.xml, hardware services |
| `system_ext` | EROFS | 849 MB | MoseyApp APK, permission XMLs, SELinux CIL |
| `vendor` | EROFS | 441 MB | lowi-server, WiFi HAL, SELinux contexts |
| `system` | EROFS | 795 MB | Platform framework, SELinux mapping |
| `product` | EROFS | 6.4 MB | Product SELinux, overlay |
| `vendor_dlkm` | ext2/ext4 | 144 MB | Kernel modules (wonder.ko, cfg80211.ko, mac80211.ko, qca_cld3_*.ko) |

### Tools Used
- **payload-dumper-go**: `brew install payload-dumper-go` — extracts OTA payload partitions
- **fsck.erofs**: Built-in erofs-utils — `fsck.erofs --extract=DIR --overwrite IMG`
- **file/strings**: Binary analysis
- **diff**: File-level comparison between ROMs
- **Python + regex**: Raw image scanning for kernel modules

## 4. Detailed Component Analysis

### 4.1. mosey_server Binary

| Property | Value |
|----------|-------|
| Path | `/odm/bin/mosey_server` |
| Format | ELF 64-bit LSB, ARM aarch64, stripped |
| Interpreter | `/system/bin/linker64` |
| Language | Rust (Rust 1.83.0) |
| Compile date | 2025-09-23 version |
| Key libraries | `libbinder_ndk.so`, `liblog.so`, `libc.so`, `libdl.so` |
| Service registration | `AServiceManager_addService("com.google.android.moseyservice.IMoseyService")` |
| Marked VINTF-stable | Yes (`AIBinder_markVintfStability`) |
| Runtime library load | `dlopen("/odm/lib64/libmosey_daemon_ffi.so")` |
| Source path | `vendor/oplus/hardware/interface/quick_share_extension/services/src/server.rs` |

### 4.2. libmosey_daemon_ffi.so

| Property | Value |
|----------|-------|
| Path | `/odm/lib64/libmosey_daemon_ffi.so` |
| Format | ELF 64-bit LSB, ARM aarch64, stripped |
| Source | `//location/nearby/protocolx/mosey_daemon_ffi:libmosey_daemon_ffi.so` |
| Build path | `blaze-out/arm64-v8a-opt/bin/location/nearby/protocolx/mosey_daemon_ffi/libmosey_daemon_ffi.so` |
| Exported symbols | `mosey_start_4`, `mosey_stop`, `mosey_dump`, `mosey_reset` |
| Capabilities | 802.11 frame capture (pcap/libpcap integration) |

### 4.3. mosey.rc

```ini
service mosey_server /odm/bin/mosey_server
    interface aidl com.google.android.moseyservice.IMoseyService/default
    user system
    group system inet
    disabled
    capabilities NET_ADMIN NET_RAW
```

The service is **disabled** — it does NOT start at boot. It's started on-demand by
the MoseyApp via `property_set("ctl.start", "mosey_server")`.

### 4.4. VINTF Manifest (manifest_mosey.xml)

```xml
<manifest version="1.0" type="device">
    <hal format="aidl">
        <name>com.google.android.moseyservice</name>
        <version>1</version>
        <fqname>IMoseyService/default</fqname>
    </hal>
</manifest>
```

This registers the mosey AIDL HAL so that `servicemanager` and `vndservicemanager` know
about it. The VINTF manifest is read by init during boot.

### 4.5. MoseyApp APK

| Property | Value |
|----------|-------|
| Package name | `com.google.android.mosey` (from permission XMLs) |
| Location | `/system_ext/priv-app/MoseyApp/MoseyApp.apk` |
| Key assets | `assets/capabilities.json`, `assets/keystore.bks`, `assets/phenotype/*` |
| Signature | Google-signed (CERT.RSA) |

#### Required Privileged Permissions
| Permission | Purpose |
|-----------|---------|
| `BLUETOOTH_PRIVILEGED` | Bluetooth operations without user consent |
| `LOCAL_MAC_ADDRESS` | Read device MAC address |
| `MANAGE_WIFI_INTERFACES` | Create/configure WiFi interfaces (wonder0) |
| `NETWORK_FACTORY` | Create network agents |
| `CREATE_APP_SPECIFIC_NETWORK` | Use restricted networks |
| `CONNECTIVITY_USE_RESTRICTED_NETWORKS` | Access restricted network types |
| `LOCATION_HARDWARE` | Hardware-level location access |

#### Default Runtime Permissions
- `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`
- `NEARBY_WIFI_DEVICES`
- `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`

### 4.6. Kernel Modules (vendor_dlkm)

Both GLO and CN have IDENTICAL kernel module sets (329 modules each). Key WiFi modules:

| Module | Purpose |
|--------|---------|
| `cfg80211.ko` | Linux 802.11 configuration API |
| `mac80211.ko` | Linux 802.11 MAC layer |
| `wonder.ko` | Qualcomm WiFi chipset driver (NOT mosey-specific wonder PHY) |
| `qca_cld3_kiwi_v2.ko` | Qualcomm WLAN driver (Kiwi variant) |
| `qca_cld3_wcn7750.ko` | Qualcomm WLAN driver (WCN7750 chip) |
| `cnss_nl.ko`, `cnss_utils.ko` | Qualcomm connectivity subsystem |

**The kernel module is NOT the differentiator.** `wonder.ko` is a Qualcomm WiFi chipset
driver present in both ROMs. The mosey-specific "wonder phy" is handled entirely in
user-space by `mosey_server` through NL80211 commands.

### 4.7. SELinux Policies

#### vendor_service_contexts (both ROMs)
```
com.google.android.moseyservice.IMoseyService/default   u:object_r:mosey_service:s0
com.google.pixel.moseyservice.IMoseyService/default     u:object_r:mosey_service:s0
```

#### precompiled_service_contexts (ODM, GLO-only)
```
com.google.android.moseyservice.IMoseyService/default   u:object_r:mosey_service:s0
com.google.pixel.moseyservice.IMoseyService/default     u:object_r:mosey_service:s0
```

#### system_ext_sepolicy.cil (both ROMs)
Defines `mosey_app` type, role assignment, and allow rules. Both ROMs include
basic `mosey_app` domain definitions.

#### product_sepolicy.cil (GLO-only additional mosey rules)
Contains additional typeattribute and domain rules for `mosey_app` integration.

### 4.8. LOWI (Location Over Wi-Fi)

| File | Description | GLO | CN |
|------|-------------|:---:|:---:|
| `vendor/bin/lowi-server` | LOWI daemon binary | ✅ | ✅ |
| `vendor/etc/init/lowi-server.rc` | LOWI init (disabled) | ✅ | ✅ |
| `vendor/lib64/liblowi_*.so` | LOWI libraries | ✅ | ✅ |
| **`odm/etc/init/lowi-server.rc`** | **ODM-level LOWI activation** | **✅** | **❌** |

Both ROMs have the Qualcomm LOWI stack in vendor, but only GLO adds an ODM-level
init that activates LOWI for Wi-Fi proximity detection.

### 4.9. eSIM Service (GLO-only)
- `/odm/etc/init/esim@1.0-service.rc`
- `/odm/lib64/vendor.oplus.hardware.esim-V1-ndk.so`

The eSIM service is present only in GLO. It may be related to AirDrop's device
identification or multi-device linking capability.

## 5. Complete Difference Summary

### Files Present ONLY in GLO

```
ODM partition:
├── bin/mosey_server                          ← Native AirDrop service binary
├── lib64/libmosey_daemon_ffi.so              ← AirDrop native library (dlopen'd)
├── lib64/vendor.oplus.hardware.esim-V1-ndk.so ← eSIM NDK library
├── etc/init/mosey.rc                         ← AirDrop service init definition
├── etc/init/esim@1.0-service.rc              ← eSIM service init
├── etc/init/lowi-server.rc                   ← ODM LOWI activation
└── etc/vintf/manifest/manifest_mosey.xml     ← AIDL HAL VINTF manifest

system_ext partition:
├── priv-app/MoseyApp/MoseyApp.apk            ← AirDrop system application
├── etc/permissions/privapp-permissions-com.google.android.mosey.xml  ← Priv permissions
└── etc/default-permissions/default-permissions-com.google.android.mosey.xml  ← Default perms
```

### Files Present ONLY in CN

```
ODM partition:
├── lib64/libCNamaSDK_vendor.so               ← Chinese AR SDK
├── lib64/libNamaWrapper.so                   ← Face retouching
├── lib64/libfuai_vendor.so                   ← AI library
├── lib64/libiccapis.so                       ← ICC API
├── lib64/vendor.oplus.hardware.eid-V1-ndk.so ← China eID library
├── etc/init/vendor.oplus.hardware.eid@1.0-service.rc  ← eID service

system_ext partition:
├── priv-app/OppoPackageInstaller             ← Different package installer
├── etc/permissions/privapp-permissions-oplus-domestic.xml
├── etc/init/tango-binfmt.rc                  ← Tango debug runtime
└── etc/init/tango-debug.rc
```

## 6. Porting Strategy

### Minimal Port (KSU/Magisk Module)

To enable AirDrop on CN ROM, the following files must be injected:

#### ODM overlay
| File | Source | Notes |
|------|--------|-------|
| `system/vendor/bin/mosey_server` | From GLO ODM | ELF binary, no changes needed |
| `system/vendor/lib64/libmosey_daemon_ffi.so` | From GLO ODM | Shared library |
| `system/vendor/etc/init/mosey.rc` | From GLO ODM | Service definition |
| `system/vendor/etc/vintf/manifest/manifest_mosey.xml` | From GLO ODM | AIDL HAL declaration |

#### system_ext overlay
| File | Source | Notes |
|------|--------|-------|
| `system/system_ext/priv-app/MoseyApp/MoseyApp.apk` | From GLO system_ext | Google-signed |
| `system/system_ext/etc/permissions/privapp-permissions-com.google.android.mosey.xml` | From GLO | Priv-app grant |
| `system/system_ext/etc/default-permissions/default-permissions-com.google.android.mosey.xml` | From GLO | Runtime permission grant |

#### Discussion Items
1. **eSIM dependency**: Determine if eSIM service is strictly required or optional for AirDrop.
2. **LOWI activation**: ODM-level `lowi-server.rc` may be needed for Wi-Fi proximity scanning.
3. **SELinux**: Both ROMs already have `mosey_app` domain and `mosey_service` context mapping.
   Additional ODM CIL rules may be needed in `precompiled_service_contexts`.
4. **VINTF manifest**: The system must recognize the new HAL — this may require
   `compatibility_matrix.device.xml` update.

## 7. Binary Integrity Verification

After extraction, verify files have not been corrupted:

```bash
# mosey_server
md5 analysis/fs/glo/odm/bin/mosey_server
# Expected: 9d6b645e798b5b40a86f04e91b703a29

# libmosey_daemon_ffi.so
md5 analysis/fs/glo/odm/lib64/libmosey_daemon_ffi.so
# Expected: 8f3e43cded5aec625ccc836282d44905
```

## 8. Future Research Directions

1. Decode `libmosey_daemon_ffi.so` further to understand specific NL80211 vendor commands.
2. Determine if `MoseyApp` communicates with Google servers or works fully offline.
3. Test mosey_server on CN ROM via direct injection to verify SELinux compatibility.
4. Investigate whether LOWI ODM activation is strictly required or can be bypassed.
5. Check Google Play Services integration requirements (GMS certification for Quick Share).

## 9. Appendix: ROM Extraction Quick Reference

```bash
# Prerequisites
brew install payload-dumper-go

# Extract partition list
payload-dumper-go -l payload.bin

# Extract specific partitions
payload-dumper-go -p system,system_ext,vendor,odm,product,my_product,my_stock,my_region \
    -o output_dir payload.bin

# Extract EROFS image
fsck.erofs --extract=target_dir --overwrite partition.img

# Extract vendor_dlkm (ext2/4)
mkdir mount_point
sudo mount -o loop,ro vendor_dlkm.img mount_point
# Or use debugfs / fuse2fs for non-root access
```
