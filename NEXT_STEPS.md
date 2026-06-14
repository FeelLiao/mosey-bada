# Bada Mosey AWDL 集成 — 下一步开发与测试计划

> **状态审查日期**: 2026-06-10
> **当前阶段**: Phase 1.5 — 发送端 Mosey 发现集成（开发方案中缺失的环节）

---

## 一、现状总览

### ✅ 已完成

| 组件 | 状态 |
|------|------|
| `mosey_bridge.c` TCP↔Binder 桥接 | ✅ 编译/运行，getVersion=1 |
| `MoseySocketClient` (Bada TCP 客户端) | ✅ 代码完成，协议匹配桥接 |
| `MoseyMediumProvider` (接收端) | ✅ 已注册到 `MediumRegistries.defaultForContext()` |
| `MoseyBinderClient` (直接 Binder) | ✅ 代码完成，Parcel 格式已修复 |
| KSU 模块 v1.2 | ✅ mosey_server + bridge 运行中 |
| 桥接协议验证 | ✅ Python 测试通过 |

### ❌ 发现的缺失

| 问题 | 影响 | 严重性 |
|------|------|--------|
| **桥接只转发 tx_code，不转发回调数据** | Bada 收不到设备信息（IP、名称等） | 🔴 P0 |
| **发送端 `NearbyPeerDiscovery` 不含 Mosey** | 用户发送时看不到 Apple 设备 | 🔴 P0 |
| **mosey_server 未收到 start() 调用** | wonder0 未创建，AWDL 未扫描 | 🔴 P0 |
| **MoseyBinderClient 未测试** | 直接 Binder 调用是否真的可用？ | 🟡 P1 |
| **回调数据格式未知** | 需捕获原始字节再逆向 | 🟡 P1 |

### 架构根本问题

开发方案假设集成走 `MediumProvider`（接收端 upgrade 路径），但**用户发送文件时 Bada 走的是 `NearbyPeerDiscovery`**，它只聚合了 3 种发现源：mDNS + BLE 扫描 + 蓝牙经典。

Mosey/AWDL 发现的 Apple 设备从未进入发送端的 peer 发现流程。

---

## 二、架构修正

### 正确的数据流

```
发送文件时:

SendActivity
  └→ SendPeerPickerController (显示设备列表)
       └→ NearbyPeerDiscovery.browse() (发现设备)
            ├─ mDNS (Wi-Fi LAN)
            ├─ BLE 扫描
            └─ ⬜ MOSEY ← 需要新增!
                  └→ MoseyPeerScanner (新组件)
                       ├→ connect() → mosey_bridge TCP
                       ├→ start(filters=[13])
                       ├→ 接收 onDeviceDiscovered()
                       ├→ 解析: { deviceName, ipv4 }
                       └→ 注入为 NearbyPeer(lanEndpoint=192.168.x.x)
```

**关键洞见**: Apple 设备通过 AWDL 发现的 IP 地址可以直接作为 `NearbyPeer.LanEndpoint` 注入。Bada 现有的 `WifiLanTransferProvider` 已能处理后续的 Quick Share TCP 传输。

---

## 三、分步实施计划

### Step 1: 修复桥接回调转发（桥接侧）

**当前问题**: `cb_on_transact` 只发送 `[tx_code:u32]`，丢弃了 `AParcel* in` 中的设备数据。

**修改**: 读取 `AParcel* in` 中的数据并转发。

```c
static binder_status_t cb_on_transact(AIBinder* binder,
                                       transaction_code_t code,
                                       const AParcel* in,
                                       AParcel* out) {
    // 1. 读取 in Parcel 中所有可用数据
    int32_t data_size = AParcel_getDataSize(in);
    uint8_t* raw_data = malloc(data_size);
    // 用 AParcel_read 函数读取具体字段...
    
    // 2. 转发: [tx_code:u32][data_size:u32][raw_data...]
    size_t payload_len = 4 + 4 + data_size;
    uint8_t* payload = malloc(payload_len);
    write_i32(payload, 0, (int32_t)code);
    write_i32(payload, 4, data_size);
    memcpy(payload + 8, raw_data, data_size);
    send_frame(g_client_fd, FRAME_EVENT, payload, payload_len);
}
```

**⚠️ 注意**: `AParcel_getDataSize()` 在 NDK 中可能不可用。替代方案是用一个 "探索" 命令，先 start 然后 dump 原始数据。

### Step 2: 捕获并分析回调数据

**目标**: 理解 mosey_server 回调中到底包含什么字段。

```bash
# 1. 编译支持 raw dump 的桥接 → 推送到设备
# 2. 通过 Python 脚本 start() 扫描
# 3. 在 Apple 设备上打开 AirDrop
# 4. 捕获回调原始字节
# 5. 与 MoseyApp 反编译对比:
#    - bkp.java  (SendProvider) — 如何解析发现回调
#    - biq.java  (ReceiveProvider) — 如何解析接收回调
```

**预期回调内容**（基于 MoseyApp 反编译）:
```
设备端点 ID (byte[])
设备名称 (String)
IPv4 地址 (byte[4])
IPv6 地址 (byte[16])
MAC 地址 (byte[6])
信号强度 (int)
服务类型 / medium 过滤器 (int)
```

### Step 3: 更新桥接 → 结构化回调转发

在确认格式后，更新桥接代码：
1. 解析 `AParcel* in` 中的字段
2. 构造成结构化的 TCP 事件帧
3. 转发给 Bada

### Step 4: 更新 MoseySocketClient（Bada 侧）

**文件**: `discovery-android/.../medium/MoseySocketClient.kt`

更新 `parseDiscoveredDevice()` 以解析桥接转发的结构化数据：

```kotlin
private fun parseDiscoveredDevice(data: ByteArray): AppleDevice? {
    // 解析: [tx_code:i32][data_size:i32][device_name:String][ipv4:byte[4]]...
    val txCode = readInt32(data, 0)
    val dataSize = readInt32(data, 4)
    // ... 具体解析取决于原始格式
    return AppleDevice(
        endpointId = ...,
        deviceName = ...,
        ipv4 = ...,
        ...
    )
}
```

### Step 5: 实现 MoseyPeerScanner（新组件）

**新文件**: `discovery-android/.../MoseyPeerScanner.kt`

创建在发送端使用的 Mosey 发现扫描器：

```kotlin
package dev.bluehouse.bada.discovery

class MoseyPeerScanner(
    private val socketClient: MoseySocketClient = MoseySocketClient(),
) {
    fun scan(): Flow<NearbyPeerEvent> = callbackFlow {
        // 1. 连接桥接
        if (!socketClient.connect()) {
            close() // 桥接不可用
            return@callbackFlow
        }
        
        // 2. 注册事件处理器
        socketClient.setEventHandler(object : MoseyEventHandler {
            override fun onDeviceDiscovered(device: AppleDevice) {
                // 3. Apple 设备 → NearbyPeer
                val ip = device.ipv4?.let { InetAddress.getByName(it) }
                if (ip != null) {
                    trySend(NearbyPeerEvent.PeerAdded(
                        NearbyPeer(
                            stableId = "mosey:${device.endpointId}",
                            endpointId = device.endpointId,
                            endpointInfo = null, // Apple 设备没有 Bada EndpointInfo
                            lanEndpoint = LanEndpoint(
                                addresses = listOf(ip),
                                port = 44378, // Quick Share TCP port
                            ),
                        )
                    ))
                }
            }
            
            override fun onDeviceLost(deviceId: String) {
                trySend(NearbyPeerEvent.PeerRemoved("mosey:$deviceId"))
            }
            
            override fun onDisconnected(reason: String) {
                close() // 断开连接
            }
        })
        
        // 4. 开始 AWDL 扫描
        socketClient.start(intArrayOf(13)) // AWDL filter
        
        awaitClose {
            socketClient.stop()
            socketClient.close()
        }
    }
}
```

### Step 6: 集成到 NearbyPeerDiscovery

**修改文件**: `NearbyPeerDiscovery.kt`

```kotlin
public class NearbyPeerDiscovery internal constructor(
    private val lanEvents: Flow<DiscoveryEvent>,
    private val bleEvents: Flow<BleFastAdvertisementScanner.Observation>,
    private val bluetoothEvents: Flow<BluetoothClassicPeerScanner.Observation>,
    private val moseyEvents: Flow<NearbyPeerEvent> = emptyFlow(), // ← 新增
) {
    public constructor(context: Context) : this(
        lanEvents = Discovery(context.applicationContext).browse(),
        bleEvents = BleFastAdvertisementScanner(context.applicationContext).scan(),
        bluetoothEvents = ...,
        moseyEvents = MoseyPeerScanner().scan(), // ← 新增
    )

    public fun browse(): Flow<NearbyPeerEvent> =
        callbackFlow {
            val aggregator = PeerAggregator()

            // ... 现有的 lanJob, bleJob, bluetoothJob ...

            val moseyJob = launch { // ← 新增
                moseyEvents.collect { event ->
                    trySend(event).isSuccess
                }
            }

            awaitClose {
                lanJob.cancel()
                bleJob.cancel()
                bluetoothJob.cancel()
                moseyJob.cancel() // ← 新增
            }
        }
}
```

---

## 四、测试计划

### Level 1: 桥接回调转发测试

```bash
# 1. 启动桥接（已运行）
# 2. 用 Python 脚本连接并发送 start() 命令
adb shell su -c '/data/data/com.termux/files/usr/bin/python /data/local/tmp/test_bridge.py'

# 3. 在 Apple 设备上打开 AirDrop
# 4. 观察桥接日志
adb shell logcat -s MoseyBridge

# 预期: cb_on_transact: tx_code=X  + 原始回调字节
```

### Level 2: Python 端到端测试

扩展 `test_bridge.py`:

```python
# 测试序列:
# 1. connect → getVersion() → 1
# 2. start(filters=[13]) → status=0
# 3. 等待 30 秒，接收事件帧
# 4. 打印事件帧内容（原始 hex）
# 5. stop()
```

### Level 3: Bada 集成测试

| 测试 | 方法 | 预期 |
|------|------|------|
| `MoseyPeerScanner` 连接桥接 | 启动 Bada 发送流程 | logcat 看到 "MoseyPeerScanner: connected" |
| Apple 设备发现 | iPhone 打开 AirDrop | logcat 看到 "onDeviceDiscovered: iPhone" |
| NearbyPeer 注入 | 同测试 | 发送 UI 出现 "iPhone (AWDL)" 条目 |
| 选择 → 传输 | 点击设备 → 发送文件 | Quick Share TCP 传输成功 |

### Level 4: 回归测试

| 场景 | 预期 |
|------|------|
| 桥接未运行时 Bada 发送 | Mosey 不可用，其他发现正常 |
| Android ↔ Android 传输 | 完全不受影响 |
| 桥接断开后重连 | Mosey 降级，不崩溃 |

---

## 五、优先级与依赖

```
Week 1: 桥接回调转发 (Step 1-2)
  ├─ Step 1: 修复 cb_on_transact → 转发原始 Parcel 数据
  ├─ Step 2: 捕获 + 分析回调格式
  └─ 验证: Python 脚本收到事件帧

Week 2: 结构化解析 + Bada 集成 (Step 3-5)
  ├─ Step 3: 更新桥接结构化转发
  ├─ Step 4: 更新 MoseySocketClient.parseDiscoveredDevice()
  └─ Step 5: 实现 MoseyPeerScanner

Week 3: NearbyPeerDiscovery 集成 (Step 6)
  ├─ Step 6: 修改 NearbyPeerDiscovery
  └─ 端到端测试
```

---

## 六、当前立即执行下一步

### 立即任务: 修复桥接回调并捕获原始数据

1. 修改 `mosey_bridge.c` 的 `cb_on_transact` 以转发回调 Parcel 数据
2. 重新编译并推送
3. 用扩展的 Python 脚本测试 start() + 事件接收
4. 在 Apple 设备上开 AirDrop，分析捕获的原始字节

需要你的确认：**要开始修复桥接回调转发吗？**
