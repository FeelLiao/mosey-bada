# Bada Mosey (AWDL/AirDrop) 集成开发方案

> **版本**: v3.5 — v1.27 Apple 反向发现与 IPv6 单播修复
> **日期**: 2026-06-13
> **状态**: 
> - ✅ KSU 模块 v1.27：基础 APK versionCode 27，更新 APK versionCode 28
> - ✅ `mosey_server`、preload UNIX proxy、`mosey_bridge`、shim FGS 运行链路已建立
> - ✅ bridge `getVersion/update/start/stop` 参数和 AIDL reply 语义已按反编译结果实现
> - ✅ Bada 以 `MoseySocketClient` 连接 bridge 为主，direct Binder 仅作回退
> - ✅ 国家码从 Android Wi-Fi 服务动态读取，不再写死 US/CN
> - ✅ Apple manufacturer ID `0x004C` BLE 原始数据已在真机观察到
> - ✅ v1.25 修复：启动 Mosey 前按原版逻辑协调并断开冲突的主 Wi-Fi STA
> - 🔧 v1.26 修复：绕过无法授予的 `NETWORK_FACTORY`，直接在 `mosey0` 上收发和解析 `_airdrop._tcp.local` mDNS
> - 🔧 v1.27 修复：按原版广播 `flags=489` 和轮换服务 ID，HTTPS 绑定 link-local，并动态维护 `mosey0` IPv6 策略路由
> - ⬜ 刷入 v1.27 后验证 Apple 双向发现和 HTTPS `/Discover`
> - ✅ Bada 接收端 MoseyMediumProvider 已注册
> **目标**: 使 Bada 能够通过 AWDL 发现 Apple 设备（iPhone/Mac），并实现 AirDrop HTTPS `/Discover`、`/Ask`、`/Upload` 与 DVZIP 文件收发

## 2026-06-13 真机根因与当前实现

v1.24 的应用、PMS、前台服务、BLE、LOWI、server、Binder/UNIX backend 和 bridge 均正常，但 `start([149,44,6])` 返回 `EX_SERVICE_SPECIFIC`，且没有创建 `mosey0`。内核日志显示 wondertap 已接收频道、速率、BSSID 和真实国家码，失败发生在 Qualcomm WLAN 并发策略检查。

通过 kretprobe 读取真实返回值：

```text
hdd_start_station_adapter ret=0
hdd_start_adapter ret=0
policy_mgr_allow_concurrency ret=0
wlan_hdd_wondertap_init ret=-1
```

因此 `Device mode 17 invalid` 只是 DP/SME 的非致命旁路能力日志，直接阻塞点是 `policy_mgr_allow_concurrency()` 拒绝当前主 Wi-Fi STA 与 wondertap 并发。反编译的原版 `MoseyController.e()` 在 Binder `start()` 前会读取当前 STA 频道；当前 STA 与 Mosey 频道同频段或频道未知时，先调用 `WifiManager.disconnect()` 并等待断开。本项目此前遗漏了这一层协调。

v1.25 起 shim 复刻该时序：

1. 从 `WifiInfo` 读取当前 networkId、frequency 和 supplicant state。
2. 对 `[149,44,6]` 覆盖的 5 GHz/2.4 GHz 频段，调用 framework `WifiManager.disconnect()`。
3. 最多等待 10 秒，确认 STA 不再处于关联/认证/已连接状态后才发送 bridge `update(country)` 和 `start(channels)`。
4. 启动失败或服务退出时调用 `WifiManager.reconnect()`，避免永久断网。
5. radio 成功后等待真实 `mosey0` 和 IPv6 link-local 地址，再启动 HTTPS、BLE 与原始 mDNS 通路。

v1.25 真机进一步确认 `wondertap0`、`mosey0`、bridge 命令和 radio 数据包均正常；剩余失败发生在 shim 调用 `NetworkAgent.register()` 时。系统抛出 `Requires android.permission.NETWORK_FACTORY or MAINLINE_NETWORK_STACK`。AOSP 将 `NETWORK_FACTORY` 定义为 signature/role 权限，privapp allowlist 和 `pm grant` 均不能把它授予模块自签名 APK，因此继续修 PMS、HybridMount 或权限 XML 不会解决此问题。

v1.26 不再向 ConnectivityService 注册伪 Android `Network`，也不再依赖 `NsdManager` 的 network-bound API。`RawMdnsEngine` 直接执行：

1. 在 `mosey0` 上加入 IPv6 mDNS 组 `ff02::fb:5353`，TTL 固定为 255。
2. 每 5 秒查询 `_airdrop._tcp.local`，解析压缩 DNS name 及 PTR、SRV、TXT、AAAA/A 记录。
3. 只有组合出真实 host 和 port 后才发送 `airdrop_found`；45 秒未刷新则发送 `airdrop_lost`。
4. 广播本机 PTR/SRV/TXT/AAAA，并响应 Apple 侧查询，使苹果设备能够解析本机 HTTPS `/Discover` 端口。
5. BLE manufacturer `0x004C` 首字节 `0x05` 仍只触发唤醒广播和诊断事件，不直接创建设备。

bridge 仍使用反编译确认的普通 Binder 作为 `start()` 生命周期 token。它不是 discovery callback；v1.26 启动 NDK Binder thread pool 仅用于 direct-Binder 生命周期与 `linkToDeath` 健壮性，发现事件来自 `mosey0` mDNS，而不是该 token 的 transaction。

v1.26 真机已解析到 Apple peer `fe80::...%mosey0:8770`，证明 AWDL 和原始 mDNS 正常。但 Android 把 `fe80::/64 dev mosey0` 放入动态专用表（真机当次为 `1032`），没有 ConnectivityService 创建的 rule 指向该表，导致单播报 `Network is unreachable`。v1.27 由 root 控制层动态读取表号和本机 link-local 地址，维护 `from <link-local>/128` 与 `oif mosey0` 两条策略规则；watchdog 在接口重建后恢复，卸载时清理。

同一轮反编译确认原版 `Mosey.ReceiveProvider` 的接收端注册使用轮换服务名，TXT 仅包含 `flags=489`。v1.27 移除错误的 `_dc=1` 与 `flags=0x1`，使用 6 字节随机服务 ID，并将 TLS `/Discover` socket 直接绑定到 `mosey0` 的 IPv6 link-local 地址，使 Apple 能解析并连接本机。

当前正式架构为 `Bada -> 127.0.0.1:19539 bridge -> direct Binder 或 preload UNIX backend -> mosey_server`。`19540` 只承载 shim JSON discovery 事件，bridge 将其转换成订阅客户端的 `FRAME_EVENT`。文档后续章节中的早期 Binder-first 描述仅作为历史设计记录，以本节和实际代码为准。

文件协议边界也已由反编译修正：`mosey_server` 只负责 AWDL radio，不能承载 Quick Share 的 UKEY2/TCP 文件流。Apple 互操作必须由独立 HTTPS 层完成。发送顺序是 `/Discover` 读取接收端 plist、`/Ask` 请求用户确认、`/Upload` 以 `application/x-dvzip` 上传；接收端需要解析 plist、把确认请求交给 Bada UI，并安全解包到 Downloads。v1.28 先把 bridge 发现事件接入 Bada 的统一设备列表，并将 AirDrop 建模为独立 route；在 Bada 接收服务接管之前，shim 对 `/Ask` 和 `/Upload` 返回 503，避免此前 HTTP 200 空 plist 造成协议伪成功和数据丢失。

v1.29 完成文件协议职责拆分：shim 的 AWDL TLS listener 保留 `/Discover`，并把 `/Ask`、`/Upload` 流式代理到 Bada 本地 `127.0.0.1:19541`。代理支持 `Content-Length`、chunked body 和 `100-continue`，不会把大文件装入 shim 内存。Bada 发送端使用 binary plist 完成 Discover/Ask，按反编译结果生成 old-ASCII CPIO (`application/x-dvzip`) 后上传；接收端解析 Ask、显示独立的接受/拒绝通知，接受后安全校验 CPIO 相对路径并复用现有 Downloads/SAF 写入策略。Apple AirDrop 与 Quick Share 仍是完全独立的传输状态机。

真机还确认 ColorOS/OPlus freezer 会让仅靠网络入站等待的 Bada 进程停在 `do_freezer_trap`，即使本地 listener 仍显示在 `ss` 中。v1.29 控制层因此在检测到 release/debug Bada 包时同步配置 `RUN_IN_BACKGROUND`、`RUN_ANY_IN_BACKGROUND` 和 device-idle whitelist；Bada 自身的 Mosey subscriber 也改为连接失败后 1/2/4...30 秒退避重连，bridge 短暂重启不再抛异常杀死主进程。

---

## 目录

1. [项目现状评估](#1-项目现状评估)
2. [架构总览](#2-架构总览)
3. [KSU Magic Mount 架构](#3-ksu-magic-mount-架构)
4. [技术障碍与解决方案](#4-技术障碍与解决方案)
5. [详细实施路线图](#5-详细实施路线图)
6. [文件修改清单](#6-文件修改清单)
7. [测试计划](#7-测试计划)
8. [风险与未知问题](#8-风险与未知问题)
9. [附录](#9-附录)

---

## 1. 项目现状评估

### 1.1 已完成的工作

通过对 Bada 代码库、KSU 模块和反向工程文档的全面审查，以下组件已经实现：

#### Bada 核心协议层 (`core-protocol`)

| 组件 | 文件 | 状态 |
|------|------|------|
| `Medium.MOSEY(13)` 枚举 | `core-protocol/.../medium/Medium.kt` | ✅ 已完成 |
| `MediumLadder` 优先级排序 | `core-protocol/.../medium/MediumLadder.kt` | ✅ 已完成 (MOSEY 排第3) |
| `UpgradePathCredentials.Mosey` | `core-protocol/.../medium/UpgradePathCredentials.kt` | ✅ 已完成 |
| `UpgradedTransport` 接口 | `core-protocol/.../medium/MediumProvider.kt` | ✅ 已完成 |
| `BandwidthUpgradeFrames` 编解码 | `core-protocol/.../connection/BandwidthUpgradeFrames.kt` | ✅ 已完成 (MOSEY 占位) |
| `MediumProvider` 接口 | `core-protocol/.../medium/MediumProvider.kt` | ✅ 已完成 |
| `MediumRegistry` 注册表 | `core-protocol/.../medium/MediumRegistry.kt` | ✅ 已完成 |

#### Bada Android 发现层 (`discovery-android`)

| 组件 | 文件 | 状态 |
|------|------|------|
| `MoseyBinderClient` (NDK Binder) | `discovery-android/.../medium/MoseyBinderClient.kt` | ✅ 已完成 |
| `MoseySocketClient` (TCP → bridge) | `discovery-android/.../medium/MoseySocketClient.kt` | ✅ **已完成** |
| `MoseyEventHandler` (事件接口) | `discovery-android/.../medium/MoseyEventHandler.kt` | ✅ **已完成** |
| `MoseyMediumProvider` (双路径) | `discovery-android/.../medium/MoseyMediumProvider.kt` | ✅ **已完成 (双路径: socket优先 + binder回退)** |
| `MoseyTransport` | `discovery-android/.../medium/MoseyTransport.kt` | ✅ 已完成 |
| `MediumRegistries` 注册 | `discovery-android/.../medium/MediumRegistries.kt` | ✅ 已完成 (已注册 MoseyMediumProvider) |
| `BadaApplication` 启动探测 | `app/.../BadaApplication.kt` | ✅ 已完成 |

#### KSU 模块 (`module_mosey`) — Magic Mount 模式

| 组件 | 状态 | 说明 |
|------|------|------|
| `mosey_server` 二进制注入 | ✅ 已完成 | `odm/bin/mosey_server` via magic mount |
| `libmosey_daemon_ffi.so` 注入 | ✅ 已完成 | `odm/lib64/libmosey_daemon_ffi.so` via magic mount |
| `mosey.rc` / VINTF manifest | ✅ 已完成 | `odm/etc/init/mosey.rc` + `odm/etc/vintf/manifest/manifest_mosey.xml` |
| SELinux sepolicy.rule | ✅ 已完成 | ksu + mosey_service 规则 |
| Linker namespace 修复 | ✅ 已完成 | `post-fs-data.sh` 添加 `/odm/${LIB}` 到 linker 默认命名空间 |
| `service.sh` 自动启动 | ✅ 已完成 | 直接启动 mosey_server（因 magic mount 后 init 已扫描完 .rc） |

### 1.2 待完成的工作

#### 🔑 关键发现 (2026-06-10): mosey_server 在默认 binder 上！

通过 ADB 诊断发现 `mosey_server` 实际上注册在 **默认 binder**（`/dev/binderfs/binder`），**而非 `/dev/vndbinder`**。

**诊断证据**:
```bash
# mosey_server 进程的 binder fd 指向默认 binder
$ ls -la /proc/3631/fd/3
lrwx------ 1 root root 64 ... 3 -> /dev/binderfs/binder    # ← 默认 binder!

# 从 shell 用户（无 root）可直接调用 getVersion() 成功
$ service call com.google.android.moseyservice.IMoseyService/default 16777215
Result: Parcel(00000000 00000001) # version = 1 ✅

# 且 servicemanager 能找到此服务（无需 --binder 参数）
$ service check com.google.android.moseyservice.IMoseyService/default
Service com.google.android.moseyservice.IMoseyService/default: found ✅
```

这意味着 `MoseyBinderClient`（直接 NDK Binder 调用）**无需 `mosey_bridge` 即可工作**。

**架构变更**: 从 `mosey_bridge` 桥接方案（v3.0）切换为 **直接 Binder 调用方案**（v3.1 Plan B'）。

#### 当前技术状态

| 问题 | 状态 | 说明 |
|------|------|------|
| mosey_server Binder 可达性 | ✅ **已解决** | 默认 binder，shell 可直接调用 |
| Parcel 参数格式 | ✅ **已修复** | 需要 `hasValue=1` 前缀（AIDL 标准） |
| start()/update() 调用（root） | ⚠️ 待验证 | Parcel 修复后需重新测试 |
| 回调事件解析 | ⬜ **待实现** | 需逆向 mosey_server 的 Binder 回调格式 |
| Apple 设备 → ShareTarget 转换 | ⬜ **待实现** | 解析回调后注入 Bada 发现流程 |
| 端到端传输测试 | ⬜ **待测试** | 验证 Apple ↔ Bada 文件收发 |

> **注意**: `MoseySocketClient` 和 `MoseyEventHandler` 已在 Bada 代码库中完整实现。`MoseyMediumProvider` 已调整为 **Binder 优先、Socket 回退** 的双路径策略。`mosey_bridge` C 程序保留作为 SELinux 限制时的后备方案，不再需要优先实现。

### 🔑 关键发现 (2026-06-10): MediumProvider ≠ 发送端发现

**开发方案中最重要的架构发现**:

`MoseyMediumProvider` 实现的是 `MediumProvider` 接口，用于**接收端 upgrade 流程**（`prepareUpgrade()`/`adoptUpgrade()`）。这是当别人发起 Quick Share 传输到 Bada 时，Bada 选择传输媒介的通道。

但**当用户主动发送文件时**，Bada 走的是完全不同的路径:

```
SendActivity
  └→ SendPeerPickerController
       └→ NearbyPeerDiscovery.browse()  ← 只聚合 mDNS + BLE + BT Classic!
```

`NearbyPeerDiscovery` 是**发送端**的发现机制，它聚合多个发现源（Flow）并输出 `NearbyPeerEvent`。Mosey/AWDL 从未被作为发现源加入！

**这意味着**: 即使 `MoseyMediumProvider` 已实现并注册，用户在发送文件界面依然看不到 Apple 设备。需要一个新的 `MoseyPeerScanner` 组件将 AWDL 发现的 Apple 设备注入 `NearbyPeerDiscovery`。

**传输层无需修改的关键洞见**: Apple 设备通过 AWDL 公开其 IP 地址。Bada 可以将 Apple 设备包装为 `NearbyPeer(lanEndpoint=192.168.x.x, port=44378)`，然后现有 `WifiLanTransferProvider` 会处理后续的 Quick Share TCP 传输。

---

## 2. 架构总览

### 2.1 完整系统架构

```
┌─ Bada (untrusted_app, 零 root) ──────────────────────────────────┐
│  discovery-android                                                │
│  ┌────────────────────────────────────────────────────────────┐   │
│  │ MoseyMediumProvider (双路径策略)                            │   │
│  │  ├─ [优先] MoseyBinderClient (直接 NDK Binder)             │   │
│  │  │    - ServiceManager.getService() 反射调用               │   │
│  │  │    - 直接 Binder transact (TR_CODE 1/2/3/0xFFFFFF)     │   │
│  │  │    - 回调 Binder 接收发现事件                             │   │
│  │  │                                                          │   │
│  │  └─ [回退] MoseySocketClient (TCP → mosey_bridge)           │   │
│  │       - TCP 127.0.0.1:19539 (loopback, SELinux 回退路径)   │   │
│  │       - 二进制帧协议: [type:u8][len:u32][payload...]       │   │
│  │                                                              │   │
│  │  解析回调 → 提取 Apple 设备 IP + 名称                        │   │
│  │  → 注入为 Bada ShareTarget (WIFI_LAN)                        │   │
│  └──────────────────────────┬─────────────────────────────────┘   │
│                             │ NDK Binder (默认 binder)            │
│                             │ OR TCP 127.0.0.1:19539 (回退)      │
└─────────────────────────────┼───────────────────────────────────┘
                              │
┌─ KSU Root Domain ──────────┼───────────────────────────────────┐
│                             │                                   │
│  ┌─ mosey_server (Rust) ───┴───────────────────────────────┐  │
│  │  AWDL 射频发现 (PF_PACKET + NL80211 + wonder.ko)         │  │
│  │  dlopen("libmosey_daemon_ffi.so")                        │  │
│  │  注册: com.google.android.moseyservice.IMoseyService     │  │
│  │  → 注册在 /dev/binder (默认 binder)                       │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  ┌─ [后备] mosey_bridge (C/NDK) ──────────────────────────┐  │
│  │  (仅当 SELinux 阻止直接 Binder 时启用)                   │  │
│  │  → AIBinder_getService(mosey_service)                    │  │
│  │  → listen TCP :19539                                     │  │
│  └──────────────────────────────────────────────────────────┘  │
│                                                                │
│  ┌─ KSU Magic Mount (OverlayFS) ──────────────────────────┐  │
│  │  /data/adb/modules/mosey-enabler/                       │  │
│  │    ├── odm/bin/mosey_server          → /odm/bin/        │  │
│  │    ├── odm/lib64/libmosey_daemon_ffi.so → /odm/lib64/   │  │
│  │    ├── odm/etc/init/mosey.rc         → /odm/etc/init/   │  │
│  │    └── odm/etc/vintf/manifest/*.xml  → /odm/etc/vintf/  │  │
│  └──────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 KSU Magic Mount 启动时序

```
Boot
  │
  ├─ [Early] init 扫描 /odm/etc/init/*.rc
  │    → mosey.rc 此时尚未挂载（KSU OverlayFS 未就绪）
  │    → mosey.rc 不会被 init 解析！
  │    → 解决方案: service.sh 直接启动二进制
  │
  ├─ [Early] init 读取 VINTF manifest
  │    → manifest_mosey.xml 此时尚未挂载
  │    → VINTF HAL 注册不会发生
  │    → 但 mosey_server 仍可通过 AServiceManager_addService 注册
  │
  ├─ [post-fs-data] KSU OverlayFS 挂载
  │    → /odm/bin/mosey_server 等文件在目标路径可见
  │    → post-fs-data.sh 执行:
  │        1. chmod 755 mosey_server
  │        2. 修改 linker 命名空间（添加 /odm/${LIB}）
  │        3. 验证所有文件已挂载
  │
  ├─ [late boot] service.sh 执行
  │    → 直接启动 mosey_server（从 /data 源路径）
  │    → mosey_server 以 ksu domain 运行
  │    → 注册 Binder 服务到 vndbinder
  │    → 启动 mosey_bridge（监听 TCP :19539）
  │
  └─ [user unlock] CE 存储解密
       → Bada 启动 → MoseyMediumProvider 探测
       → MoseySocketClient 连接 mosey_bridge
       → 发送 start() 命令 → mosey_bridge 转发到 mosey_server
       → AWDL 发现启动
```

### 2.3 关键架构决策

| 决策 | 选择 | 理由 |
|------|------|------|
| Binder 访问方式 | **直接 NDK Binder（默认 binder）** | ADB 诊断确认 mosey_server 注册在 `/dev/binderfs/binder`，`untrusted_app` 可直接调用 |
| 文件传输 | **复用 Bada 现有 WIFI_LAN TCP** | mosey_server 只做发现，传输走标准 Quick Share TCP |
| 发现事件传递 | **Binder 回调直接转发** | Bada 通过 `Binder.onTransact()` 接收 mosey_server 的发现回调 |
| Binder 客户端语言 | **Kotlin (Android Binder API)** | 使用 `ServiceManager` 反射调用 + `Parcel.transact()` |
| 桥接回退方案 | **mosey_bridge (C/NDK)** | 仅当 SELinux 阻止直接 Binder 时启用 |
| KSU 挂载方式 | **Magic Mount (OverlayFS)** | KSU 自动 bind-mount，无需自定义 mount 脚本 |
| mosey_server 启动 | **service.sh 直接启动** | magic mount 在 init 之后，.rc 不会被解析 |
| Bada 连接策略 | **Binder 优先 → Socket 回退** | Binder 更快（无中间层），Socket 作为 SELinux 限制时的备选 |
| GMS 依赖 | **无** | Bada 直接调用 mosey_server NDK Binder，完全绕过 GMS |

### 2.4 数据流

```
Phase 1: AWDL 发现 (直接 Binder 方案)
  iPhone ──AWDL beacon──→ wonder.ko ──NL80211──→ mosey_server
                                                    │
                                                    │ NDK Binder onTransact (回调):
                                                    │ { deviceName, ipv4, ipv6, mac, ... }
                                                    ▼
                                                  Bada (MoseyMediumProvider)
                                                    │ MoseyBinderClient callback
                                                    │ → MoseyEventHandler.onDeviceDiscovered()
                                                    │ → 解析 → ShareTarget(WIFI_LAN)
                                                    ▼
                                                  Bada 发现流程

Phase 2: Quick Share 传输 (Bada 现有能力)
  Bada ←── TCP 44378 (加密) ──→ iPhone
       ←── 标准 Quick Share 帧 ──→
       ←── 文件数据 (protobuf 分段) ──→
```

---

## 3. KSU Magic Mount 架构详解

### 3.1 什么是 KernelSU Magic Mount

KernelSU 的 Magic Mount 是一种基于 **OverlayFS** 的文件挂载机制。当 KSU 模块在 `post-fs-data` 阶段被激活时，KSU 内核驱动会在目标分区路径上创建 OverlayFS 层，将模块目录中的文件覆盖到系统分区。

```
模块目录结构:                         挂载后可见:
/data/adb/modules/mosey-enabler/      /odm/
  ├── odm/bin/mosey_server      →       ├── bin/mosey_server
  ├── odm/lib64/libmosey_daemon_ffi.so → ├── lib64/libmosey_daemon_ffi.so
  ├── odm/etc/init/mosey.rc     →       ├── etc/init/mosey.rc
  └── ...                                └── ...
```

**关键特性**:
- **自动 bind-mount**: KSU 自动将模块目录下的文件映射到目标路径
- **无需 overlayfs.ko**: KSU 内核驱动直接处理，不需要内核模块
- **无需 tmpfs**: 不占用额外内存
- **文件级覆盖**: 只覆盖模块提供的文件，不修改原始分区

### 3.2 Magic Mount 的限制

| 限制 | 影响 | 缓解措施 |
|------|------|----------|
| **init 之后才挂载** | `mosey.rc` 不会被 init 解析 | `service.sh` 直接启动 mosey_server |
| **VINTF manifest 不被识别** | HAL 注册不会自动发生 | mosey_server 通过 `AServiceManager_addService` 运行时注册 |
| **SELinux 上下文不自动应用** | 文件可能以错误 context 运行 | `sepolicy.rule` 补充 ksu domain 规则 |
| **OverlayFS 文件只读** | 无法直接修改挂载后的文件 | 从 `/data` 源路径直接执行 |

### 3.3 模块文件布局

```
module_mosey/
├── module.prop              # 模块元数据 (KSU 必须)
├── customize.sh             # 安装时脚本 (KSU 自动执行)
├── post-fs-data.sh          # 早期启动脚本 (KSU 自动执行)
├── service.sh               # 晚期启动脚本 (KSU 自动执行)
├── uninstall.sh             # 卸载脚本
├── sepolicy.rule            # SELinux 策略 (KSU 自动加载)
├── odm/                     # → /odm (KSU magic mount)
│   ├── bin/
│   │   ├── mosey_server          # GLO ROM 提取的 Rust 二进制
│   │   └── mosey_bridge          # [待实现] C/NDK 桥接程序
│   ├── lib64/
│   │   └── libmosey_daemon_ffi.so  # GLO ROM 提取的 native 库
│   └── etc/
│       ├── init/
│       │   └── mosey.rc              # mosey_server 服务定义 (disabled)
│       └── vintf/manifest/
│           └── manifest_mosey.xml    # AIDL HAL VINTF 声明

```

### 3.4 启动脚本详解

#### `customize.sh` — 安装时

```bash
# KernelSU 两阶段安装:
# 阶段 1: 解压并执行 customize.sh
# 阶段 2: 解压剩余数据文件到 MODPATH
#
# 因此 customize.sh 中不能检查数据文件！
# 文件验证在 post-fs-data.sh 和 service.sh 中进行

# 主要工作:
# 1. 验证 KernelSU 环境 (KSU=true, KSU_MAGIC_MOUNT=enabled)
# 2. 验证目标分区 (odm) 已挂载
# 3. 创建持久化状态目录 /data/adb/mosey-enabler/
# 4. 输出安装信息
```

#### `post-fs-data.sh` — 早期启动

```bash
# 执行时机: KSU OverlayFS 挂载后立即执行
# 此时 /odm/bin/mosey_server 等文件已在目标路径可见

# 主要工作:
# 1. chmod 755 mosey_server (修复源文件执行权限)
# 2. 修改 linker 命名空间配置:
#    echo "namespace.default.permitted.paths += /odm/\${LIB}" >> /linkerconfig/ld.config.txt
#    → 让 mosey_server 能 dlopen("libmosey_daemon_ffi.so")
# 3. 验证所有 magic mount 文件已就绪
```

#### `service.sh` — 晚期启动

```bash
# 执行时机: sys.boot_completed=1 之后
# 此时 /data/data/ 仍不可访问 (FBE CE 加密)

# 主要工作:
# 1. 直接启动 mosey_server (从 /data 源路径):
#    nohup $MODDIR/odm/bin/mosey_server > /dev/null 2>&1 &
# 2. 等待 mosey_server 就绪后启动 mosey_bridge:
#    sleep 2
#    nohup $MODDIR/odm/bin/mosey_bridge > /dev/null 2>&1 &
# 3. 验证 Binder 服务注册:
#    service check com.google.android.moseyservice.IMoseyService
# 4. 记录完整状态日志到 /data/adb/mosey-enabler/service.log
```

### 3.5 Linker 命名空间修复

CN ROM 的默认 linker 命名空间**不包含** `/odm/${LIB}` 在 `permitted.paths` 中。`mosey_server` 在运行时 `dlopen("libmosey_daemon_ffi.so")` 会失败。

**修复**: 在 `post-fs-data.sh` 中修改 `/linkerconfig/ld.config.txt`：

```bash
# 在 /odm/priv-app 行后插入 /odm/${LIB}
sed -i '/^namespace.default.permitted.paths += \/odm\/priv-app$/a\
namespace.default.permitted.paths += /odm/${LIB}' /linkerconfig/ld.config.txt
```

### 3.6 SELinux 策略

`sepolicy.rule` 需要覆盖三个场景：

| 场景 | Domain | 规则 |
|------|--------|------|
| **直接启动** (service.sh) | `ksu` | `allow ksu mosey_service service_manager { add find }` |
| **init 启动** (未来) | `mosey_service` | `allow init mosey_exec file { read execute open }` |
| **Bada 访问** | `untrusted_app` | `allow untrusted_app mosey_service service_manager { find }` |

**关键发现**: CN ROM 已预置 `mosey_service` SELinux context 映射和 `mosey_app` domain 定义。`sepolicy.rule` 只需补充 ksu domain 和 binder 通信规则。

---

## 4. 技术障碍与解决方案

### 4.1 Binder 访问方式（已解决）

**问题（v3.0 假设）**: mosey_server 可能注册在 `/dev/vndbinder`，Bada 的 `untrusted_app` 域无法访问。

**实际发现（2026-06-10）**: ADB 诊断确认 mosey_server 注册在 **默认 binder**。

```bash
# mosey_server 的 binder fd → /dev/binderfs/binder (默认 binder)
$ ls -la /proc/3631/fd/3
lrwx------ 3 -> /dev/binderfs/binder

# shell 用户 (无 root) 可直接调用
$ service call com.google.android.moseyservice.IMoseyService/default 16777215
Result: Parcel(00000000 00000001)   # version = 1
```

**解决方案**: 直接 NDK Binder 调用（`MoseyBinderClient`）+ Parcel 格式修复。

**保留后备方案**: `mosey_bridge` TCP 桥接代码 (`mosey_bridge.c`) 已编写完成，作为 SELinux 规则阻止直接 Binder 访问时的后备方案。在 v3.1 架构中为**可选组件**，非必需。

### 4.2 KSU Magic Mount 时序问题

**问题**: KSU OverlayFS 在 `post-fs-data` 阶段挂载，但 init 在更早阶段已扫描完 `/odm/etc/init/*.rc`。

**影响**:
1. `mosey.rc` 不会被 init 解析 → mosey_server 不会自动启动
2. `manifest_mosey.xml` 不会被 VINTF 框架读取 → HAL 不会自动注册

**解决方案**:
1. `service.sh` 从 `/data` 源路径直接启动 mosey_server
2. mosey_server 通过 `AServiceManager_addService()` 运行时注册 Binder 服务
3. 进程以 `ksu` domain 运行（非 `mosey_service`），需在 `sepolicy.rule` 中添加对应规则

### 4.3 Linker 命名空间限制

**问题**: CN ROM 的 linker 默认命名空间不包含 `/odm/${LIB}`，`mosey_server` 的 `dlopen("libmosey_daemon_ffi.so")` 会失败。

**解决方案**: `post-fs-data.sh` 修改 `/linkerconfig/ld.config.txt`，添加 `/odm/${LIB}` 到 `namespace.default.permitted.paths`。

### 4.4 回调格式未知

**问题**: mosey_server 的 Binder 回调事务代码和数据格式尚未完全逆向。

**解决方案**: 分阶段处理：
1. 先用 `mosey_bridge` 捕获原始回调字节
2. 与 MoseyApp 反编译代码（`bkp.java`/`biq.java`）对比分析
3. 逐步实现解析逻辑

### 4.5 Apple Quick Share TCP 兼容性

**问题**: Apple 可能使用修改版的 Quick Share 协议。

**解决方案**:
1. 先用 GLO ROM 的 GMS 抓取 Apple ↔ Android 的 TCP 流量
2. 与 Bada 现有实现对比
3. 必要时调整协议细节

### 4.6 GMS 白名单检查（已解决）

**问题**: GMS 的 `eaac.a()` 方法检查 MoseyApp 是否在白名单中。CN ROM 的 GMS 可能不包含 MoseyApp 的白名单条目。

**解决方案**: **Bada 直接调用 mosey_server NDK Binder（绕过 GMS 完全）**。

Bada 通过 `mosey_bridge` 直接与 `mosey_server` 通信，完全绕过 GMS 和 MoseyApp。因此 GMS 白名单检查不构成障碍。

---

## 5. 详细实施路线图

### Phase 0: 环境验证 (1-2 天) — ✅ 已完成

**目标**: 确认 OnePlus 15 CN ROM 上 mosey_server 可运行

| 步骤 | 操作 | 验证方法 | 状态 |
|------|------|----------|------|
| 0.1 | 安装 `module_mosey` KSU 模块 | `ls /odm/bin/mosey_server` | ✅ |
| 0.2 | 验证 wonder.ko 已加载 | `lsmod \| grep wonder` | ✅ |
| 0.3 | 启动 mosey_server | `ps -A \| grep mosey` | ✅ |
| 0.4 | 验证 Binder 注册（默认 binder） | `service check IMoseyService` | ✅ |
| 0.5 | 测试 getVersion() | `service call IMoseyService 16777215` → version=1 | ✅ |
| 0.6 | 验证直接 Binder 可达性（shell 无 root） | `service call ... 16777215` 从 shell 返回版本 | ✅ |
| 0.7 | 验证 wonder.ko + wifi-aware0 | `iw dev` 可见 wifi-aware0 NAN 接口 | ✅ |

**依赖**: OnePlus 15 CN ROM + KernelSU + `module_mosey` 已安装

### Phase 0.5: 修复 Binder 直连通路 (1-2 天) — ✅ 已完成

**目标**: 修复 `MoseyBinderClient` 的 Parcel 格式，使 Bada 可直接调用 mosey_server

#### 0.5.1 ADB 诊断发现

2026-06-10 通过 ADB 诊断确认：

```bash
# mosey_server 在默认 binder 上！不是 vndbinder！
$ ls -la /proc/3631/fd/3
lrwx------ 3 -> /dev/binderfs/binder

# shell 用户可直接调用成功
$ service call com.google.android.moseyservice.IMoseyService/default 16777215
Result: Parcel(00000000 00000001)   # version = 1
```

#### 0.5.2 修复 Parcel 格式

**根因**: AIDL 生成的代理代码在序列化 `Parcelable` 参数前写入 `hasValue=1` int 前缀，`MoseyBinderClient` 遗漏了此标志。

**修改文件**: `MoseyBinderClient.kt`
- `start()`: 在 `writeIntArray` 前加 `data.writeInt(1)`
- `stop()`: 在 interface token 后加 `data.writeInt(1)`
- `update()`: 在 `writeString` 前加 `data.writeInt(1)`

#### 0.5.3 Bada 侧修改

| 文件 | 修改内容 | 状态 |
|------|----------|------|
| `MoseyBinderClient.kt` | 修复 Parcel 格式（3 处） | ✅ 已完成 |
| `MoseyMediumProvider.kt` | 交换优先级（Binder 优先→Socket 回退） | ✅ 已完成 |
| `BadaApplication.kt` | 交换探测顺序 | ✅ 已完成 |

### Phase 1: Bada 直接 Binder 调用 (1-2 天) — ✅ 已完成 (并入 Phase 0.5)

**目标**: `MoseyBinderClient` 直接调用 mosey_server NDK Binder

#### 1.1 Parcel 格式修复

AIDL 自动生成的代理代码对 `Parcelable` 参数会写入 `hasValue` 前缀：

```java
// AIDL 生成的代码 (bpq.java):
Parcel data = Parcel.obtain();
data.writeInterfaceToken(DESCRIPTOR);
// → bps Parcelable:
data.writeInt(1);        // hasValue: non-null  ← 之前遗漏了此标志！
data.writeIntArray(filters);
data.writeStrongBinder(callback);
data.writeInt(MAX_VALUE);
binder.transact(1, data, reply, 0);
```

已在 `MoseyBinderClient.kt` 的 `start()`、`stop()`、`update()` 方法中添加 `data.writeInt(1)`。

#### 1.2 双路径策略

`MoseyMediumProvider` 当前使用 **Binder 优先、Socket 回退** 的双路径策略：
1. 优先尝试 `MoseyBinderClient`（直接 NDK Binder，更快更可靠）
2. 回退尝试 `MoseySocketClient`（TCP → mosey_bridge，当 SELinux 阻止直接 Binder 时）

### Phase 1.5: 桥接回调转发 + 数据格式分析 (2-3 天) — ⬜ 待实现

**📌 当前阻塞点 — 第一步工作**

**目标**: 修复桥接回调转发，捕获原始数据，分析 mosey_server 的发现回调格式

#### 1.5.1 修复桥接回调转发

**当前问题**: `mosey_bridge.c` 中 `cb_on_transact` 只转发 `tx_code`（4 字节），丢弃了 `AParcel* in` 中的设备数据。

**修改方案**: 
```c
static binder_status_t cb_on_transact(AIBinder* binder,
                                       transaction_code_t code,
                                       const AParcel* in,
                                       AParcel* out) {
    // 读取 in Parcel 中的所有数据
    const void* raw_data;
    size_t data_len;
    // 用 AParcel_getDataSize / AParcel_view 等 API 读取原始字节
    // 然后转发: [tx_code:u32][data_len:u32][raw_data...]
}
```

**⚠️ 风险**: NDK Binder API 可能不直接暴露 `AParcel_getDataSize`。如果不可用，可能需要用 `AIBinder_dump` 或创建 "debug 模式" 来逐字段读取。

**输出**: 更新后的 `mosey_bridge.c`，编译并推送至设备。

#### 1.5.2 捕获原始回调数据

1. 重启桥接（使用新版）
2. 用 `test_bridge.py` 发送 `start(filters=[13])`
3. 在 Apple 设备上打开 AirDrop
4. 桥接通过 `LOGI("← Callback tx_code=%u, data=[...]", ...)` 输出回调数据
5. 用 `logcat -s MoseyBridge` 捕获

#### 1.5.3 参考 MoseyApp 逆向分析格式

| 源文件 | 位置 | 内容 |
|--------|------|------|
| `bkp.java` | `decompile/moseyapp/sources/defpackage/` | SendProvider — 发送发现回调处理 |
| `biq.java` | 同上 | ReceiveProvider — 接收发现回调处理 |
| `boj.java` | 同上 | 核心状态机 — 设备数据模型 |

**预期回调内容**（需验证）:
```
设备端点 ID (byte[] hash)
设备名称 (String — Apple AWDL TLV 中的 NSString)
IPv4 地址 (byte[4])
IPv6 地址 (byte[16] / 可选)
MAC 地址 (byte[6])
信号强度 (int)
服务类型 / medium 过滤器 (int)
```

### Phase 2: 结构化回调解析 (1-2 天) — ⬜ 待实现

**目标**: 根据分析结果实现结构化解析

#### 2.1 桥接侧结构化转发

更新 `cb_on_transact` 解析 Parcel 字段并构造结构化 TCP 事件帧:

```
Event frame (type=0x03) payload:
  [tx_code:i32]
  [device_name_len:i32][device_name:utf8]
  [ipv4:byte[4]] (0.0.0.0 if unavailable)
  [mac:byte[6]]
  [signal_strength:i32]
```

#### 2.2 Bada 侧 MoseySocketClient 解析

更新 `parseDiscoveredDevice()` 以解析结构化事件帧:

```kotlin
private fun parseDiscoveredDevice(data: ByteArray): AppleDevice? {
    var offset = 0
    val txCode = readInt32(data, offset); offset += 4
    val nameLen = readInt32(data, offset); offset += 4
    val name = data.copyOfRange(offset, offset + nameLen).decodeToString(); offset += nameLen
    val ipv4 = "${data[offset].toInt() & 0xFF}.${data[offset+1].toInt() & 0xFF}..."
    // ...
}
```

### Phase 3: 发送端 Mosey 发现集成 (2-3 天) — ⬜ 待实现

**📌 这是让用户能看到 Apple 设备的关键步骤**

#### 3.1 实现 MoseyPeerScanner

**新文件**: `discovery-android/.../discovery/MoseyPeerScanner.kt`

```kotlin
/**
 * Sender-side Mosey/AWDL peer scanner.
 *
 * Connects to mosey_bridge (TCP), starts AWDL discovery, and
 * emits NearbyPeerEvent when Apple devices are found.
 * Apple devices are injected as WIFI_LAN peers using their AWDL-announced
 * IP address, so Bada's existing WifiLanTransferProvider handles transfer.
 */
class MoseyPeerScanner(
    private val socketClient: MoseySocketClient = MoseySocketClient(),
) {
    fun scan(): Flow<NearbyPeerEvent> = callbackFlow {
        if (!socketClient.connect()) {
            Log.w(TAG, "mosey_bridge not available")
            close()
            return@callbackFlow
        }
        
        socketClient.setEventHandler(object : MoseyEventHandler {
            override fun onDeviceDiscovered(device: AppleDevice) {
                val ip = device.ipv4?.let { InetAddress.getByName(it) } ?: return
                val peer = NearbyPeer(
                    stableId = "mosey:${device.endpointId}",
                    endpointId = device.endpointId,
                    endpointInfo = null, // Apple 设备没有 Bada EndpointInfo
                    lanEndpoint = LanEndpoint(
                        addresses = listOf(ip),
                        port = 44378, // Quick Share TCP 默认端口
                    ),
                )
                trySend(NearbyPeerEvent.PeerAdded(peer))
            }
            
            override fun onDeviceLost(deviceId: String) {
                trySend(NearbyPeerEvent.PeerRemoved("mosey:$deviceId"))
            }
            
            override fun onDisconnected(reason: String) {
                close()
            }
        })
        
        socketClient.start(intArrayOf(13)) // AWDL filter
        
        awaitClose {
            socketClient.stop()
            socketClient.close()
        }
    }
}
```

#### 3.2 集成到 NearbyPeerDiscovery

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
            val lanJob = launch { /* ... */ }
            val bleJob = launch { /* ... */ }
            val bluetoothJob = launch { /* ... */ }
            
            val moseyJob = launch { // ← 新增
                moseyEvents.collect { event ->
                    trySend(event).isSuccess
                }
            }

            awaitClose {
                lanJob.cancel()
                bleJob.cancel()
                bluetoothJob.cancel()
                moseyJob.cancel()
            }
        }
}
```

#### 3.3 Apple 设备出现在发送 UI 中

`SendPeerPickerController` 自动显示所有 `NearbyPeerEvent.PeerAdded` 事件。Apple 设备以 `"iPhone 15 (AWDL)"` 形式显示，用户点击后将通过 WIFI_LAN 发送。

### Phase 4: 端到端验证与优化 (2-3 天) — ⬜ 待实现

#### 4.1 端到端发现测试

| 测试 | Apple 设备操作 | Bada 预期 |
|------|---------------|-----------|
| 发送端发现 | 打开 AirDrop | Bada 发送 UI 显示 "iPhone" |
| 设备离开 | 关闭 AirDrop | 设备从列表消失（超时后） |
| 多个设备 | 多台 Apple 设备 | 全部显示 |

#### 4.2 传输测试

| 方向 | 文件 | 预期 |
|------|------|------|
| Bada → Apple | 图片 5MB | Quick Share TCP 传输成功 |
| Bada → Apple | 文本 1KB | 同上 |

#### 4.3 优化

- 桥接连接超时 → 优雅降级
- 国家码自动检测（telephony → MNC/MCC）
- 仅在发送/接收时启动 AWDL
- bridge watchdog 监控
```

---

## 5. 文件修改清单

### 5.1 已完成的文件（Bada 侧 — 全部已有，无需修改）

| 文件 | 说明 | 状态 |
|------|------|------|
| `core-protocol/.../medium/Medium.kt` | 已包含 `MOSEY(13)` 枚举 | ✅ 已完成 |
| `core-protocol/.../medium/MediumLadder.kt` | 已包含 MOSEY 优先级排序（第3位） | ✅ 已完成 |
| `core-protocol/.../medium/UpgradePathCredentials.kt` | 已包含 `Mosey` data class | ✅ 已完成 |
| `core-protocol/.../medium/MediumProvider.kt` | 接口定义完整 | ✅ 已完成 |
| `core-protocol/.../medium/MediumRegistry.kt` | 注册表逻辑完整 | ✅ 已完成 |
| `core-protocol/.../connection/BandwidthUpgradeFrames.kt` | 已包含 MOSEY 占位编解码 | ✅ 已完成 |
| `discovery-android/.../medium/MoseyBinderClient.kt` | NDK Binder 客户端（**已修复 Parcel 格式**） | ✅ **PA-0.5** |
| `discovery-android/.../medium/MoseyMediumProvider.kt` | 双路径策略（**Binder 优先 + Socket 回退**） | ✅ **PA-0.5** |
| `discovery-android/.../medium/MoseySocketClient.kt` | TCP socket 客户端（保留作为回退） | ✅ 已完成 |
| `discovery-android/.../medium/MoseyEventHandler.kt` | 事件处理器接口 | ✅ 已完成 |
| `discovery-android/.../medium/MoseyTransport.kt` | Transport 实现 | ✅ 已完成 |
| `discovery-android/.../medium/AppleDevice.kt` | Apple 设备数据模型 | ✅ 已完成 |
| `discovery-android/.../medium/MediumRegistries.kt` | 已注册 MoseyMediumProvider | ✅ 已完成 |
| `discovery-android/.../medium/BadaMediumRegistries.kt` | 兼容工厂类 | ✅ 已完成 |
| `app/.../BadaApplication.kt` | 启动探测（**Binder 优先**） | ✅ **PA-0.5** |

### 5.2 待修改/创建的文件

| 文件 | 修改内容 | 优先级 | 阶段 |
|------|----------|--------|------|
| `mosey_bridge.c` | 修复 `cb_on_transact` 转发 Parcel 数据 | 🔴 P0 | Phase 1.5 |
| `discovery-android/.../MoseyPeerScanner.kt` | **新建** — 发送端 Mosey 扫描器 | 🔴 P0 | Phase 3 |
| `discovery-android/.../NearbyPeerDiscovery.kt` | 增加 `moseyEvents` 参数 | 🔴 P0 | Phase 3 |
| `discovery-android/.../MoseySocketClient.kt` | 更新 `parseDiscoveredDevice()` | 🟡 P1 | Phase 2 |
| `mosey_bridge.c` | 更新为结构化回调转发 | 🟡 P1 | Phase 2 |
| `tests/test_bridge.py` | 增加事件帧接收测试 | 🟡 P1 | Phase 1.5 |

### 5.3 可选文件（mosey_bridge 后备方案）

| 文件 | 说明 | 状态 |
|------|------|------|
| `module_mosey/odm/bin/mosey_bridge.c` | C 桥接程序源码 | ✅ 已编写并运行 |
| `module_mosey/odm/bin/mosey_bridge` | 编译后的 ARM64 二进制 | ✅ 已推送到设备 |

### 5.4 文件状态总览

```
项目文件状态图例:
  ✅ = 已完成并落地  ⬜ = 待实现  🛠 = 修改中

Bada 侧 (untrusted_app):
  core-protocol/                  → 7/7 ✅ (100%)
  discovery-android/ Mosey*       → 8/8 ✅ (100%, 含 AppleDevice)
  discovery-android/ MoseyPeerScanner.kt  → ⬜ P0 (新建)
  discovery-android/ NearbyPeerDiscovery.kt → 🛠 P0 (修改)
  app/ BadaApplication.kt         → 1/1 ✅ (100%)

桥接侧:
  mosey_bridge.c cb_on_transact   → 🛠 Phase 1.5 (先转发原始数据)
  mosey_bridge.c 结构化解析       → ⬜ Phase 2 (等格式分析后)

KSU 模块侧:
  mosey_server                    → ✅ 已运行
  mosey_bridge                    → ✅ 已运行
```

---

## 6. 测试计划

### 6.1 测试层级总览

```
测试策略:
  Level 0: 构建验证 (Bada APK 编译 + KSU 模块打包)
  Level 1: 组件单元测试 (bridge 协议、Bada 帧编解码)
  Level 2: 集成测试 (bridge ↔ mosey_server ↔ Bada)
  Level 3: 端到端测试 (Apple 设备 ↔ Bada 文件收发)
  Level 4: 回归测试 (现有 Bada 功能不受影响)
```

### 6.2 Level 0: 构建验证

#### 6.2.1 Bada APK 编译

```bash
# 在项目根目录
cd Bada
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :app:assembleDebug
# 预期: BUILD SUCCESSFUL
```

#### 6.2.2 KSU 模块打包

```bash
# 验证模块结构完整性
cd module_mosey
# 检查必需文件
ls -la module.prop customize.sh post-fs-data.sh service.sh sepolicy.rule
ls -la odm/bin/mosey_server odm/lib64/libmosey_daemon_ffi.so
ls -la odm/etc/init/mosey.rc odm/etc/vintf/manifest/manifest_mosey.xml
```

### 6.3 Level 1: 桥接回调转发测试（当前重点）

```bash
# 1. 编译新版桥接（含回调数据转发）→ 推送到设备
# 2. 用 Python 脚本发送 start() 命令
adb shell su -c '/data/data/com.termux/files/usr/bin/python /data/local/tmp/test_bridge.py start'

# 3. 在 Apple 设备上打开 AirDrop
# 4. 观察桥接日志
adb shell logcat -s MoseyBridge

# 预期: cb_on_transact: tx_code=X, data=[...hex...]
```

### 6.4 Level 2: 集成测试

#### 6.4.1 Bridge 协议验证（已完成）

```bash
# 已验证: getVersion() → 1
# 已验证: start() → status=0 (通过 Python 脚本)
# 待验证: 事件帧 (type=0x03) 是否能通过桥接正确转发
```

#### 6.4.2 Bada MoseyPeerScanner 集成测试

待 `MoseyPeerScanner` 实现后：

```bash
# 1. 启动 Bada 发送界面
adb shell am start -n dev.bluehouse.bada.debug/dev.bluehouse.bada.send.SendActivity

# 2. 观察日志
adb shell logcat -s MoseyPeerScanner MoseySocketClient NearbyPeerDiscovery

# 3. 预期日志序列:
# MoseyPeerScanner: connecting to mosey_bridge...
# MoseySocketClient: Connected to mosey_bridge at 127.0.0.1:19539
# MoseyPeerScanner: start(filters=[13])...
# ...Apple 设备打开 AirDrop...
# MoseyEventHandler: Device discovered: iPhone (xxx)
# MoseyPeerScanner: injecting NearbyPeer(device=iPhone, ip=192.168.x.x)
# NearbyPeerDiscovery: PeerAdded(mosey:xxx)
```

### 6.5 Level 3: 端到端测试

#### 6.5.1 发现测试

**设备要求**:
- OnePlus 15 CN ROM + module_mosey（mosey_server 运行中）
- Apple 设备（MacBook / iPhone）开启 AirDrop

**测试步骤**:
1. 确保 mosey_server 正在运行（无需 bridge）
2. 启动 Bada → MoseyMediumProvider.prepareUpgrade()
3. Apple 设备打开 AirDrop → 应看到 "OnePlus 15" ✅
4. Bada 收到回调 → 提取 Apple 设备 IP + 名称
5. 验证 `MoseyEventHandler.onDeviceDiscovered()` 被调用

**验证点**:
- [ ] Apple 设备出现在 Bada 发现列表中
- [ ] 设备名称正确显示（如 "iPhone 15"）
- [ ] IP 地址正确提取（用于后续 TCP 传输）
- [ ] 多个 Apple 设备同时发现时全部列出
- [ ] 设备离开后触发 `onDeviceLost()`

#### 6.5.2 传输测试

**测试步骤**:
1. Apple → Bada: 从 iPhone/Mac 分享文件到 OnePlus
   - Bada 通过 Quick Share TCP 接收 ✅
   - 文件完整性校验（md5sum）
2. Bada → Apple: 从 Bada 分享文件到 iPhone/Mac
   - Bada 通过 WifiLanTransferProvider 发送 ✅
   - Apple 通过 AirDrop 接收

**测试文件**:
| 文件类型 | 大小 | 说明 |
|----------|------|------|
| 文本文件 | 1 KB | 基础功能验证 |
| 图片 | 5 MB | 中等大小文件 |
| 视频 | 100 MB | 大文件分片传输 |
| 文件夹 | 10 MB | 多文件传输 |

### 6.6 Level 4: 回归测试

确保现有 Bada 功能不受 mosey 集成影响：

| 测试场景 | 预期结果 |
|----------|----------|
| Android ↔ Android Quick Share | 正常工作 |
| Bada 关闭时无 mosey 相关日志 | 无异常错误 |
| mosey_server 未运行时 Bada 正常启动 | 优雅降级，无崩溃 |
| Binder 连接中断后 Bada 自动重连 | 服务恢复（`isSupported()` 重新探测） |
| WiFi 切换时 AWDL 不受影响 | 信道自动调整 |

### 6.7 设备矩阵

| 设备 | 角色 | 预期结果 |
|------|------|----------|
| OnePlus 15 CN + module_mosey | Bada (AWDL 发现) | 在 AWDL 上广播，接收回调 |
| MacBook (macOS Sequoia) | AirDrop 发送方 | 发现 OnePlus，通过 Quick Share 发送文件 |
| iPhone (iOS 19+) | AirDrop 发送方 | 同 MacBook |
| OnePlus 15 GLO (原厂) | 参考基准 | 对比抓取流量，验证协议兼容性 |
| Pixel 9 (GMS 原生) | 参考基准 | 验证 mosey_server 的 Pixel 兼容性 |

---

## 7. 风险与未知问题

### 7.1 技术风险

| 风险 | 影响 | 概率 | 缓解措施 |
|------|------|------|----------|
| **mosey_server 回调格式未知** | 无法解析发现事件 | 高 | 先用 bridge 捕获原始字节，参考 MoseyApp 反编译代码分析 |
| **Apple Quick Share TCP 不兼容** | 文件传输失败 | 中 | 抓取 GMS→Apple 流量对比，必要时调整协议细节 |
| **mosey_bridge 稳定性** | bridge 崩溃 = 无发现（仅回退路径） | 低 | 非必需组件，仅在 SELinux 阻止直接 Binder 时需要 |
| **AWDL 信道冲突** | 干扰正常 WiFi | 中 | mosey_server 处理信道切换，用 logcat 监控 |
| **mosey_server 无 MoseyApp 时行为异常** | 某些 Binder 调用失败 | 中 | 独立测试每个 TR_CODE，验证无 MoseyApp 时的行为 |
| **国家码不匹配** | AWDL 在错误信道上被禁 | 低 | 默认 "US" 5GHz，回退 "CN" 5.8GHz |
| **KSU 版本兼容性** | Magic Mount 行为变化 | 低 | 锁定 KSU 版本，测试后再升级 |
| **SELinux 策略拒绝 bridge 访问 vndbinder** | bridge 无法连接 mosey_server | 中 | sepolicy.rule 补充 ksu domain 规则 |
| **linkerconfig 修改被 OTA 覆盖** | dlopen 失败 | 低 | post-fs-data.sh 每次启动都执行修复 |

### 7.2 缓解策略优先级（2026-06-10 更新）

```
P0 阻塞性 (当前):
  ├─ 桥接 cb_on_transact 转发 Parcel 数据 (当前只送 tx_code) 🔴
  ├─ 回调数据格式捕获与分析 🔴
  ├─ MoseyPeerScanner 实现 + NearbyPeerDiscovery 集成 🔴
  └─ Apple 设备注入发送端发现列表 🔴

P1 功能性:
  ├─ 结构化回调解析 (桥接 + Bada)
  ├─ 国家码管理 (AWDL 信道选择)
  └─ 传输测试 (Apple ↔ Bada Quick Share TCP)

P2 稳定性:
  ├─ 桥接连接监控与重连
  ├─ 连接超时/重试逻辑
  └─ 电源优化 (空闲停止 AWDL)

P3 兼容性:
  ├─ Apple Quick Share TCP 协议适配
  ├─ 多设备并发发现
  └─ KSU 版本升级兼容
  └─ KSU 版本升级兼容
```

### 7.3 未知问题

| 问题 | 说明 | 调查方法 |
|------|------|----------|
| mosey_server 回调的精确事务代码 | 需要从 MoseyApp 反编译中确认 | 分析 `bkp.java`/`biq.java` 的 Binder 调用 |
| Apple 设备是否接受来自非 Apple 设备的 Quick Share 连接 | 需要实际测试 | GLO ROM 抓包对比 |
| mosey_server 是否需要 MoseyApp 的特定初始化状态 | 需要独立测试 | 逐步测试每个 TR_CODE |
| 多个 Apple 设备同时发现时的行为 | 需要实际测试 | 多设备场景测试 |
| bridge 回调 Binder 的精确 AIDL 接口定义 | 需要逆向 | 从 MoseyApp 反编译中提取 |
| AWDL 发现延迟（从启动到首次发现） | 需要实测 | logcat 时间戳分析 |

### 7.4 依赖项

| 依赖 | 版本/来源 | 说明 | 替代方案 |
|------|-----------|------|----------|
| OnePlus 15 CN ROM | PLK110_11.A.63_0630 | 目标设备 | 其他 OPPO/OnePlus 设备（需测试） |
| KernelSU | 最新稳定版 | root 解决方案 | APatch / Magisk（需适配） |
| `module_mosey` | workspace 中 | KSU 模块，提供 mosey_server 等文件 | 无（核心依赖） |
| Android NDK | r27+ | 交叉编译 mosey_bridge | 无（必须） |
| `libbinder_ndk.so` | 系统库（Android 9+） | NDK Binder 支持 | 无（系统内置） |
| `wonder.ko` | Qualcomm 芯片驱动 | AWDL 射频支持 | 无（内核模块） |
| `libmosey_daemon_ffi.so` | GLO ROM 提取 | mosey_server 运行时依赖 | 无（核心依赖） |

---

## 8. 附录

### 8.1 参考文档

| 文档 | 位置 |
|------|------|
| ROM 研究 (GLO vs CN) | `MOSEY_RESEARCH.md` |
| 反向工程 (AIDL, GMS) | `MOSEY_REVERSE_ENGINEERING.md` |
| AirDrop 集成方案 | `Bada/docs/research/mosey-airdrop-integration.md` |
| 仓库记忆 (技术笔记) | `memory/repo/mosey-extended.md` |
| Bada 架构 | `Bada/docs/architecture.md` |
| Bada CLAUDE.md | `Bada/CLAUDE.md` |
| KSU 模块源码 | `module_mosey/` |
| MoseyApp 反编译 | `decompile/moseyapp/sources/` |
| GMS 反编译 | `decompile/gms/sources/` |

### 8.2 关键类索引

| 类 | 位置 | 说明 |
|-----|------|------|
| `bpq.java` | `decompile/moseyapp/sources/defpackage/bpq.java` | NDK Binder proxy (OnePlus) |
| `bpr.java` | `decompile/moseyapp/sources/defpackage/bpr.java` | NDK AIDL 接口 (OnePlus) |
| `bmz.java` | `decompile/moseyapp/sources/defpackage/bmz.java` | Java 包装 (OnePlus) |
| `bnc.java` | `decompile/moseyapp/sources/defpackage/bnc.java` | Java 接口定义 |
| `bps.java` | `decompile/moseyapp/sources/defpackage/bps.java` | Start 参数 |
| `bpt.java` | `decompile/moseyapp/sources/defpackage/bpt.java` | Stop 参数 |
| `bpu.java` | `decompile/moseyapp/sources/defpackage/bpu.java` | Update 参数 |
| `bgd.java` | `decompile/moseyapp/sources/defpackage/bgd.java` | 控制器 |
| `bkp.java` | `decompile/moseyapp/sources/defpackage/bkp.java` | 发送发现提供者 |
| `biq.java` | `decompile/moseyapp/sources/defpackage/biq.java` | 接收发现提供者 |
| `boj.java` | `decompile/moseyapp/sources/defpackage/boj.java` | 核心状态机 |

### 8.3 调试命令速查

```bash
# 检查 mosey_server Binder 服务 (默认 binder — 无需 --binder 参数)
service check com.google.android.moseyservice.IMoseyService/default

# 查看 mosey_server 进程
ps -A | grep mosey
lsof -p $(pidof mosey_server)

# 检查 wonder 内核模块
lsmod | grep wonder
iw dev

# 测试 getVersion() (从 shell, 无 root 也可)
service call com.google.android.moseyservice.IMoseyService/default 16777215

# Bada 调试日志
adb logcat -s MoseyDiag MoseyBinderClient MoseyMediumProvider MoseySocketClient

# 检查 SELinux 上下文
ps -AZ | grep mosey

# 验证 Binder 设备
ls -la /proc/$(pidof mosey_server)/fd/ | grep binder
```

### 8.4 术语表

| 术语 | 定义 |
|------|------|
| AWDL | Apple Wireless Direct Link — Apple 的专有 Wi-Fi 点对点协议 |
| mosey | Google 对 "Quick Share over AWDL" (AirDrop 互操作) 的代号 |
| MoseyApp | `com.google.android.mosey` — 桥接 Quick Share ↔ AWDL 的 Google 系统应用 |
| mosey_server | Rust 原生守护进程，实现 AWDL 射频控制 |
| Binder TR_CODE | 事务代码 — Binder RPC 方法的数字标识符 |
| VINTF | Vendor Interface — Android HAL 注册框架 |
| KSU | KernelSU — Android 内核级 root 方案 |
| NAN | Neighbor Awareness Networking — Wi-Fi Aware 标准 |
| NL80211 | Linux 无线协议栈配置的 Netlink 协议 |
| wonder.ko | Qualcomm WiFi 芯片驱动，支持 NAN/monitor 模式 |
| vndbinder | VINTF vendor 上下文 Binder 管理器 (`/dev/vndbinder`) |
