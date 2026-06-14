# Mosey (AWDL/AirDrop) Integration into Bada

> **Status**: Bridge architecture implemented; v1.27 completes raw mDNS advertising and IPv6 policy routing on `mosey0`.
> **Target**: Add Apple AirDrop / AWDL cross-platform discovery and transfer to Bada
>   using the OnePlus 15 GLO ROM's native `mosey_server` binary.
> **Approach**: **mosey_bridge controls AWDL radio; Bada implements AirDrop HTTPS and DVZIP separately**
>   (bypass GMS/MoseyApp entirely for discovery, reuse Bada's existing transfer layer).
> **Key Insight (v2)**: `mosey_server` **only** handles AWDL radio discovery. File transfer
>   goes through **standard Quick Share TCP** (same protocol Bada already implements).
>   Therefore Bada needs only to extract Apple device IPs from mosey callbacks, then
>   reuse its existing WIFI_LAN transfer layer.
> **Date**: 2026-06-13

> **Runtime update (v1.27)**: Bada uses `MoseySocketClient` and the root bridge as
> the primary control path. The bridge selects direct Binder or the preload UNIX
> proxy. The shim obtains the real Android Wi-Fi country code and owns radio
> startup. True-device kretprobe logging showed `policy_mgr_allow_concurrency=0`
> while `hdd_start_adapter=0`; decompiled MoseyApp confirms that the stock client
> disconnects an overlapping primary Wi-Fi STA before `start()`. The v1.25 shim
> now performs that disconnect-and-wait sequence and reconnects Wi-Fi on startup
> failure or service shutdown. Android `NetworkAgent` registration is not used:
> `NETWORK_FACTORY` is signature/role-only on this ROM and cannot be granted to
> the module-signed shim. Discovery now binds an IPv6 mDNS socket directly to
> `mosey0`, parsing and advertising `_airdrop._tcp.local` PTR/SRV/TXT/AAAA data.
> Receiver advertising matches the decompiled app: a rotating 12-hex service
> name and TXT `flags=489`. KernelSU dynamically installs source/oif IPv6 rules
> for the route table created with `mosey0`, and the TLS server binds directly
> to its scoped link-local address so Apple can reach `/Discover`.
>
> **Protocol correction (v1.28)**: `mosey_server` is a radio-control service,
> not a file transport. Quick Share UKEY2 frames are not valid AirDrop traffic.
> Bada therefore treats a resolved `_airdrop._tcp` endpoint as its own route and
> will implement `/Discover`, `/Ask`, and `application/x-dvzip` `/Upload` over
> HTTPS. Until the Bada receiver owns those endpoints, the shim advertises and
> answers `/Discover` but returns 503 for `/Ask` and `/Upload` instead of the
> previous misleading empty success response.
>
> **Transfer implementation (v1.29)**: the shim now streams `/Ask` and
> `/Upload` to Bada's loopback-only receiver on port 19541 while retaining
> TLS and AWDL interface ownership. Bada parses and emits binary plist,
> creates/reads old-ASCII CPIO archives for `application/x-dvzip`, asks for
> user consent through a high-priority notification, rejects traversal paths,
> and writes accepted files through the existing Downloads/MediaStore/SAF
> abstraction. The sender follows Discover -> Ask -> Upload and never enters
> the Quick Share UKEY2 state machine for an AirDrop route.

---

## 1. Problem Statement

Bada currently interops with **Quick Share / Nearby Share** over Wi-Fi LAN and Wi-Fi Direct.
Apple AirDrop peers (macOS, iPhone, iPad) speak a different protocol — **AWDL**
(Apple Wireless Direct Link) over 802.11 — and are invisible to Bada today.

The OnePlus 15 Global (GLO) ROM ships a complete AirDrop stack that translates
between Quick Share and AWDL at the kernel radio level. This stack comprises
a native Rust binary (`mosey_server`), a Google-signed APK (`MoseyApp`), and a
kernel-mode WiFi chipset driver (`wonder.ko`). All three exist in the GLO ROM
and are **absent** (or inactive) in the China (CN) ROM build for the same hardware.

**Goal**: Make Bada talk to Apple AirDrop peers by directly controlling the
`mosey_server` native binary — without depending on Google Play Services or
the MoseyApp APK for the protocol bridge.

### 1.1. Key Architecture Insight (v2 — from MoseyApp/GMS decompilation)

After decompiling MoseyApp (`bgd.java`, `bkp.java`, `biq.java`, `boj.java`)
and GMS (`duun.java`, `dxro.java`, `dxsg.java`), the true architecture is:

```
iPhone ──AWDL 射频──→ wonder.ko ──nl80211──→ mosey_server (Rust)
                                               │
                                               │ Binder callback (TR_CODE 1)
                                               ▼
                    MoseyApp (ExternalSharingService)
                     │  bgd 控制器解析回调 →
                     │  提取 Apple 设备信息 (IP地址、设备名等)
                     │
                     │  AIDL IExternalSharingProvider.send(params)
                     ▼
                    GMS (NearbySharingChimeraService)
                     │  收到 ShareTarget →
                     │  启动标准 Quick Share TCP 传输
                     │  ===== 同 Bada 已实现的协议 =====
                     ▼
                    iPhone ←──TCP 加密传输──→ GMS
```

**mosey_server 不负责文件传输**。它只做 AWDL 射频发现。
文件传输由 GMS 的标准 **Quick Share TCP** 层完成——这是 Bada 已经完整实现的协议。
因此 Bada 只需要从 mosey_server 的 Binder 回调中拿到 Apple 设备的 IP 地址，
就可以复用现有的 `WIFI_LAN` 传输层来完成文件收发。

---

## 2. Architecture Overview

### 2.1. OnePlus 15 AirDrop Stack

```
User space:
┌──────────────────────────────────────────────┐
│  MoseyApp (com.google.android.mosey)         │
│  ┌────────────────────────────────────────┐  │
│  │ ExternalSharingService                 │  │
│  │  → GMS IExternalSharingProvider AIDL   │  │
│  │  → bgd: send/receive provider ctrl     │  │
│  └──────────────┬─────────────────────────┘  │
└─────────────────┼────────────────────────────┘
                  │ NDK Binder (AIDL)
                  │ "com.google.android.moseyservice.IMoseyService/default"
┌─────────────────┼────────────────────────────┐
│  mosey_server   ▼  (Rust, /odm/bin/)         │
│  ┌────────────────────────────────────────┐  │
│  │ AServiceManager_addService(...)        │  │
│  │ dlopen("libmosey_daemon_ffi.so")       │  │
│  │  → mosey_start_4()                     │  │
│  │    ├─ PF_PACKET socket (raw 802.11)    │  │
│  │    ├─ NL80211 → cfg80211 → wonder.ko   │  │
│  │    └─ AWDL frame I/O                   │  │
│  └──────────────┬─────────────────────────┘  │
└─────────────────┼────────────────────────────┘
                  │ nl80211 + vendor commands
Kernel:           ▼
┌──────────────────────────────────────────────┐
│  wonder.ko (Qualcomm WiFi chipset driver)    │
│  → wonder0 NAN/monitor virtual interface     │
│  → AWDL 802.11 frame tx/rx                  │
└──────────────────────────────────────────────┘
```

### 2.2. Bada Integration Target (v2 — Bridge Architecture)

**Problem discovered during debugging**: `mosey_server` registers its Binder service on
**`/dev/vndbinder`** (VINTF vendor context manager, PID 1125 `vndservicemanager`), NOT on
the default `/dev/binder` that `ServiceManager.getService()` queries.

```
# Verified on device:
$ su -c "service list --binder /dev/vndbinder | grep mosey"
147 com.google.android.moseyservice.IMoseyService/default

$ su -c "service list | grep mosey"
(empty — NOT in default binder)
```

Android's `untrusted_app` (Bada's domain) **cannot open `/dev/vndbinder`** even with
SELinux rules. Therefore a direct Binder approach is impossible without modifying the
Android framework.

**Solution**: A native C bridge (`mosey_bridge`) running in KSU's `ksu` domain that:
1. Opens `/dev/vndbinder` (root has access)
2. Calls `AIBinder_getService()` to get mosey_server's Binder proxy
3. Exposes a **TCP loopback socket** (`127.0.0.1:19539`) to Bada
4. Forwards Bada's commands to mosey_server and relays callback events back

```
┌─ Bada (untrusted_app, 零 root) ────────────────────┐
│  discovery-android                                  │
│  ┌──────────────────────────────────────────────┐   │
│  │ MoseyMediumProvider                          │   │
│  │  → MediumProvider impl                       │   │
│  │  → MoseySocketClient (TCP → bridge)          │   │
│  │  → 解析回调 → 提取 Apple 设备 IP + 名称       │   │
│  │  → 注入为 Bada ShareTarget                    │   │
│  └─────────────────────┬────────────────────────┘   │
└────────────────────────┼────────────────────────────┘
                         │ TCP 127.0.0.1:19539
                         │ (loopback, 无需权限)
┌─ mosey_bridge (ksu) ──┴───────────────────────────┐
│  C 程序 (NDK 编译)                                   │
│  ┌──────────────────────────────────────────────┐  │
│  │ → open /dev/vndbinder                        │  │
│  │ → AIBinder_getService(mosey_service)         │  │
│  │ → listen TCP :19539                          │  │
│  │ → 转发命令/回复/回调事件                        │  │
│  └─────────────────────┬────────────────────────┘  │
└────────────────────────┼────────────────────────────┘
                         │ NDK Binder
                         │ (同进程内 vndbinder)
┌─ mosey_server (ksu) ──┴───────────────────────────┐
│  Rust 守护进程                                       │
│  AWDL 射频发现 (PF_PACKET + NL80211 + wonder.ko)   │
└────────────────────────────────────────────────────┘
```

### 2.3. Actual File Transfer Flow (Standard Quick Share TCP)

**Key finding from decompilation**: No file data passes through mosey_server.
The file transfer uses the same Quick Share protocol Bada already implements.

```
Phase 1: AWDL Discovery (mosey_server)
  iPhone ──AWDL beacon──→ wonder.ko ──NL80211──→ mosey_server
                                                    │
                                                    │ Binder callback:
                                                    │ { deviceName, ipv4, ipv6, mac, ... }
                                                    ▼
                                                  mosey_bridge
                                                    │ TCP relay
                                                    ▼
                                                  Bada

Phase 2: Quick Share Transport (Bada 现有能力)
  Bada  ←── TCP 44378 (加密) ──→ iPhone
        ←── 标准 Quick Share 帧 ──→
        ←── 文件数据 (protobuf 分段) ──→

  Bada 不需要修改传输层！只需要把 Apple 设备的 IP 地址
  包装成一个普通的 WIFI_LAN ShareTarget。
```

**证据**: GMS 的 `dxro` 接口 (`ExternalSharingProvider`):
```java
// GMS → MoseyApp: send a file to an Apple device
int g(String str, ShareTarget shareTarget, dtsb dtsbVar, dxrn dxrnVar);

// The ShareTarget contains the IP address (from mosey callback)
// GMS then uses its standard Nearby Connections TCP to transfer
```

而 Bada 的 `WifiLanTransferProvider` 已经实现了完全相同的逻辑。
区别只在于 Apple 设备是通过 AWDL 发现的，而非 mDNS。

---

## 3. Reverse-Engineered NDK Binder Interface

The native `mosey_server` registers a Binder service with four transaction codes.
All data below was recovered from `jadx` decompilation of `MoseyApp.apk` v13120.

### 3.1. Service Identity

| Property | Value |
|----------|-------|
| Service name | `com.google.android.moseyservice.IMoseyService/default` |
| Java wrapper prefix | `"A"` (OnePlus variant; Pixel uses `"P"`) |
| Version string | `"A20250923"` (compile date of the Rust binary) |
| Binder type | NDK Binder (`libbinder_ndk.so`), VINTF-stable |

### 3.2. Transaction Codes

| TR_CODE | Hex | Method | Param type | Returns | Description |
|---------|-----|--------|-----------|---------|-------------|
| `16777215` | `0xFFFFFF` | `getVersion()` | (none) | `int` | Query native version |
| `1` | `0x01` | `start(bps)` | `bps` | `void` | Start AWDL discovery + register callback |
| `2` | `0x02` | `stop(bpt)` | `bpt` (empty) | `void` | Stop discovery |
| `3` | `0x03` | `update(bpu)` | `bpu` | `void` | Update config (country code) |

### 3.3. Parameter Structures

**bps** (start params) — corresponds to Pixel's `cfe`:

```
Parcel layout:
  int[]   a   → medium filter bitmask array
  IBinder b   → callback Binder (for discovery events)
  int     c   → stability flag (typically Integer.MAX_VALUE)
```

**bpt** (stop params) — corresponds to Pixel's `cff`:

```
Parcel layout:
  (empty — just writes the interface token header)
```

**bpu** (update params) — corresponds to Pixel's `cfg`:

```
Parcel layout:
  String  a   → country code (e.g. "US", "CN", "JP")
```

### 3.4. Binder Proxy Transaction Template

```
// All transactions start with the interface token header:
Parcel obtain = Parcel.obtain();
obtain.writeInterfaceToken("com.google.android.moseyservice.IMoseyService");

// TR_CODE specific payload follows (see 3.3 above)

// Reply is always void for TR_CODE 1/2/3.
// TR_CODE 0xFFFFFF reads back: int version = reply.readInt();
```

---

## 4. Dependencies

### 4.1. Runtime Requirements

| Dependency | Source | Purpose |
|-----------|--------|---------|
| `mosey_server` binary | OnePlus 15 GLO ROM `/odm/bin/mosey_server` | Rust native daemon |
| `libmosey_daemon_ffi.so` | GLO ROM `/odm/lib64/libmosey_daemon_ffi.so` | dlopen'd by mosey_server |
| `wonder.ko` kernel module | Present in **both** GLO and CN ROM (`vendor_dlkm`) | Qualcomm chipset AWDL support |
| Root access | KernelSU / Magisk / APatch | Binder access + file mount |
| VINTF manifest | `manifest_mosey.xml` (from GLO) | AIDL HAL registration |

### 4.2. KernelSU Module (`module_mosey`)

The existing KSU module at `module_mosey/` already delivers all required
binaries. The `mosey-extended/` module is a sibling variant. Either can be
used as the runtime dependency for Bada.

**Critical files injected by the module:**

| Mount target | File |
|-------------|------|
| `/odm/bin/mosey_server` | Native AirDrop daemon |
| `/odm/lib64/libmosey_daemon_ffi.so` | Native FFI library |
| `/odm/etc/init/mosey.rc` | Service definition (disabled) |
| `/odm/etc/vintf/manifest/manifest_mosey.xml` | AIDL HAL manifest |

### 4.3. SELinux

The CN ROM already has the following SELinux contexts defined:

```
# vendor_service_contexts (both ROMs):
com.google.android.moseyservice.IMoseyService/default  u:object_r:mosey_service:s0

# system_ext_sepolicy.cil (both ROMs):
(typeattribute mosey_app)
```

**Critical finding**: `mosey_server` runs as `u:r:ksu:s0` (started from KSU service.sh,
not from init). Its Binder service is registered on **`/dev/vndbinder`**.
`untrusted_app` (Bada) **cannot** open `/dev/vndbinder`, period — SELinux rules
cannot circumvent this kernel-level restriction.

**Solution**: Bada communicates with `mosey_bridge` over TCP loopback, which
requires zero SELinux changes. Only `mosey_bridge` (running as `ksu` domain)
opens `/dev/vndbinder`.

Existing sepolicy.rule for `mosey_bridge` (runs as `ksu` domain, already allowed):
```
# (already in module_mosey/sepolicy.rule)
allow ksu mosey_service service_manager { add find }
allow ksu mosey_service binder { call transfer }
allow ksu servicemanager binder { call transfer }
```

---

## 5. Implementation Plan (v2 — Bridge Architecture)

### Phase 0: Prerequisites (KSU Module + Binary Verification)

Before any Bada code change, verify the runtime environment:

- [ ] OnePlus 15 with unlocked bootloader + KernelSU
- [ ] Install `module_mosey` — verify files at
      `/odm/bin/mosey_server`, `/odm/lib64/libmosey_daemon_ffi.so`
- [ ] Verify `wonder.ko` is loadable: `lsmod | grep wonder`
- [ ] Test mosey_server launches: `su -c "mosey_server"`, check `service list --binder /dev/vndbinder | grep mosey`
- [ ] Verify TR_CODE 0xFFFFFF from root: `su -c "service call --binder /dev/vndbinder com.google.android.moseyservice.IMoseyService/default 16777215"`

### Phase 0.5: mosey_bridge (NEW — Native C Bridge Binary)

**File**: `module_mosey/odm/bin/mosey_bridge` (C source TBD — or Rust)

Build a native binary that:
1. Opens `/dev/vndbinder` and connects to mosey_server via `AIBinder_getService()`
2. Listens on TCP `127.0.0.1:19539` for Bada connections
3. Implements a simple binary protocol:

```
Binary frame protocol:
  [type: u8][payload_len: u32][payload...]

Request frames (Bada → bridge, type=0x01):
  [cmd: u8][params...]
  Commands:
    0 = getVersion()               → reply: [i32 version]
    1 = start(filters, callback)   → reply: [i32 status]
    2 = stop()                     → reply: [i32 status]
    3 = update(countryCode)        → reply: [i32 status]

Reply frames (bridge → Bada, type=0x02):
  [status: i32][data...]

Event frames (bridge → Bada, type=0x03):
  Forward discovery callbacks from mosey_server to Bada
  [event_type: u8][payload...]
  Events: device_discovered, device_lost, ...
```

Integration: add to `module_mosey/service.sh` to launch after mosey_server:
```bash
# Start mosey_bridge after mosey_server is up
sleep 2
/odm/bin/mosey_bridge &
```

### Phase 1: Bada Socket Client + MediumProvider

#### 1.1. Replace MoseyBinderClient with MoseySocketClient

**File**: `discovery-android/src/main/kotlin/.../medium/MoseySocketClient.kt`

Instead of calling ServiceManager directly, connect to the bridge via TCP:

```kotlin
class MoseySocketClient {
    private val host = "127.0.0.1"
    private val port = 19539
    
    fun connect(): Boolean  // TCP connect to bridge
    fun isAlive(): Boolean  // getVersion() via TCP
    fun start(filters: IntArray)  // send start command
    fun stop()  // send stop command
    fun update(countryCode: String)  // send update command
    fun setCallback(handler: MoseyEventHandler)  // register event handler
}
```

The Binder transact code (already reverse-engineered) stays the same —
it's just the transport that changes from Binder IPC to TCP.

#### 1.2. Create MoseyMediumProvider (revised)

**File**: `discovery-android/src/main/kotlin/.../medium/MoseyMediumProvider.kt`

Use `MoseySocketClient` instead of `MoseyBinderClient`:

```kotlin
class MoseyMediumProvider(context: Context) : MediumProvider {
    override val medium = Medium.MOSEY
    private val socketClient = MoseySocketClient()
    
    override fun isSupported() = socketClient.connect() && socketClient.isAlive()
    
    override suspend fun prepareUpgrade(): UpgradePathCredentials? {
        socketClient.start(intArrayOf(13))
        // Start receiving callback events via TCP
        socketClient.setCallback { event ->
            when (event.type) {
                DEVICE_DISCOVERED -> {
                    // event contains Apple device IP + name
                    // Inject into Bada's discovery flow as a WIFI_LAN peer
                    discoverAppleDevice(event.ipAddress, event.deviceName)
                }
            }
        }
        return UpgradePathCredentials.Mosey("US")
    }
    
    override suspend fun adoptUpgrade(creds: UpgradePathCredentials): UpgradedTransport? {
        socketClient.update(countryCode)
        return MoseyTransport(creds)
    }
    
    override fun cancelPendingUpgrade() { socketClient.stop() }
}
```

#### 1.3. Add Medium.MOSEY enum value, MediumLadder, Credentials, Transport, Registries

(same as v1, already partially implemented)

### Phase 2: Callback Event Parsing (Key Difference from v1)

With the bridge handling Binder IPC, Bada receives **TCP frames** instead of
raw Binder transactions. This is much easier to debug and parse.

The callback events from mosey_server (forwarded through bridge) contain:
- Device endpoint ID (hash)
- Device name (NSString from Apple's AWDL TLV)
- IPv4 / IPv6 address (for Quick Share TCP)
- MAC address (for WiFi Direct fallback)
- Signal strength / proximity
- Service type / medium filter info

**Parsing strategy**: 
1. Connect bridge and capture raw event payloads
2. Compare with `bkp.java` / `biq.java` logic to decode fields
3. Extract IP address → create Bada ShareTarget → start Quick Share transfer

### Phase 3: Transfer Integration (Revised — Reuse Existing Layer)

**Key insight from decompilation**: File transfer does NOT go through mosey.
Apple devices support standard Quick Share TCP on **port 44378** (already
implemented by Bada's `WifiLanTransferProvider`).

```
When mosey callback reports an Apple device:
  { ipAddress: "192.168.1.42", deviceName: "iPhone 15" }

Bada simply:
  val target = ShareTarget(
      deviceName = "iPhone 15 (AWDL)",
      ipAddress = "192.168.1.42",
      medium = WIFI_LAN  // ← existing transport!
  )
  // → Bada's existing WifiLanTransferProvider handles everything
```

No new transport code needed — only a thin adapter that converts mosey
discovery events into Bada's existing `ShareTarget` model.

### Phase 4: Integration

- Register `MoseyMediumProvider` in `MediumRegistries`
- In receiver mode: start AWDL advertising → wait for callback → parse device info
- In sender mode: start AWDL discovery → receive events → display Apple devices in UI
- On transfer: user selects device → Bada uses WIFI_LAN transfer (existing code)

---

## 6. Risks & Unknowns (v2)

| Risk | Impact | Mitigation |
|------|--------|-----------|
| **mosey_server callback format unknown** | Can parse callbacks but fields unclear | Capture raw bridge frames, reverse-engineer with MoseyApp bkp/biq as reference |
| **Apple Quick Share TCP compatibility** | Apple may use modified Quick Share variant | Test actual file transfer; capture GMS→Apple traffic for comparison |
| **mosey_bridge stability** | Bridge crash = no discovery | Simple C code, watchdog restart in service.sh |
| **AWDL channel contention** | Interference with normal WiFi | mosey_server handles channel switching; monitor with logcat |
| **mosey_server without MoseyApp** | Some Binder calls may depend on MoseyApp state | Test each TR_CODE independently; start() without registered callback receiver |
| **Country code mismatch** | AWDL on wrong channel bans | Default to "US" 5GHz, fallback to "CN" 5.8GHz |

---

## 7. Test Plan (v2)

### 7.1. Bridge Connectivity Test

```bash
# From device shell:
$ su -c "/odm/bin/mosey_server &"
$ su -c "/odm/bin/mosey_bridge &"
$ echo -n -e '\\x00\\x00\\x00\\x00\\x00' | nc 127.0.0.1 19539
# Expected: reply with version code
```

### 7.2. Start/Stop Discovery Test

```bash
# Start AWDL advertising for 10 seconds, then stop
# Check bridge logs for callback events
$ logcat | grep MoseyBridge
```

### 7.3. End-to-End Discovery + Transfer Test

Requires:
- OnePlus 15 CN ROM with module_mosey + mosey_bridge
- Apple device (MacBook / iPhone) with AirDrop enabled nearby

Steps:
1. Start Bada → MoseyMediumProvider.prepareUpgrade()
2. Apple device opens AirDrop → sees "OnePlus 15" via AWDL ✅
3. Bada receives callback → extracts Apple device IP
4. Apple device shares file → Bada receives over Quick Share TCP ✅
5. (Reverse) Bada sends file → Apple receives over AirDrop ✅

### 7.4. Device Matrix

| Device | Role | Expected Outcome |
|--------|------|------------------|
| OnePlus 15 CN + module_mosey | Bada (AWDL disc.) | Advertises on AWDL, receives callbacks |
| MacBook (macOS Sequoia) | AirDrop sender | Discovers OnePlus, sends file via Quick Share |
| iPhone (iOS 19+) | AirDrop sender | Same as MacBook |
| OnePlus 15 GLO (stock) | Reference | For comparison, captures GMS traffic |

---

## 8. Development Roadmap (v2)

```
Phase 0: Environment Setup
├── [✓] Verify wonder.ko loaded on CN ROM
├── [✓] Install module_mosey, verify mosey_server file presence
├── [✓] Manual start test: mosey → service list (vndbinder)
├── [ ] TR_CODE 0xFFFFFF test via root shell
└── [✓] Document vndbinder discovery and bridge requirement

Phase 0.5: mosey_bridge (Native Bridge Binary)
├── [ ] Write C bridge: vndbinder connect + AIBinder_getService
├── [ ] Implement TCP listener + binary protocol
├── [ ] Implement command forwarding (getVersion/start/stop/update)
├── [ ] Implement callback event relay
├── [ ] Cross-compile with NDK (aarch64-linux-android29)
├── [ ] Integrate into module_mosey/odm/bin/
├── [ ] Add to service.sh auto-launch after mosey_server
└── [ ] Deploy and test connectivity

Phase 1: Bada Binder-Protocol Extension
├── [✓] Add Medium.MOSEY enum (wire 13)
├── [✓] Update MediumLadder with MOSEY priority
├── [ ] Create MoseySocketClient (TCP to bridge)
├── [ ] Create MoseyMediumProvider (uses socket client)
├── [✓] Create MoseyTransport stub
├── [✓] Add UpgradePathCredentials.Mosey
└── [ ] Register in MediumRegistries

Phase 2: Callback Parsing
├── [ ] Capture raw bridge callback events
├── [ ] Decode AWDL device info (IP, name, MAC)
├── [ ] Compare with MoseyApp bkp.java/biq.java logic
└── [ ] Implement MoseyEventHandler → ShareTarget converter

Phase 3: Transfer Integration
├── [ ] Map discovered Apple device → Bada ShareTarget (WIFI_LAN)
├── [ ] Inject into Bada's peer discovery UI
├── [ ] Test Apple → Bada file send
├── [ ] Test Bada → Apple file send
└── [ ] End-to-end on device

Phase 4: Polish
├── [ ] Auto-start mosey_bridge with service.sh
├── [ ] Error handling & watchdog (restart on crash)
├── [ ] Power optimization (radio management)
├── [ ] Country code auto-detection (telephony)
└── [ ] Documentation and diagrams
```

---

## 9. References

| Document | Location |
|----------|----------|
| Full ROM research (GLO vs CN) | `/Users/feelliao/code/mosey-extended/MOSEY_RESEARCH.md` |
| Reverse engineering (AIDL, GMS) | `/Users/feelliao/code/mosey-extended/MOSEY_REVERSE_ENGINEERING.md` |
| Repository memory (technical notes) | `/Users/feelliao/code/mosey-extended/memory/repo/mosey-extended.md` |
| KSU module source | `/Users/feelliao/code/mosey-extended/module_mosey/` |
| Bada architecture | `docs/architecture.md` |
| Bada protocol spec | `https://github.com/grishka/NearDrop/blob/master/PROTOCOL.md` |
| Nearby proto wire formats | Vendored in `:core-protocol` protobuf definitions |
| MoseyApp decompile | `/Users/feelliao/code/mosey-extended/decompile/moseyapp/sources/` |
| GMS decompile (ExternalSharingProvider) | `/Users/feelliao/code/mosey-extended/decompile/gms/sources/` |

---

## Appendix A. Key Files Referenced

```
Bada files to modify:
├── core-protocol/src/main/kotlin/.../medium/Medium.kt          ← add MOSEY(13)
├── core-protocol/src/main/kotlin/.../medium/MediumLadder.kt    ← add MOSEY priority
├── core-protocol/src/main/kotlin/.../medium/UpgradePathCredentials.kt  ← add Mosey
├── core-protocol/src/main/kotlin/.../medium/UpgradedTransport.kt       ← add Mosey
├── discovery-android/src/main/kotlin/.../medium/MediumRegistries.kt    ← register
├── discovery-android/src/main/kotlin/.../medium/MoseyBinderClient.kt   ← NEW
└── discovery-android/src/main/kotlin/.../medium/MoseyMediumProvider.kt ← NEW

KSU module files (existing, no changes needed):
├── module_mosey/odm/bin/mosey_server
├── module_mosey/odm/lib64/libmosey_daemon_ffi.so
├── module_mosey/odm/etc/init/mosey.rc
├── module_mosey/odm/etc/vintf/manifest/manifest_mosey.xml
├── module_mosey/system_ext/priv-app/MoseyApp/MoseyApp.apk
└── module_mosey/tools/sqlite3_mosey

Reverse engineering reference files:
├── decompile/moseyapp/sources/defpackage/bpq.java   ← Binder proxy
├── decompile/moseyapp/sources/defpackage/bpr.java   ← AIDL interface
├── decompile/moseyapp/sources/defpackage/bps.java   ← start params
├── decompile/moseyapp/sources/defpackage/bpt.java   ← stop params
├── decompile/moseyapp/sources/defpackage/bpu.java   ← update params
├── decompile/moseyapp/sources/defpackage/bmz.java   ← OnePlus wrapper
├── decompile/moseyapp/sources/defpackage/bnc.java   ← Java wrapper interface
├── decompile/moseyapp/sources/defpackage/bgd.java   ← controller
├── decompile/moseyapp/sources/defpackage/bkp.java   ← SendProvider
└── decompile/moseyapp/sources/defpackage/biq.java   ← ReceiveProvider
```

---

## Appendix B. Glossary

| Term | Definition |
|------|-----------|
| AWDL | Apple Wireless Direct Link — Apple's proprietary Wi-Fi peer-to-peer protocol |
| mosey | Google's codename for "Quick Share over AWDL" (AirDrop interop) |
| MoseyApp | `com.google.android.mosey` — Google system app bridging Quick Share ↔ AWDL |
| mosey_server | Rust native daemon that implements AWDL radio control |
| Binder TR_CODE | Transaction code — numeric identifier for Binder RPC method |
| VINTF | Vendor Interface — Android HAL registration framework |
| KSU | KernelSU — kernel-level root solution for Android |
| NAN | Neighbor Awareness Networking — Wi-Fi Aware standard |
| NL80211 | Netlink protocol for Linux wireless stack configuration |
| wonder.ko | Qualcomm WiFi chipset kernel module with NAN/monitor mode support |
