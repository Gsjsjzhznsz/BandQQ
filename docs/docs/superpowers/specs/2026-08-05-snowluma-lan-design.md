# SnowLuma 支持 + 局域网 + 状态同步修复 设计文档

日期：2026-08-05
状态：已获用户认可（方案 A）
适用对象：`band-qq` 仓库（Android 同步器 `android-sync` + 手环 Vela 应用 `band-qq`）

## 1. 背景与目标

当前架构：
- NapCat（QQ 机器人 OneBot v11 协议端）跑在**手机本机** Termux，同步器经 OkHttp 连 loopback `127.0.0.1` 的 WS(3001)/HTTP(3000)
- NapCatDetector 只扫描 `127.0.0.1` loopback 固定端口
- 手环端 settings/index 的「互联状态」用 `api.connectStatus()` 走蓝牙互联通道诊断，与手机端 `SyncState` 数据来源不同，导致**状态不同步**

用户需求：
1. 添加对 **SnowLuma**（另一 OneBot v11 协议端）的支持
2. 支持**局域网**连接（手机同步器经局域网连协议端，协议端可跑在 Windows PC 或本机）
3. 修复**状态不同步**（手环显示未连接但手机显示已连接）
4. 添加**手动测试 NapCat / SnowLuma 连接**的按钮

### SnowLuma 调研结论
- 同为 OneBot v11 协议端，事件模型与 QQ Bot API 完全复用 NapCat 组件（`NapCatWebSocket`/`OB11Protocol`/`NapCatEventParser`），**对现有 OneBotClient 在协议/事件层面完全兼容**
- 默认 WebSocket 端口 **3001（与 NapCat 一致）**，WebUI 端口 **5099**（NapCat 为 6099）
- 自带 Node.js 运行时，不依赖 QQNT；自动安装仅 Windows x64，Linux/macOS 需手动管理
- 未提及 Termux/Android 上运行 → 故 SnowLuma 大概率跑在 **Windows PC**，需局域网访问

### 已澄清的关键决策（用户选择）
1. **部署拓扑**：协议端可在任意设备，须支持任意 IP；手环始终只经蓝牙连手机
2. **状态含义**：手环仍只显示「蓝牙通道」，但数据须与手机端同源一致
3. **测试按钮**：两个独立按钮——「测试 NapCat」「测试 SnowLuma」
4. **配置模型**：分两套独立配置（NapCat 配置 + SnowLuma 配置）
5. **活动协议端**：侧切开关（NapCat / SnowLuma 二选一）
6. **自动探测范围**：扫局域网网段 + 默认端口，两种协议端都探测

## 2. 方案 A（已采纳）概述

静态双配置 + 全同源状态：
- 数据层：`AppConfig` 拆分为两套协议端配置 + 全局设置
- 连接层：SyncService 按活动开关只连当前协议端
- 探测层：`NapCatDetector` 重构为通用的 `GameProtocolDetector`，支持 loopback + 局域网子网扫描、区分两端
- UI 层：活动页加协议端侧切开关 + 两个测试按钮；改造自动探测按钮为局域网扫描
- 状态同步：手环端改用**同源状态帧**渲染（SyncService 把 `bandConnected` 通过既有 `connect_state` 帧推给手环），不再本地猜

## 3. 详细设计

### 3.1 数据层：ConfigManager / AppConfig

`android-sync/app/src/main/java/com/example/bandqq/config/ConfigManager.kt`

当前单份 `AppConfig(wsUrl, httpUrl, token)`。重构为：

```kotlin
enum class ProtocolType { NAPCAT, SNOWLUMA }

data class EndpointConfig(
    val wsUrl: String,
    val httpUrl: String,
    val token: String
)

data class AppConfig(
    val napcat: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "http://127.0.0.1:3000", ""),
    val snowluma: EndpointConfig = EndpointConfig("ws://127.0.0.1:3001", "http://127.0.0.1:3001", ""),
    val activeType: ProtocolType = ProtocolType.NAPCAT
)
```

DataStore keys（名称保持兼容，新增 `snowluma_*` 与 `active_type`）：
```
ws_url, http_url, token                 # NapCat（沿用旧 key，无缝迁移）
snowluma_ws_url, snowluma_http_url, snowluma_token
active_type
```

- `ConfigHolder.config: AppConfig` 保留，改为持有两套
- `getActiveEndpoint(): EndpointConfig` 便捷方法返回 `activeType` 对应端
- `save()` 保留旧 `wsUrl/httpUrl/token` 字段的 getter/setter 兼容读写（迁移期），最终统一走新结构

**注意**：`OneBotClient` 目前接收 `AppConfig` 并直接读 `config.wsUrl`/`config.httpUrl`。设计上让 OneBotClient 改收 `EndpointConfig`（抽象协议端，不再关心是 NapCat 还是 SnowLuma）；SyncService 传入 `getActiveEndpoint()`。

### 3.2 连接层：OneBotClient / SyncService

- `OneBotClient` 构造/`start()` 参数由 `AppConfig` 改为 `EndpointConfig`，内部读 `endpoint.wsUrl/httpUrl/token`
- `SyncService.onStartCommand`：`oneBot.start(configManager.load().getActiveEndpoint(), broker)`
- `MessageBroker` 不感知协议类型（协议层已兼容），无需改动其 OneBot 交互
- **状态同步修复**（关键）：手环端不再用 `api.connectStatus()` 本地诊断蓝牙，改为渲染手机推来的 `connect_state` 帧

当前状态帧链路：
- 手机 → 手环：`InterconnectBridge.onConnect()/onDisconnect()` 发 `connect_state {state}`；`MessageBroker.onState(oneBot)` 也发 `connect_state`
- 冲突问题：两种来源都发 `connect_state`，band 无法区分布鲁特还是协议端

**修复方案**：扩展 `connect_state` 帧携带两个布尔字段，明确语义：

```json
{ "type": "connect_state", "state": "connected", "band": true, "protocol": true }
```

- `band`：蓝牙互联通道是否建立（来自 `SyncState.bandConnected`）
- `protocol`：当前协议端（NapCat/SnowLuma）是否在线（来自 `SyncState.oneBotConnected`）

手机端：
- `InterconnectBridge.onConnect/onDisconnect` 与 `MessageBroker.onState` 统一走一个 `SyncStatePush.sendState()`，把当前 `SyncState.bandConnected` 与 `oneBotConnected` 打包成上述帧发送
- band `app.ux` 的 `connect_state` 分支把整帧 emit(`state`)，页面读取 `band`/`protocol` 字段

手环端：
- settings/index 的「互联状态」行改为显示 `state.band` 值（蓝牙通道），与手机端 `SyncState.bandConnected` 同源 → 一旦不一致即为真实 bug，消除「手环本地猜」的偏差
- （可选增强，非必需）可在 NapCat 行显示 `state.protocol`

### 3.3 探测层:重构为 GameProtocolDetector

`android-sync/app/src/main/java/com/example/bandqq/onebot/NapCatDetector.kt` → 新建通用 `GameProtocolDetector.kt`

- `detect(type: ProtocolType, preferred: String? = null): EndpointConfig?`
- 并发扫描目标列表（loopback + 局域网子网）：
  - `127.0.0.1` 固定
  - 局域网：通过 `NetworkInterface`/`WifiManager` 取当前本机 IPv4 与子网（如 `192.168.1.x/24`），枚举 2..254 的所有机
- 候选配置：
  - NapCat：HTTP 端口 `[3000,6099,3001,5700,8080,3002]`，探测路径 `GET /api/get_version`
  - SnowLuma：默认 WS 3001，WebUI 5099；因 SnowLuma 无独立 HTTP OneBot 端点（仅 WS），探测用 **WS 握手**或探针（`/api/get_version` 不保证存在）——需在实现期验证 SnowLuma 是否响应标准 OneBot 探测端点；若无，则探测 WS 3001 是否可握手
- 全网段并发需控制线程/超时：每个地址独立 `async(Dispatchers.IO)`，复用 800ms 超时连接，加整体并发限制（如信号量），局域网需转台多个 IP 并行
- 返回首个成功的端点

**实现风险**：SnowLuma 探测端点（HTTP get_version 或仅 WS）在文档中未确认。实现期需作兼容性兜底：尝试 HTTP `get_version`，失败则尝试 WS 握手（用 `Request` + `newWebSocket` 短暂连接探测）。

### 3.4 UI 层：activity_main.xml + MainActivity

`MainActivity.kt` / `res/layout/activity_main.xml`：

新增/改造：
1. **协议端侧切开关**：`RadioButton` 或 `Switch` 组「NapCat / SnowLuma」，切更新 `AppConfig.activeType` 并持久化；切换后 `refreshStatus()`
2. **NapCat 配置区**（沿用现有 `wsInput/httpInput/tokenInput`，仅当活动端为 NapCat 时显示）
3. **SnowLuma 配置区**：新增同构的 `snowWsInput/snowHttpInput/snowTokenInput`（仅当活动端为 SnowLuma 时显示，或始终显示分组）
4. **测试按钮**：`testNapBtn`「测试 NapCat」`testSnowBtn`「测试 SnowLuma」——各自用**对应端点**发 `GET /api/get_version`（或 WS 探测），成功 toast「NapCat/SnowLuma 在线」并回填，失败提示
5. **自动探测按钮**：文案改「自动探测(局域网)」，调用 `GameProtocolDetector`，扫当前活动协议端（或两端），回填对应配置

MainActivity 新增方法：
```kotlin
private fun testConnection(type: ProtocolType, inputWs: String, inputHttp: String)
private fun probeLan(type: ProtocolType)
```
- 复用现有 `scope.launch` 模式
- 状态文案 `refreshStatus()` 改为体现当前活动协议端类型

### 3.5 Android 权限 / 构建

局域网扫描需要：
- `ACCESS_WIFI_STATE`（读 WiFi 信息/子网）——新增
- `ACCESS_NETWORK_STATE`——新增（可选，获取外网接口）
- `INTERNET` 已有
- cleartext HTTP（`http://192.168.x.x`）：当前未禁用 cleartext（未设 `usesCleartextTraffic=false`），确认 `AndroidManifest` 无 networkSecurityConfig → 默认允许 http，但需在 manifest 显式声明 `android:usesCleartextTraffic="true"` 以覆盖 targetSdk 34（Android 9+ 默认禁止 cleartext）。**需在实现期加入**

`AndroidManifest.xml` 新增权限 + application 标签加 `android:usesCleartextTraffic="true"`。

### 3.6 手环端改动

`band-qq/src/app.ux`：
- `connect_state` 分支已 `emit('state', msg)`，无需改（已在 app.ux:43-45）

`band-qq/src/pages/settings/settings.ux` 与 `index/index.ux`：
- 移除 `refreshStatus()` 里的 `api.connectStatus()` 本地诊断逻辑
- 订阅 `$app.$def.on('state', ...)`，渲染帧内 `band` 字段到「互联状态」行，`protocol` 字段到（可选）NapCat 行
- 初始值仍为「未连接」，收到帧后更新

## 4. 文件改动清单

### 手机端 `android-sync/`
| 文件 | 动作 |
|------|------|
| `config/ConfigManager.kt` | 重构为双端点 + 活动类型 |
| `onebot/NapCatDetector.kt` | 重命名/重构为 `GameProtocolDetector.kt`（多协议 + 局域网） |
| `onebot/OneBotClient.kt` | 参数改 `EndpointConfig` |
| `sync/SyncService.kt` | 传活动端点；统一状态帧推送 |
| `sync/InterconnectBridge.kt` | 状态帧携带 band/protocol |
| `sync/MessageBroker.kt` | `onState` 统一发 state 帧 |
| `sync/（新增）SyncStatePush.kt` | 可选：状态帧组装工具 |
| `MainActivity.kt` | 侧切 + 测试按钮 + 局域网探测入口 |
| `res/layout/activity_main.xml` | 新增控件、分组 |
| `AndroidManifest.xml` | 权限 + cleartext |

### 手环端 `band-qq/`
| 文件 | 动作 |
|------|------|
| `src/pages/settings/settings.ux` | 状态行改读帧 |
| `src/pages/index/index.ux` | 状态行改读帧 |

### 测试
| 文件 | 动作 |
|------|------|
| `android-sync` 配置相关单测 | 更新为双端点结构 |
| 手环 store/app 测试 | 适应性调整（若有引用 statusText） |

## 5. 测试计划
- Android 单测：ConfigManager 双端点持久化/迁移；GameProtocolDetector 对 mock 探测端点的识别（loopback 命中、局域网候选生成、SnowLuma WS 探测兜底）
- Android 编译：`powershell scripts\build-android.ps1 -Task assembleRelease`
- 手环：`cd band-qq; npm test`
- 交付：重新打包 rpk + APK，更新交付包

## 6. 不做的事（范围外）
- 不做同时双协议端（一次只连活动端）
- 不做手环直连协议端
- 不做 SnowLuma 安装/引导（那是部署端，由用户自装）

## 7. 风险与依赖
- SnowLuma 探测端点未确认（HTTP get_version 是否存在）→ 实现期加 WS 握手兜底，保持兼容
- 局域网全段扫描开销 → 并发限制 + 超时控制
- cleartext HTTP over LAN 在 Android 9+ 默认受限 → 必须在 manifest 显式开启