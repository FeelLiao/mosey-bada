# Mosey 反向工程 & AIDL 接口文档

> **目标**: 将 Google Mosey (AirDrop 等效协议) 集成到 Bada (开源 Quick Share) 中
> **反编译对象**: MoseyApp APK `com.google.android.mosey` (版本 13120) + GMS `com.google.android.gms` (26.20.31)
> **来源**: MoseyApp: `/system_ext/priv-app/MoseyApp/MoseyApp.apk`; GMS: `/data/app/~~r7EdCUR4.../base.apk`

---

## 0. v3 关键发现 (2026-06-10)

通过 ADB 诊断 OnePlus 15 CN ROM 设备，确认以下关键事实：

### mosey_server 在默认 binder 上（非 vndbinder！）

**诊断证据**:
```bash
# mosey_server 进程的 binder fd → 默认 binder
$ ls -la /proc/3631/fd/3
lrwx------ 3 -> /dev/binderfs/binder    # ← 不是 /dev/binderfs/vndbinder！

# shell 用户 (无 root) 可直接调用 getVersion() 成功
$ service call com.google.android.moseyservice.IMoseyService/default 16777215
Result: Parcel(00000000 00000001)   # version = 1 ✅
```

**影响**: `MoseyBinderClient`（直接 NDK Binder 调用）可以直接工作，**无需 `mosey_bridge` TCP 桥接**。之前假设的 vndbinder 访问限制在此设备上不存在。

### Parcel 格式修正

`service call` 的 `start()` 和 `update()` 调用返回 `"Invalid argument"`。根因是 AIDL 生成的代理代码在序列化 `Parcelable` 参数前会写入 `hasValue=1` 标志：

```java
// AIDL 自动生成的代码 (bpq.java):
Parcel data = Parcel.obtain();
data.writeInterfaceToken(DESCRIPTOR);
// → bps Parcelable 包装:
data.writeInt(1);              // hasValue: non-null  ← 之前遗漏
data.writeIntArray(filters);   // int[]
data.writeStrongBinder(callback); // IBinder
data.writeInt(MAX_VALUE);      // stability
binder.transact(1, data, reply, 0);
```

修复：在 `MoseyBinderClient` 的三个方法中添加 `data.writeInt(1)` 作为 `hasValue` 前缀。

### 对 Bada 集成的意义

1. **不再需要 `mosey_bridge`** — `MoseyBinderClient` 可直接调用 mosey_server
2. **架构简化**: Bada → Binder → mosey_server，无需中间 TCP 桥接
3. **`mosey_bridge` C 代码保留**作为 SELinux 限制时的后备方案

---

## 0. v2 关键发现 (2026-06-08)

通过完整反编译 MoseyApp 和 GMS，确认以下架构事实：

### mosey_server 只做 AWDL 发现，不参与文件传输

- `mosey_server` (Rust) 只负责 AWDL 射频发现 (PF_PACKET + NL80211 + wonder.ko)
- 文件传输由 **GMS 的标准 Quick Share TCP** 完成 — Bada 已经完整实现此协议
- MoseyApp 的 `bgd` 控制器只解析回调中的设备信息，然后通过 AIDL 传给 GMS
- GMS 收到 `ShareTarget` (含 IP 地址) 后，用标准的 Nearby Connections TCP 传文件

### 关键证据

| 类 | 位置 | 证据 |
|----|------|------|
| `boj.java:435` | MoseyApp | 只调 `bnc.c(str)`=update 和 `bnc.b()`=stop，无文件数据 |
| `bog.java` | MoseyApp | `start()` 只传 `int[]`+`IBinder` callback — 无文件数据 |
| `bgd.java` | MoseyApp | `d(str, agy, ahh)`=send 委托 `bkp` 处理文件传输 |
| `bkp.java` | MoseyApp | SendProvider 处理发送，使用 mDNS/NSD + TCP socket |
| `dxro.java` (GMS) | GMS | `g(String, ShareTarget, ...)` = GMS 的 send 接口 |
| `duun.java` (GMS) | GMS | 14 个 AIDL 方法，都是控制信令，无原生 Binder 文件传输 |

### 对 Bada 集成的影响

Bada 只需要从 mosey_server 回调中提取 **Apple 设备的 IP 地址**，然后：
1. 包装为标准的 WIFI_LAN `ShareTarget`
2. 使用 Bada 已有的 `WifiLanTransferProvider` 传输文件
3. 无需新传输层代码

### 技术障碍

`mosey_server` 的 Binder 注册在 **`/dev/vndbinder`** (VINTF vendor 上下文管理器)，
`untrusted_app` 无法访问。需要用 `mosey_bridge` (KSU root 域 native 程序) 桥接。

---

## 1. 架构总览

```
┌─────────────────────────────────────────────────────────────────┐
│  GMS (Google Play Services) 26.20.31                           │
│  ┌──────────────────────────────────────────────────────┐      │
│  │ NearbySharingChimeraService                           │      │
│  │  ├── W(): 管理 ExternalProviderRegistry 生命周期    │      │
│  │  │   └── 触发条件: jsdy.t() [enableExternalProviders]│      │
│  │  ├── dtlp: 服务处理器 (duuy 子类)                    │      │
│  │  └── dury (FlagSnapshot): 读取 aconfig 标志          │      │
│  │                                                              │
│  │  ExternalProviderRegistry (dttd)                            │
│  │  ├── dzms: 状态机 → queryIntentServices(SHARING_PROVIDER)  │
│  │  ├── dzlx: 绑定逻辑 → eaac.a() 白名单检查                  │
│  │  └── duun (IExternalSharingProvider AIDL)                    │
│  │      └── 14 methods: accept/deny/send/discover/advertise... │
│  └────────────────────┬──────────────────────────────────┘      │
└───────────────────────┼──────────────────────────────────────────┘
                        │
┌───────────────────────┼─────────────────────────────────────┐
│                ┌──────┴──────┐                               │
│                │ MoseyApp    │  (Java/Kotlin APK)            │
│                │ ExternalSharingService                      │
│                │  - agt (base service)                       │
│                │  - bgd (controller)                         │
│                │    ├── bkp (SendProvider, 发送发现)        │
│                │    └── biq (ReceiveProvider, 接收发现)     │
│                └──────┬──────┘                               │
│                       │  Binder AIDL                         │
│                       │  "com.google.android.moseyservice   │
│                       │   .IMoseyService/default"            │
└───────────────────────┼─────────────────────────────────────┘
                        │
┌───────────────────────┼─────────────────────────────────────┐
│                ┌──────┴──────┐                               │
│                │ mosey_server │  (Rust native binary)        │
│                │ /odm/bin/   │                               │
│                │              │                               │
│                │  NDK Binder Service                        │
│                │  Transact codes:                            │
│                │    0xFFFFFF → getVersion() → int           │
│                │    1        → start(bps)                   │
│                │    2        → stop(bpt)                    │
│                │    3        → update(bpu)                  │
│                │                                              │
│                │  libmosey_daemon_ffi.so                     │
│                │  Symbols: mosey_start_4, mosey_stop,        │
│                │           mosey_dump, mosey_reset           │
│                └──────┬──────┘                               │
│                       │  nl80211 + AWDL                      │
│                       ▼                                       │
│                ┌──────────┐                                   │
│                │ wonder.ko │  (内核模块)                      │
│                │ wonder0   │  (NAN/monitor mode 虚拟接口)    │
│                └──────────┘                                   │
└─────────────────────────────────────────────────────────────┘
```

---

## 2. AIDL 接口 — 完整逆向定义

### 2.1 NDK Binder 接口 (`bpr` / `cfd`)

**服务标识**: `com.google.android.moseyservice.IMoseyService` + `/default`

这是 native `mosey_server` 注册的实际 Binder 服务：

```java
// bpr.java — OnePlus 版 NDK AIDL 接口
interface bpr extends IInterface {
    int a();                                          // TR_CODE: 16777215 (0xFFFFFF)
    void b(bps bpsVar);                               // TR_CODE: 1 → "start"
    void c(bpt bptVar);                               // TR_CODE: 2 → "stop"
    void d(bpu bpuVar);                               // TR_CODE: 3 → "update"
}
```

**Proxy 实现** (`bpq.java`):
```java
public final class bpq implements bpr {
    private final IBinder a;
    private int b = -1;  // 缓存 version

    public int a() {  // getVersion()
        // TR_CODE: 0xFFFFFF
        // 注意: 此方法直接传接口 token，不经过 Parcelable 包装
        // 因此不需要 hasValue 前缀
        ...
    }

    public void b(bps params) {  // start(filters, callback)
        // TR_CODE: 1
        // 注意: bps 是 Parcelable，AIDL 代理会自动写入:
        //   data.writeInt(1) ← hasValue (非 null)
        //   data.writeIntArray(params.a)
        //   data.writeStrongBinder(params.b)
        //   data.writeInt(params.c)
    }

    public void c(bpt params) {  // stop()
        // TR_CODE: 2
        // bpt 是空 Parcelable → hasValue=1 后无字段
    }

    public void d(bpu params) {  // update(string)
        // TR_CODE: 3
        // bpu 是 Parcelable → hasValue=1, 然后是 String
    }
}
```

### 2.2 Pixel 对比 (`cfd` / `cfc`)

Pixel 使用不同的服务名和白盒化参数类，但结构完全一致：

| 属性 | OnePlus | Pixel |
|------|---------|-------|
| 服务名 | `com.google.android.moseyservice.IMoseyService/default` | `com.google.pixel.moseyservice.IMoseyService/default` |
| AIDL 接口 | `bpr` | `cfd` |
| Proxy 类 | `bpq` | `cfc` |
| Java 包装 | `bmz` (前缀 "A") | `bnb` (前缀 "P") |
| 参数类型 | `bps`/`bpt`/`bpu` | `cfe`/`cff`/`cfg` |

### 2.3 参数结构

```java
// === bps.java (Start 参数) — 对应 Pixel 的 cfe.java ===
// 功能: 启动发现, 注册回调
public final class bps implements Parcelable {
    public int[] a;         // 过滤器数组 (medium 类型位掩码?)
    public IBinder b;       // 回调 Binder (接收发现事件)
    public int c = 1;       // 稳定性标志 (Parcelable.STABILITY_LOCAL)
}

// === bpt.java (Stop 参数) — 对应 Pixel 的 cff.java ===
// 功能: 停止发现, 无参数
public final class bpt implements Parcelable {
    // 空结构, 仅携带 Parcel 头
}

// === bpu.java (Update 参数) — 对应 Pixel 的 cfg.java ===
// 功能: 更新配置 (国家码?)
public final class bpu implements Parcelable {
    public String a;   // 字符串值 (可能是国家码/配置标记)
}
```

### 2.4 Java 包装接口 (`bnc`)

`bnc` 是 Java 层面对 NDK Binder 的高层封装：

```java
public interface bnc {
    String a();                               // "A" + version (OnePlus) / "P" + version (Pixel)
    void b();                                 // → native.stop()
    void c(String str);                       // → native.update(bpu{str})
    void d(int[] iArr, IBinder iBinder);     // → native.start(bps{iArr, iBinder, MAX_INT})
}
```

### 2.5 OnePlus 实现 (`bmz`)

```java
public final class bmz implements bnc {
    private final bpr d;  // NDK Binder proxy (bpq)

    public String a() { return "A" + this.d.a(); }      // 前缀 "A" + version

    public void b() {                                    // stop()
        this.d.c(new bpt());                             // transact TR_CODE 2
    }

    public void c(String str) {                          // update(countryCode)
        bpu bpuVar = new bpu();
        bpuVar.a = str;
        this.d.d(bpuVar);                               // transact TR_CODE 3
    }

    public void d(int[] iArr, IBinder iBinder) {         // start(filters, callback)
        bps bpsVar = new bps();
        bpsVar.a = iArr;
        bpsVar.b = iBinder;
        bpsVar.c = Integer.MAX_VALUE;                    // 特殊值 (无限?)
        this.d.b(bpsVar);                               // transact TR_CODE 1
    }
}
```

### 2.6 Pixel 实现 (`bnb`)

```java
public final class bnb implements bnc {
    private final cfd d;  // NDK Binder proxy (cfc)

    // 调用方式相同, 但:
    // - 服务名: "com.google.pixel.moseyservice.IMoseyService/default"
    // - 前缀: "P" + version
    // - 参数类型: cfe/cff/cfg (与 bps/bpt/bpu 结构相同但类型不同)
}
```

---

## 3. ExternalSharingService 完整调用链

### 3.1 AndroidManifest 声明

```xml
<service
    android:name="com.google.android.mosey.ExternalSharingService"
    android:permission="com.google.android.gms.permission.ACCESS_NEARBY_SHARE_API"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="com.google.android.nearby.SHARING_PROVIDER"/>
    </intent-filter>
    <meta-data
        android:name="com.google.android.gms.nearby.sharing.PROVIDER_VERSION"
        android:value="1"/>
    <meta-data
        android:name="com.google.android.gms.nearby.sharing.PROVIDER_NAME"
        android:value="Mosey"/>
</service>
```

**关键**: GMS 通过 Intent `com.google.android.nearby.SHARING_PROVIDER` 绑定到此服务，
需要 `ACCESS_NEARBY_SHARE_API` 权限 (signature|privileged)。

### 3.2 服务生命周期

```
GMS bindService(Intent"SHARING_PROVIDER")
  → agt.onCreate()
    → ExternalSharingService.b() [doCreate()]
      → ayi.b(this)  // 注册 ActivityLifecycleCallbacks
      → if (cqp.v()) Security.addProvider(new eaj())  // BC 安全提供者
      → this.a = new bgd(og.e(this), this)  // 创建控制器
    → this.b = a()  // 返回 bgd 作为 ags provider
    → 注册 ags 监听器到 sharingClient
```

### 3.3 `bgd` 控制器结构

```java
public final class bgd implements ags {
    public final boj a;          // 核心状态机 (WiFi/连接管理)
    public final bkp b;          // SendProvider — 发送发现
    public final biq c;          // ReceiveProvider — 接收发现
    public final ExecutorService d;
    private final czx e;         // 应用/activity 上下文
    private final Context f;
    private boolean g;           // 是否正在 advertising
}
```

### 3.4 `ags` 接口 (SharingProvider)

```java
public interface ags {
    int a(agy agyVar);              // accept(target)
    int b(agy agyVar);              // cancel(target)
    int c(agy agyVar);              // deny(target)
    int d(String, agy, ahh);       // send(text, target, callback)
    int e(agq, ahh);               // startAdvertising(config, callback)
    int f();                        // stopAdvertising()
    int g(agq);                     // updateAdvertising(config)
    void h(agy);                    // open(target)
    void i(String);                 // setDownloadDirectory(path)
    void j();                       // shutdown()
    void k();                       // stopDiscovery()
    void l(agr);                    // startDiscovery(options)
    int m(bid, agr);               // injectEndpoint(endpoint, options)
}
```

### 3.5 特征标志 (aconfig)

所有操作都受 aconfig 运行时标志控制:

```java
// ExternalSharingService.onCreate: Security provider
cqp.v() → flag #48 ("45743306"), default=true
        → ExternalSharingProvider 安全提供者

// bgd: 所有操作检查
cqp.l() → flag #24 ("45713369"), default=true
        → ExternalSharingProvider 核心开关
```

**重要**: "default=true" 表示如果无法从 phenotype 读取标志，默认启用。
但 `cqp` 通过 `bau` / `bsn` 加载，实际行为取决于编译时的嵌入标志。

---

## 4. Aconfig 标志系统

### 4.1 标志存储

标志定义在 `cqr.java` 中，通过 `bau` (B bucketed Aconfig) 系统加载：

```java
// cqr.class: aconfig 标志存储
private static final bau a = new bau(cqo.b, 75);
// 75 个标志通过 map 存储
// 每个标志: index + package_hash + default_value

// 读取示例:
// boolean flag = a.c(index, "packageHash", defaultValue).aX()
```

### 4.2 已知标志

通过 `cqr` 类定义可以获得 75 个标志。核心标志:

| 方法 | Index | 包哈希 | 默认值 | 说明 |
|------|-------|--------|--------|------|
| `A()` | 11 | 45750664 | false | |
| `B()` | 12 | 45755025 | false | |
| `C()` | 13 | 45749779 | false | |
| `D()` | 15 | 45754438 | false | |
| `E()` | 16 | 45717378 | **true** | |
| `F()` | 17 | 45717984 | **true** | |
| `a()` | 4 | 45721440 | 12000 | timeout(long) |
| `b()` | 32 | 45721439 | 10000 | timeout(long) |
| `c()` | 37 | 45717670 | -1 | timeout(long) |
| `d()` | 38 | 45717312 | 3000 | timeout(long) |
| `e()` | 39 | 45717313 | 0 | timeout(long) |
| `f()` | 40 | 45717314 | 500 | timeout(long) |

---

## 5. GMS Phenotype 标志注入

### 5.1 配置文件位置

- **CE 存储**: `/data/data/com.google.android.gms/databases/phenotype.db`
- **需要两个表**:
  - `flag_overrides` — 存储实际标志值
  - `flag_overrides_to_commit` — 告诉 GMS 读取标志

### 5.2 SQL 模板

```sql
-- 插入标志
INSERT OR REPLACE INTO flag_overrides
    (packageName, type, flagName, account_id, committed, activated, userType)
VALUES
    ('com.google.android.gms.nearby', 0, 'flag_name', 0, 0, 1, '');

-- 链接到提交表 (必须!)
INSERT OR REPLACE INTO flag_overrides_to_commit
    (packageName, flagName, account_id)
VALUES
    ('com.google.android.gms.nearby', 'flag_name', 0);
```

### 5.3 KSU 模块 service.sh 参考

位置: `/data/adb/modules/mosey-enabler/service.sh`

```bash
# 等待 CE 解锁
while [ "$(getprop sys.user.0.ce_available)" != "true" ]; do
    sleep 1
done

# 注入标志
PHENOTYPE_DB="/data/data/com.google.android.gms/databases/phenotype.db"
for flag in "ExternalSharingProvider__enable_external_receive_share" \
            "ExternalSharingProvider__enable_external_send_share" \
            "ExternalSharingProvider__enable_use_mosey"; do
    sqlite3 "$PHENOTYPE_DB" "INSERT OR REPLACE INTO flag_overrides VALUES('com.google.android.gms.nearby',0,'$flag',0,0,1,'');"
    sqlite3 "$PHENOTYPE_DB" "INSERT OR REPLACE INTO flag_overrides_to_commit VALUES('com.google.android.gms.nearby','$flag',0);"
done

# 广播唤醒 MoseyApp
pm enable com.google.android.mosey/.ExternalSharingService
am broadcast -a com.google.android.gms.phenotype.UPDATE
am broadcast -a android.intent.action.PACKAGE_ADDED -p com.google.android.mosey
```

---

## 6. Bada 集成方案

### 6.1 `MediumProvider` 接口 (Bada 纯 Kotlin)

```kotlin
// core-protocol/src/main/kotlin/.../medium/MediumProvider.kt
interface MediumProvider {
    val medium: Medium
    fun isSupported(): Boolean
    suspend fun prepareUpgrade(): UpgradePathCredentials?  // 服务端
    suspend fun adoptUpgrade(creds: UpgradePathCredentials): UpgradedTransport  // 客户端
}
```

### 6.2 MoseyMediumProvider 实现 (新增 media)

```kotlin
// discovery-android/src/main/kotlin/.../medium/MoseyMediumProvider.kt

class MoseyMediumProvider(private val context: Context) : MediumProvider {
    override val medium = Medium.MOSEY  // 需新增 Medium 枚举值

    override fun isSupported(): Boolean {
        // 检查 mosey_server Binder 是否存在
        return checkMoseyService()
    }

    override suspend fun prepareUpgrade(): UpgradePathCredentials? {
        // 服务端: 调用 native mosey update() → 获取 AWDL 凭证
        return moseyStartAdvertising()
    }

    override suspend fun adoptUpgrade(creds: UpgradePathCredentials): UpgradedTransport {
        // 客户端: 连接到服务端的 AWDL/P2P 接口
        return moseyConnect(creds)
    }
}
```

### 6.3 NDK Binder 直接调用 (绕过 MoseyApp)

由于 `mosey_server` 已经在运行并注册了 Binder 服务，Bada 可以直接通过 NDK Binder 调用，无需依赖 MoseyApp:

```kotlin
// 直接绑定到 mosey_server 的 NDK Binder
class MoseyBinderClient {
    private val serviceName = "com.google.android.moseyservice.IMoseyService/default"

    fun connect(): IBinder? {
        val sm = ServiceManagerNative.getDefault()
        return sm.getService(serviceName)
    }

    fun start(mediumFilters: IntArray, callback: IMoseyCallback) {
        // 通过 Binder transact TR_CODE 1
        val data = Parcel.obtain()
        data.writeInterfaceToken("com.google.android.moseyservice.IMoseyService")
        data.writeIntArray(mediumFilters)
        data.writeStrongBinder(callback.asBinder())
        data.writeInt(Integer.MAX_VALUE)

        val reply = Parcel.obtain()
        binder.transact(1, data, reply, 0)
    }

    fun stop() {
        // TR_CODE 2
        val data = Parcel.obtain()
        data.writeInterfaceToken("com.google.android.moseyservice.IMoseyService")
        val reply = Parcel.obtain()
        binder.transact(2, data, reply, 0)
    }

    fun update(value: String) {
        // TR_CODE 3
        val data = Parcel.obtain()
        data.writeInterfaceToken("com.google.android.moseyservice.IMoseyService")
        data.writeString(value)
        val reply = Parcel.obtain()
        binder.transact(3, data, reply, 0)
    }

    fun getVersion(): Int {
        // TR_CODE 16777215
        val data = Parcel.obtain()
        data.writeInterfaceToken("com.google.android.moseyservice.IMoseyService")
        val reply = Parcel.obtain()
        binder.transact(16777215, data, reply, 0)
        return reply.readInt()
    }
}
```

### 6.4 新增 Medium 枚举

```kotlin
// core-protocol/src/main/kotlin/.../medium/Medium.kt
enum class Medium(val wireNumber: Int) {
    BLUETOOTH(2),
    WIFI_HOTSPOT(3),
    BLE(4),
    WIFI_LAN(5),
    WIFI_AWARE(6),
    // ... 现有枚举 ...
    MOSEY(11),        // 新增: 自定义 AWDL/mosey 传输
}
```

### 6.5 架构集成图

```
┌─────────────────────────────────────────────────────────────┐
│ Bada                                                        │
│ ┌───────────────────────┐  ┌──────────────────────────────┐ │
│ │    core-protocol      │  │    discovery-android         │ │
│ │  ┌─────────────────┐  │  │  ┌────────────────────────┐ │ │
│ │  │ MediumProvider  │◄─┼──┼──│ MoseyMediumProvider    │ │ │
│ │  │   (interface)   │  │  │  │  - NDK Binder client   │ │ │
│ │  └─────────────────┘  │  │  │  - AWDL/wonder0 mgmt   │ │ │
│ │  ┌─────────────────┐  │  │  │  - Apple device disc.  │ │ │
│ │  │ Medium.MOSEY(11)│  │  │  └────────────────────────┘ │ │
│ │  └─────────────────┘  │  └──────────────────────────────┘ │
│ └───────────────────────┘                                    │
└───────────────────────────┬─────────────────────────────────┘
                            │
                            ▼
              ┌─────────────────────────┐
              │  mosey_server (Rust)    │
              │  NDK Binder Service     │
              │  "IMoseyService/default" │
              └────────────┬────────────┘
                           │
                           ▼
              ┌─────────────────────────┐
              │  wonder.ko / nl80211    │
              │  AWDL + NAN            │
              └─────────────────────────┘
```

---

## 7. SELinux 规则

如果 Bada 直接调用 NDK Binder (绕过 MoseyApp)，需要以下 SELinux 权限:

```
# Bada 进程 → servicemanager (查找 mosey_server)
allow bada_app servicemanager:binder { call transfer };

# Bada 进程 → mosey_server Binder
allow bada_app mosey_service:binder { call transfer };

# Bada 进程 → nl80211 (wonder0 接口)
allow bada_app self:netlink_route_socket { create bind read write };
allow bada_app self:packet_socket { create bind read write };
allow bada_app wireless_device:chr_file { read write ioctl };
```

---

## 8. 实施路线图

### 阶段 1: 基础设施
1. 在 Bada 中新增 `Medium.MOSEY(11)` 枚举
2. 创建 `MoseyMediumProvider` 接口骨架
3. 实现 NDK Binder 客户端 (`MoseyBinderClient`)
4. 编写单元测试验证 Binder 连接

### 阶段 2: 核心功能
5. 实现 `start()` → 调用 mosey native `TR_CODE 1` 启动 AWDL 发现
6. 实现 `stop()` → 调用 native 停止
7. 实现 `update(String)` → 更新国家码/配置
8. 处理回调 Binder (解析发现事件)

### 阶段 3: 集成
9. 注册 `MoseyMediumProvider` 到 `MediumRegistries`
10. 调整 discovery 流程以支持 AWDL 发现
11. 端到端 Apple→Android 传输测试

### 阶段 4: 优化
12. 性能调优
13. 错误处理和重试逻辑
14. 电量优化
15. 兼容性测试 (不同 Android 版本)

---

## 9. GMS 反向工程分析

### 9.1 GMS 中 ExternalSharingProvider 的完整调用链

```
aconfig flag "enableExternalProviders"
    ↓ jsdy.t()
dury (FlagSnapshot) { enableExternalProviders = true }
    ↓
NearbySharingChimeraService.W()
    ├── aJ() || (aH()/aI() + foreground) || (jseb.a.mD().aI() + condition)
    ├── → "Starting external provider registry if not running"
    └── → dttd.b() [START registry]
    
ExternalProviderRegistry (dttd)
    ├── 创建时: dzms.a (Idle 状态)
    ├── dttd.b() → dzml command (START)
    └── dzms.a() [状态机处理]
        ├── pm.queryIntentServices("com.google.android.nearby.SHARING_PROVIDER", 128)
        ├── 过滤: enabled && exported
        ├── "Found %d providers"
        └── 对每个 provider:
            └── dzlx.a(serviceInfo, ...)
                ├── eaac.a(context, packageName) → 白名单检查
                │   ├── return 2: Google-signed app (always OK)
                │   ├── return 3: explicitly whitelisted
                │   ├── return 4: in secondary list
                │   └── return 1: NOT whitelisted → "Skipping provider %s due to not whitelisted"
                ├── PROVIDER_VERSION metadata check
                └── bkms.g(context, "NearbyExternalProviderRegistry", intent, conn, flags)
                    ├── BIND_AUTO_CREATE | BIND_IMPORTANT
                    └── "Bound to provider %s"
```

### 9.2 GMS AIDL 接口: IExternalSharingProvider

**接口**: `duun` (extends `IInterface`), Proxy: `duul`
**DESCRIPTOR**: `"com.google.android.gms.nearby.sharing.internal.IExternalSharingProvider"`

```java
interface duun extends IInterface {
    void a(ProviderAcceptParams);          // accept
    void b(ProviderCancelParams);          // cancel
    void c(ProviderDenyParams);            // deny
    void d(ProviderOpenParams);            // open
    void e(ProviderSendParams);            // send
    void g(ProviderSetDownloadDirectoryParams); // setDownloadDirectory
    void i(ProviderShutdownParams);        // shutdown
    void l(ProviderStartAdvertisingParams); // startAdvertising
    void m(ProviderStartDiscoveryParams);   // startDiscovery
    void n(ProviderStopAdvertisingParams);  // stopAdvertising
    void o(ProviderStopDiscoveryParams);    // stopDiscovery
    void p(ProviderUpdateAdvertisingParams); // updateAdvertising
    void q(ProviderUpdateDiscoveryParams);   // updateDiscovery
    void r(ProviderValidatePinParams);      // validatePin
}
```

### 9.3 GMS 标志系统

GMS 使用双层标志系统:

| 层 | 类 | 说明 |
|-----|------|------|
| Aconfig | `jsea` (148 flags) | 编译时嵌入的默认值 + phenotype 可覆盖 |
| 特征门 | `jsdy` | `jsea`的静态包装, `t()` → `enableExternalProviders` |
| 快照 | `dury` (FlagSnapshot) | 一次读取多个 flags, 供其他组件查询 |

```
jsea (148 flags via gmrq) → jsdy (static accessors) → dury (FlagSnapshot)
                                                           ↓
                                                     jsdy.t()
                                              enableExternalProviders
                                                     ↓
                                          ExternalProviderRegistry
```

### 9.4 FlagSnapshot 定义

```java
// dury.java — FlagSnapshot(enableExternalProviders=..., ...)
public final class dury {
    public final boolean a;  // enableExternalProviders  → jsdy.t()
    public final boolean b;  // startForegroundServiceAgent → jsdy.K()
    public final boolean c;  // useInterruptingNotificationChannel... → jseb.T()
    public final boolean d;  // enableQrCodeCloud → jsdy.x()
    
    public dury(byte[] bArr) {
        boolean zT = jsdy.t();    // enableExternalProviders
        boolean zK = jsdy.K();    // startForegroundServiceAgent
        boolean zT2 = jseb.T();   // useInterruptingNotificationChannel...
        boolean zX = jsdy.x();    // enableQrCodeCloud
        this.a = zT; this.b = zK; this.c = zT2; this.d = zX;
    }
}
```

### 9.5 关键疑问: CN ROM 上为什么失败

基于反编译分析, 以下三点 都 可能导致 GMS 无法发现 MoseyApp:

| 问题 | 影响 | 验证方法 |
|------|------|----------|
| 1. **`enableExternalProviders` 标志为 false** | GMS 不启动 ExternalProviderRegistry | `logcat \| grep "external provider registry"` |
| 2. **MoseyApp 不在 GMS 白名单** | `eaac.a()` 返回 1, provider 被跳过 | `logcat \| grep "not whitelisted"` |
| 3. **MoseyApp 被禁用或未安装** | `pm.queryIntentServices()` 找不到 | 检查 `pm list packages \| grep mosey` |

### 9.6 GMS 相关文件索引 (反编译输出)

| 文件 | 路径 | 说明 |
|------|------|------|
| `duun.java` | `sources/defpackage/duun.java` | IExternalSharingProvider AIDL 接口 |
| `duul.java` | `sources/defpackage/duul.java` | AIDL Proxy 实现 |
| `dury.java` | `sources/defpackage/dury.java` | FlagSnapshot(enableExternalProviders) |
| `dtlp.java` | `sources/defpackage/dtlp.java` | 服务处理器 (extends duuy) |
| `duuy.java` | `sources/defpackage/duuy.java` | INearbySharingService AIDL 实现基类 |
| `dttd.java` | `sources/defpackage/dttd.java` | ExternalProviderRegistry 控制器 |
| `dzms.java` | `sources/defpackage/dzms.java` | Registry 状态机 (Idle→Binding) |
| `dzlx.java` | `sources/defpackage/dzlx.java` | 绑定逻辑 + 白名单检查 |
| `dzly.java` | `sources/defpackage/dzly.java` | Dagger Factory |
| `eaac.java` | `sources/defpackage/eaac.java` | 白名单检查逻辑 |
| `jsdy.java` | `sources/defpackage/jsdy.java` | 特征标志静态访问器 |
| `jsea.java` | `sources/defpackage/jsea.java` | 148 个 aconfig 标志定义 |
| `jsdz.java` | `sources/defpackage/jsdz.java` | 标志接口定义 |
| `RegisterSharingProviderParams.java` | `sources/.../internal/...` | 注册参数 (携带 IExternalSharingProvider Binder) |
| `dxsg.java` | `sources/defpackage/dxsg.java` | DisabledExternalSharingProvider (fallback) |
| `NearbySharingChimeraService.java` | `sources/.../nearby/sharing/...` | GMS Nearby Share 主服务 |

---

## 10. 实施方案对比

### 方案 A: GMS Phenotype 注入 (当前 KSU 模块)

**原理**: 通过修改 phenotype.db 强制 GMS 启用 ExternalSharingProvider

```
KSU service.sh → 注入 flag_overrides → GMS 重启 → 读取 enableExternalProviders=true
    → 启动 ExternalProviderRegistry → queryIntentServices(SHARING_PROVIDER)
    → bindService(MoseyApp) → MoseyApp 连接 mosey_server → AWDL 发现
```

**优点**: 复用完整的 Google 生态, 无需修改 Bada
**缺点**: 
- 依赖 GMS 内部行为 (可能随版本变化)
- 需要 MoseyApp APK (GLO 专有)
- 白名单检查可能阻止绑定
- GMS 必须识别设备为"受支持"

### 方案 B: Bada 直接调用 mosey_server NDK Binder ✅ 推荐 (v3.1)

**原理**: Bada 绕过 GMS 和 MoseyApp, 直接通过 ServiceManager 获取 mosey_server 的 NDK Binder 并调用

```
Bada → ServiceManager.getService("com.google.android.moseyservice.IMoseyService/default")
     → mosey_server NDK Binder
     → transact(1, bps{hasValue, int[], IBinder, int}) → 启动 AWDL 发现
     → transact(3, bpu{hasValue, country_code})        → 更新配置
     → transact(2, bpt{hasValue})                      → 停止
```

**2026-06-10 更新**:
- ADB 诊断确认 mosey_server 注册在 **默认 binder**（`/dev/binderfs/binder`）
- Shell 用户（无 root）可直接调用 `getVersion()` ✅
- `MoseyBinderClient` 已修复 Parcel 格式（添加 `hasValue=1` 前缀）
- **不再需要 mosey_bridge TCP 桥接**！(`mosey_bridge.c` 保留为后备方案)

**优点**:
- 完全独立, 不依赖 GMS/MoseyApp
- 控制权完整 (Bada 决定何时启动/停止)
- 可以与 Bada 的现有 MediumProvider 架构完美集成
- 只需要 mosey_server 二进制存在

**缺点**:
- 需要实现 AWDL→Bada 的发现事件转换
- 需要 SELinux 规则 (bada_app → mosey_service)
- 需要 root/KSU (直接调用 ServiceManager)

### 方案 C: Bada 内置 AWDL 实现 (长期)

**原理**: 在 Bada 中直接实现 AWDL 协议栈, 不依赖 mosey_server

```
Bada → libpcap → nl80211 → wonder.ko → AWDL 帧
Bada 自己实现 AWDL 解析, 无需 mosey_server
```

**优点**: 完全独立, 无 Google 依赖
**缺点**: 开发工作量大, AWDL 协议复杂

---

## 11. 实施路线图 (推荐: 方案 B)

### 阶段 1: 验证 NDK Binder 通路
1. 编写测试应用, 通过 ServiceManager 获取 mosey_server Binder
2. 测试 transact(0xFFFFFF) 获取版本号
3. 测试 transact(3) 更新国家码
4. 测试 transact(1) 启动发现 + 注册回调 Binder

### 阶段 2: Bada 集成
5. 新增 `Medium.MOSEY(11)` 枚举
6. 创建 `MoseyMediumProvider` 实现 `MediumProvider` 接口
7. 在 Android 侧封装 NDK Binder 调用逻辑
8. 注册到 `MediumRegistries`

### 阶段 3: 功能完善
9. 实现发现事件回调解析
10. 实现文件传输
11. 错误处理和重试
12. 性能优化

### 阶段 4: 测试
13. Apple→Android AWDL 发现测试
14. 文件传输测试
15. 兼容性测试 (不同设备/ROM)

---

## 12. 关键调试命令

| 文件 | 路径 | 说明 |
|------|------|------|
| `bpq.java` | `decompile/moseyapp/sources/defpackage/bpq.java` | NDK Binder proxy (OnePlus) |
| `bpr.java` | `decompile/moseyapp/sources/defpackage/bpr.java` | NDK AIDL 接口 (OnePlus) |
| `bmz.java` | `decompile/moseyapp/sources/defpackage/bmz.java` | Java 包装 (OnePlus) |
| `bnc.java` | `decompile/moseyapp/sources/defpackage/bnc.java` | Java 接口定义 |
| `bps.java` | `decompile/moseyapp/sources/defpackage/bps.java` | Start 参数 |
| `bpt.java` | `decompile/moseyapp/sources/defpackage/bpt.java` | Stop 参数 |
| `bpu.java` | `decompile/moseyapp/sources/defpackage/bpu.java` | Update 参数 |
| `cfc.java` | `decompile/moseyapp/sources/defpackage/cfc.java` | NDK Binder proxy (Pixel) |
| `cfd.java` | `decompile/moseyapp/sources/defpackage/cfd.java` | NDK AIDL 接口 (Pixel) |
| `bnb.java` | `decompile/moseyapp/sources/defpackage/bnb.java` | Java 包装 (Pixel) |
| `ExternalSharingService.java` | `decompile/moseyapp/sources/.../mosey/ExternalSharingService.java` | 服务入口 |
| `bgd.java` | `decompile/moseyapp/sources/defpackage/bgd.java` | 控制器 |
| `bgn.java` | `decompile/moseyapp/sources/defpackage/bgn.java` | 服务基类 |
| `agt.java` | `decompile/moseyapp/sources/defpackage/agt.java` | 抽象服务 + sharingClient 注册 |
| `ags.java` | `decompile/moseyapp/sources/defpackage/ags.java` | SharingProvider 接口 |
| `cqp.java` | `decompile/moseyapp/sources/defpackage/cqp.java` | Aconfig 标志访问器 |
| `cqr.java` | `decompile/moseyapp/sources/defpackage/cqr.java` | Aconfig 标志存储 (75 flags) |
| `boj.java` | `decompile/moseyapp/sources/defpackage/boj.java` | 核心状态机 (WiFi/mDNS) |
| `bkp.java` | `decompile/moseyapp/sources/defpackage/bkp.java` | 发送发现提供者 |
| `biq.java` | `decompile/moseyapp/sources/defpackage/biq.java` | 接收发现提供者 |

---

## 10. 附录: 关键调试命令

```bash
# 检查 mosey_server Binder 服务
service check com.google.android.moseyservice.IMoseyService/default

# 查看 mosey_server 进程
ps -A | grep mosey
lsof -p $(pidof mosey_server)

# 检查 wonder 内核模块
lsmod | grep wonder
iw dev

# 测试 Binder 调用 (使用 servicemanager)
service call com.google.android.moseyservice.IMoseyService 16777215

# 查看 phenotype 标志
sqlite3 /data/data/com.google.android.gms/databases/phenotype.db \
  "SELECT * FROM flag_overrides WHERE packageName='com.google.android.gms.nearby';"

# 检查 SELinux 上下文
ps -AZ | grep mosey
```
