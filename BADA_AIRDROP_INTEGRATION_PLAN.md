# Bada AirDrop 集成开发方案 v4.0

> **版本**: v4.0 — 唤醒链路修复 + Bada 控制集成
> **日期**: 2026-06-14
> **基于**: 真机日志分析 + 代码审查
> **前置**: KSU 模块 v1.30 + Bada 20260613.01

---

## 目录

1. [现状诊断](#1-现状诊断)
2. [问题定义](#2-问题定义)
3. [修复路径](#3-修复路径)
4. [实施路线图](#4-实施路线图)
5. [文件变更清单](#5-文件变更清单)
6. [测试计划](#6-测试计划)
7. [附录：代码证据](#7-附录代码证据)

---

## 1. 现状诊断

### 1.1 当前完成了什么

| 层 | 组件 | 状态 |
|----|------|------|
| 射频 | `wonder.ko` wondertap + mosey0 | ✅ 断开 WiFi 后可正常初始化 |
| 射频 | AWDL mDNS / BLE 信号 | ✅ iPhone 能发现手机 |
| 桥接 | `mosey_bridge` TCP:19539 + 19540 | ✅ 运行正常，事件可分类型 |
| 桥接 | Shim 事件（type=0x03 apple_ble_seen）| ✅ 每秒产生 98 字节事件 |
| 桥接 | `CMD_WAKE_BADA` | ⚠️ 实现存在，但目标组件错误 |
| 应用 | `MoseySocketClient` TCP 客户端 | ✅ 连接/订阅/命令链路正常 |
| 应用 | `MoseyMediumProvider` | ✅ 接收端 upgrade 路径已注册 |
| 应用 | `AirDropLocalServer` :19541 | ✅ `/Ask`、`/Upload` 逻辑完整 |
| 应用 | `AirDropWakeReceiver` | ✅ 对外广播接收器已声明 |
| WebUI | 模块开关 + 状态 + WiFi 管理 | ⚠️ XWeb 兼容性问题已修复 |

### 1.2 关键断点

```
iPhone 发送文件
    │
    ├── 1. AWDL 发现手机 (✅ 成功)
    │       └── BLE beacon → wonder.ko → mosey_server 解析 → shim 事件
    │
    ├── 2. shim 发送 event type=0x03 (✅ 成功)
    │       └── bridge 收到 → "No subscriber for event type=3"
    │       └── Bada 未连接 19540 (❌ 无订阅者)
    │
    ├── 3. Bada 应被唤醒 (❌ 失败)
    │       ├── bridge 有 CMD_WAKE_BADA 但无人调用
    │       └── shim/MoseySocketClient 对 type=0x03 只写日志，不触发 wake
    │
    ├── 4. ReceiverForegroundService 未启动 (❌)
    │       └── AirDropLocalServer :19541 未监听
    │
    └── 5. iPhone 发 HTTPS /Ask → shim proxy → :19541 (❌ 连接被拒)
```

---

## 2. 问题定义

### P0：唤醒目标组件失配

bridge 的 `CMD_WAKE_BADA` (line 516-523) 直接 `am start-foreground-service` 指向 `ReceiverForegroundService`：

```c
system("am start-foreground-service -n "
       "dev.bluehouse.bada.debug/.receiver.ReceiverForegroundService "
       ">/dev/null 2>&1");
```

但 `ReceiverForegroundService` 在 Bada manifest 中声明为 **`android:exported="false"`**（`service-android/src/main/AndroidManifest.xml:28-31`）。从 Android 14 起，跨包（shim 包名 `dev.bluehouse.moseybridgeshim` vs Bada 包名 `dev.bluehouse.bada.debug`）显式 Intent 启动 exported=false 的 Service 会被平台拒绝，抛出 `SecurityException`。

仓库已正确提供了对外入口：**`AirDropWakeReceiver`**（`service-android/.../airdrop/AirDropWakeReceiver.kt`），声明为 `android:exported="true"`，监听 action `dev.bluehouse.bada.airdrop.WAKE`。收到广播后调用 `ReceiverForegroundService.start(context)`。

**需要修改**：bridge 的 `CMD_WAKE_BADA` 应从 `start-foreground-service` 改为发送广播到 `AirDropWakeReceiver`。

### P1：type=0x03 事件无业务处理

`MoseySocketClient.handleEvent()` (line 213) 对 `EVENT_APPLE_BLE_SEEN` 的处理：

```kotlin
EVENT_APPLE_BLE_SEEN -> Log.d(TAG, "Apple BLE wakeup observed: $jsonText")
```

只写日志，不触发任何回调。同时 `MoseyEventHandler` 接口也没有 `onAppleBleSeen(...)` 方法。

这意味着即使 bridge 把事件送到了 Bada，Bada 也不会做出拉起接收服务的动作。

### P2：控制通道与事件通道耦合

`MoseySocketClient.connect()` 自动执行 `CMD_SUBSCRIBE`（line 59），且 bridge 只允许一个 `g_subscriber`。如果设置页为了轮询状态新建一个 client，会顶掉已有的 discovery 事件订阅。

同时 `pendingReply` 是单槽竞态控制（line 85-102），无法并发处理"每 5 秒 status 轮询 + enable/disable + 后台 discovery 事件"。

### P3：AirDropLocalServer 缺少 100-continue

`handleUpload()` 和 `handleAsk()` 没有处理 `Expect: 100-continue` 请求头。如果 Apple 的发端使用该机制（macOS 常见），服务端可能在应用层卡住。

---

## 3. 修复路径

### Step 1：修复 CMD_WAKE_BADA 目标（bridge 侧）

**文件**: `mosey_bridge.c`

将 CMD_WAKE_BADA 的 handler 从：

```c
system("/system/bin/am start-foreground-service -n "
       "dev.bluehouse.bada.debug/dev.bluehouse.bada.service.receiver.ReceiverForegroundService "
       ">/dev/null 2>&1");
```

改为：

```c
system("/system/bin/am broadcast -a dev.bluehouse.bada.airdrop.WAKE "
       "-n dev.bluehouse.bada.debug/dev.bluehouse.bada.service.airdrop.AirDropWakeReceiver "
       "--include-stopped-packages >/dev/null 2>&1 || "
       "/system/bin/am broadcast -a dev.bluehouse.bada.airdrop.WAKE "
       "-n dev.bluehouse.bada/dev.bluehouse.bada.service.airdrop.AirDropWakeReceiver "
       "--include-stopped-packages >/dev/null 2>&1");
```

关键变化：
- `start-foreground-service` → `broadcast`（发送有序广播）
- 目标组件：`ReceiverForegroundService` → `AirDropWakeReceiver`
- 移除 `force-stop`（防止竞态：force-stop 后再 start 可能被系统抑制）
-添加 `--include-stopped-packages`（确保应用即使被停止也能收到广播）

### Step 2：给 EVENT_APPLE_BLE_SEEN 添加回调 + 去抖唤醒（Bada 侧）

**文件**: `MoseyEventHandler.kt`

```kotlin
public interface MoseyEventHandler {
    public fun onDeviceDiscovered(device: AppleDevice)
    public fun onDeviceLost(endpointId: String)
    public fun onConnected()
    public fun onDisconnected(reason: String)
    public fun onAppleBleSeen(deviceName: String, mac: String?)  // ← 新增
}
```

**文件**: `MoseySocketClient.kt`

```kotlin
// handleEvent() 中
EVENT_APPLE_BLE_SEEN -> {
    Log.d(TAG, "Apple BLE wakeup observed: $jsonText")
    val deviceName = json.optString("deviceName", "Apple device")
    val mac = json.optString("mac", null)
    eventHandler.get()?.onAppleBleSeen(deviceName, mac)
}
```

**文件**: `MoseyPeerScanner.kt` 或新增 `MoseyWakeHandler.kt`

```kotlin
// 去抖逻辑：60 秒内最多触发一次 wake
private val lastWakeTime = AtomicLong(0)

override fun onAppleBleSeen(deviceName: String, mac: String?) {
    val now = System.currentTimeMillis()
    if (now - lastWakeTime.get() < 60_000) return  // 去抖
    lastWakeTime.set(now)
    Log.i(TAG, "BLE wake signal from $deviceName; sending CMD_WAKE_BADA")
    socketClient.sendWakeBada()  // 新增方法
}
```

### Step 3：新增 `CMD_WAKE_BADA` 的 Bada 侧方法

**文件**: `MoseySocketClient.kt`

```kotlin
// 新增常量
private const val CMD_WAKE_BADA: Byte = 5

// 新增方法
public fun sendWakeBada(): Boolean {
    val reply = sendRequest(CMD_WAKE_BADA)
    return reply.status == 0
}
```

### Step 4：拆出独立控制客户端 `MoseyControlClient`

**新建文件**: `MoseyControlClient.kt`

职责：
- 独立 TCP 连接到 bridge（可选端口复用 19539）
- **不自动发送 CMD_SUBSCRIBE**
- 仅有 `enable()` / `disable()` / `status()` / `wakeBada()` 四个方法
- 内部实现简单的请求-响应，不与 `MoseySocketClient` 共享连接

```kotlin
class MoseyControlClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 19539,
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null

    fun connect(): Boolean { /* 连接但不 subscribe */ }
    fun close() { /* 关闭连接 */ }

    fun enable(): Boolean { return sendCmd(CMD_ENABLE).status == 0 }
    fun disable(): Boolean { return sendCmd(CMD_DISABLE).status == 0 }
    fun status(): MoseyStatus? { 
        val reply = sendCmd(CMD_STATUS)
        return if (reply.status == 0) MoseyStatus.parse(reply.data) else null 
    }
    fun wakeBada(): Boolean { return sendCmd(CMD_WAKE_BADA).status == 0 }

    private fun sendCmd(cmd: Byte): BridgeReply { /* 基础帧协议 */ }
}

data class MoseyStatus(
    val enabled: Boolean,
    val nativeRunning: Boolean,
    val bridgeRunning: Boolean,
    val shimRunning: Boolean,
    val wifiConnected: Boolean,
    val mosey0Exists: Boolean,
    val wonderLoaded: Boolean,
)
```

**现有 `MoseySocketClient` 保持不变**——它继续承载 discovery 事件订阅职责。

### Step 5：新增桥接命令 `CMD_ENABLE(6)` / `CMD_DISABLE(7)` / `CMD_STATUS(8)`

**文件**: `mosey_bridge.c`

```c
#define CMD_ENABLE   6
#define CMD_DISABLE  7
#define CMD_STATUS   8
```

在 `client_thread()` 中新增 handler：

```c
} else if (cmd == CMD_ENABLE) {
    int rc = system("/system/bin/sh /data/adb/modules/mosey-enabler/mosey-control.sh "
                    "webui enable >/dev/null 2>&1");
    value = (rc == 0) ? 0 : -1;
    status = value;
    LOGI("command=enable result=%d", rc);
} else if (cmd == CMD_DISABLE) {
    int rc = system("/system/bin/sh /data/adb/modules/mosey-enabler/mosey-control.sh "
                    "webui disable >/dev/null 2>&1");
    value = (rc == 0) ? 0 : -1;
    status = value;
    LOGI("command=disable result=%d", rc);
} else if (cmd == CMD_STATUS) {
    // popen 读取 JSON 状态
    FILE* fp = popen("/system/bin/sh /data/adb/modules/mosey-enabler/mosey-control.sh "
                     "webui status 2>/dev/null", "r");
    if (fp) {
        char buf[512] = {0};
        size_t n = fread(buf, 1, sizeof(buf) - 1, fp);
        int rc = pclose(fp);
        // 返回 [status:i32][json_len:i32][json_bytes...]
        uint8_t reply[8 + n];
        write_i32(reply, 0);       // status = 0
        write_i32(reply + 4, n);   // data length
        memcpy(reply + 8, buf, n);
        send_frame_locked(client, FRAME_REPLY, reply, sizeof(reply));
    }
    // 如果 popen 失败，走原有错误路径
    if (!fp) { status = -1; value = -1; goto send_fail; }
}
```

### Step 6：MoseyControlClient 集成到 Bada UI

**文件**: 新增 `MoseySettingsScreen.kt`（示例路径 `app/.../ui/settings/`）

```kotlin
@Composable
fun MoseySettingsScreen(controller: MoseyControlClient) {
    var enabled by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<MoseyStatus?>(null) }

    // 每 5 秒轮询一次
    LaunchedEffect(Unit) {
        while (isActive) {
            status = controller.status()
            enabled = status?.enabled ?: false
            delay(5000)
        }
    }

    Column(modifier = Modifier.padding(16.dp)) {
        Switch(
            checked = enabled,
            onCheckedChange = {
                if (it) controller.enable() else controller.disable()
            }
        )
        // 状态指示器
        StatusRow("mosey_server", status?.nativeRunning)
        StatusRow("mosey_bridge", status?.bridgeRunning)
        StatusRow("shim", status?.shimRunning)
        StatusRow("Wi-Fi", status?.wifiConnected)
        StatusRow("mosey0", status?.mosey0Exists)
        StatusRow("wonder.ko", status?.wonderLoaded)
    }
}
```

**entry point**: 在 Bada 设置页或发送页添加入口。推荐放在 `SendActivity` 的溢出菜单或独立 AirDrop 设置屏。

### Step 7：添加 Expect: 100-continue 支持

**文件**: `AirDropLocalServer.kt`

```kotlin
// 在 handle() 方法中，readRequest() 之后、dispatch() 之前添加：
private fun handle(clientSocket: Socket) {
    val input = BufferedInputStream(clientSocket.getInputStream())
    val output = BufferedOutputStream(clientSocket.getOutputStream())
    val request = readRequest(input)
    
    // ── Expect: 100-continue ──
    if (request.headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
        output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray())
        output.flush()
        Log.d(TAG, "Sent 100 Continue for ${request.method} ${request.path}")
    }
    
    dispatch(request, input, output)
}
```

### Step 8：替代方案 Bada 侧状态管理

**新建文件**: `StatusRepository.kt`

```kotlin
object StatusRepository {
    // 根侧状态（来自 bridge CMD_STATUS）
    data class RootState(
        val enabled: Boolean = false,
        val nativeRunning: Boolean = false,
        val bridgeRunning: Boolean = false,
        val shimRunning: Boolean = false,
        val wifiConnected: Boolean = false,
        val mosey0Exists: Boolean = false,
        val lastBridgeEvent: Long = 0,
    )
    
    // 应用侧状态（本地读取，不需要 root）
    data class AppState(
        val receiverServiceRunning: Boolean = false,
        val localServerRunning: Boolean = false,
        val pendingConsentCount: Int = 0,
        val lastAskResult: AskResult? = null,
        val lastUploadResult: UploadResult? = null,
    )
    
    private val _rootState = MutableStateFlow(RootState())
    val rootState: StateFlow<RootState> = _rootState.asStateFlow()
    
    private val _appState = MutableStateFlow(AppState())
    val appState: StateFlow<AppState> = _appState.asStateFlow()
}
```

---

## 4. 实施路线图

```
Phase 1: 修复唤醒链路（P0 + P1）
  ├── Step 1: 修改 CMD_WAKE_BADA 广播目标（bridge 侧）  [1天]
  ├── Step 2: 添加 onAppleBleSeen 回调 + 去抖唤醒（Bada 侧）  [1天]
  └── Step 3: 添加 CMD_WAKE_BADA const + sendWakeBada()（Bada 侧）  [0.5天]
  └── 验证: adb logcat 看到 wake 广播触发 Bada 接收服务启动

Phase 2: 拆出控制通道（P2）
  ├── Step 4: 新增 CMD_ENABLE/DISABLE/STATUS（bridge 侧）  [1天]
  ├── Step 4b: 新增 MoseyControlClient（Bada 侧）  [1天]
  └── 验证: adb shell 中 `nc 127.0.0.1 19539` 发送 enable/disable/status 收到正确回复

Phase 3: Bada UI 集成 + 补 100-continue（P3）
  ├── Step 5: MoseyControlClient 集成到设置页  [2天]
  ├── Step 6: AirDropLocalServer 添加 100-continue  [0.5天]
  ├── Step 7: StatusRepository 实现  [0.5天]
  └── 验证: 真机苹果 ↔ Bada 双向文件收发完整通过

Phase 4: 端到端测试与调优
  ├── Bada 接收：iPhone 发文件给手机 → Bada 接收通知 → 同意 → 文件落盘
  ├── Bada 发送：Bada 发送页面 → iPhone 接收 → 完成
  ├── WiFi 切换：启用/关闭 Mosey 时 WiFi 自动断开/恢复
  ├── 后台唤醒：手机锁屏 → iPhone 发文件 → Bada 被唤醒 → 通知 → 接收
  └── 异常恢复：bridge 崩溃 → 自动恢复 → 订阅重新建立
```

### 优先级

| 优先级 | Phase | 估计工时 | 依赖 |
|--------|-------|----------|------|
| 🔴 P0 | Phase 1（唤醒链路） | 2.5 天 | — |
| 🔴 P0 | Phase 2（控制通道） | 2 天 | Phase 1 |
| 🟡 P1 | Phase 3（UI + 100-continue） | 3 天 | Phase 2 |
| 🟢 P2 | Phase 4（端到端测试） | 2 天 | Phase 3 |

---

## 5. 文件变更清单

### bridge 侧

| 文件 | 操作 | 说明 |
|------|------|------|
| `mosey_bridge.c` | 修改 | 修 CMD_WAKE_BADA 广播目标；新增 CMD_ENABLE/DISABLE/STATUS handler |

### Bada 侧（discovery-android）

| 文件 | 操作 | 说明 |
|------|------|------|
| `.../medium/MoseyEventHandler.kt` | 修改 | 新增 `onAppleBleSeen()` 接口方法 |
| `.../medium/MoseySocketClient.kt` | 修改 | 新增 `CMD_WAKE_BADA` 常量 + `sendWakeBada()`；`handleEvent()` 中 type=3 回调 |
| `.../medium/MoseyControlClient.kt` | **新建** | 独立控制客户端：enable/disable/status/wakeBada |
| `.../MoseyWakeHandler.kt` 或 `.../medium/MoseyWakeHandler.kt` | **新建** | 去抖唤醒逻辑，连接 MoseyEventHandler 和 CMD_WAKE_BADA |

### Bada 侧（service-android）

| 文件 | 操作 | 说明 |
|------|------|------|
| `.../airdrop/AirDropLocalServer.kt` | 修改 | 添加 `Expect: 100-continue` 处理 |
| `.../airdrop/StatusRepository.kt` | **新建** | 根侧/应用侧状态聚合 |

### Bada 侧（app）

| 文件 | 操作 | 说明 |
|------|------|------|
| `.../ui/settings/MoseySettingsScreen.kt` | **新建** | Compose 设置屏：开关 + 状态面板 |
| `app/src/main/AndroidManifest.xml` | 修改（可能） | 添加新 Activity 或 Fragment |

---

## 6. 测试计划

### Level 1：Bridge 命令测试（ADB）

```bash
# 验证 CMD_ENABLE（hex: 06）
printf '\x01\x05\x00\x00\x00\x06' | nc 127.0.0.1 19539 | xxd
# 预期返回: FRAME_REPLY(0x02) + len(8) + status(0) + value(0)

# 验证 CMD_STATUS（hex: 08）
printf '\x01\x05\x00\x00\x00\x08' | nc 127.0.0.1 19539
# 预期返回: FRAME_REPLY + JSON

# 验证 CMD_WAKE_BADA（hex: 05）
printf '\x01\x05\x00\x00\x00\x05' | nc 127.0.0.1 19539 | xxd
# 验证后: dumpsys activity services dev.bluehouse.bada.debug 应看到 ReceiverForegroundService

# 验证 CMD_DISABLE（hex: 07）
printf '\x01\x05\x00\x00\x00\x07' | nc 127.0.0.1 19539 | xxd
# 预期: WiFi 恢复, mosey 进程退出
```

### Level 2：Wake Receiver 验证

```bash
# 模拟 bridge 发送 WAKE 广播
adb shell am broadcast -a dev.bluehouse.bada.airdrop.WAKE \
  -n dev.bluehouse.bada.debug/dev.bluehouse.bada.service.airdrop.AirDropWakeReceiver \
  --include-stopped-packages

# 验证 ReceiverForegroundService 是否启动
adb shell dumpsys activity services dev.bluehouse.bada.debug \
  | grep -A5 ReceiverForegroundService

# 验证 AirDropLocalServer 是否监听 19541
adb shell ss -tlnp | grep 19541
```

### Level 3：端到端 Apple AirDrop

| 场景 | 步骤 | 预期 |
|------|------|------|
| iPhone → 手机 /Ask 成功 | 手机锁屏 → iPhone 分享 → 选设备 | 手机弹出接收通知 |
| 用户接受 /Upload | 点击"接受" | 文件写入 Downloads |
| 用户拒绝 | 点击"拒绝" | iPhone 显示已拒绝 |
| 手机 → iPhone | 打开发送页面 → 选 Apple 设备 → 选文件 → 发送 | iPhone 弹出接收通知 |
| WiFi 切换 | 启用 Mosey → WiFi 断开；禁用 → WiFi 恢复 | 自动切换无异常 |

---

## 7. 附录：代码证据

### 7.1 ReceiverForegroundService exported=false

**文件**: `Bada/service-android/src/main/AndroidManifest.xml` 第 28-31 行

```xml
<service
    android:name=".receiver.ReceiverForegroundService"
    android:exported="false"          <!-- ← 跨包不可达 -->
    android:foregroundServiceType="connectedDevice" />
```

### 7.2 AirDropWakeReceiver exported=true

**文件**: `Bada/service-android/src/main/AndroidManifest.xml` 第 37-41 行

```xml
<receiver
    android:name=".airdrop.AirDropWakeReceiver"
    android:exported="true">          <!-- ← 跨包可达 -->
    <intent-filter>
        <action android:name="dev.bluehouse.bada.airdrop.WAKE" />
    </intent-filter>
</receiver>
```

### 7.3 CMD_WAKE_BADA 当前实现（错误）

**文件**: `mosey_bridge.c` 第 516-523 行

```c
system("/system/bin/am force-stop dev.bluehouse.bada.debug >/dev/null 2>&1; "
       "/system/bin/am start-foreground-service -n "
       "dev.bluehouse.bada.debug/dev.bluehouse.bada.service.receiver.ReceiverForegroundService "
       ">/dev/null 2>&1");
```

### 7.4 EVENT_APPLE_BLE_SEEN 仅日志无回调

**文件**: `MoseySocketClient.kt` 第 213 行

```kotlin
EVENT_APPLE_BLE_SEEN -> Log.d(TAG, "Apple BLE wakeup observed: $jsonText")
```

### 7.5 bridge 单订阅者模式

**文件**: `mosey_bridge.c` 第 72, 366-374, 376-393 行

```c
static client_ctx_t* g_subscriber = NULL;
// set_subscriber() 替换旧订阅者
// emit_event() 发送给唯一订阅者
```
