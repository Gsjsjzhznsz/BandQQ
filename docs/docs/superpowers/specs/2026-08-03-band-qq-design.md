# 小米手环9 QQ 消息收发小程序 — 设计文档

日期：2026-08-03
状态：已批准（brainstorming 流程）

## 1. 目标与范围

在小米手环 9 上实现 QQ 消息收发。手环通过蓝牙互联通道与手机端同步器 App 通信；同步器 App 通过 OneBot 协议连接 NapCatQQ，实现与 QQ 服务器双向收发。

### 范围

- 手环端：会话列表、消息查看、发送文本消息（含快捷回复）。
- 手机端：作为手环与 NapCatQQ 之间的桥接器，双向转发消息。
- QQ 接入：NapCatQQ + OneBot v11，跑在手机端 Termux + proot 容器内，无需 PC。
- 非文本消息（图片/语音/视频/文件）不传输，统一降级为文字标签（`[图片]` 等）。

### 非目标

- 不传输/显示图片、语音、视频、文件。
- 手环端不做自由文本输入，仅支持快捷回复词条（后续可扩展输入）。
- 不实现后台消息防杀机外的复杂离线逻辑。

## 2. 系统架构

```
小米手环9 (Vela 快应用) ⇄ Bluetooth interconnect ⇄ 手机
                                               │
                                    同步器 App（自研，互联+OneBot 客户端）
                                      │  OneBot WS (127.0.0.1:3001)
                                NapCat APK（社区方案封装 Termux+proot+NapCat，扫码登录）
                                      │
                                     QQ 服务器
```

### 数据流

- QQ → 手环：QQ服务器 → NapCatQQ → (OneBot 事件) → 同步器 App → (interconnect.send) → 手环快应用 → UI
- 手环 → QQ：手环输入 → (interconnect.send) → 同步器 App → (send_private_msg/send_group_msg) → NapCatQQ → QQ服务器

### 关键约束

- interconnect 是手环快应用与手机 App 的直连蓝牙通道，连接由系统自动建立。
- 手环快应用 `manifest.json` 的 `package` 必须与同步器 App `applicationId` 一致（`com.example.bandqq`），且 rpk 需用与手机 App 相同的证书签名。
- 小米运动健康 App 仅负责蓝牙配对与连接维护，不参与数据转发。
- NapCat 无法内嵌进同步器 App 进程（Node + NTQQ 需要 Linux 用户态，Android 不允许），故采用"一体化 APK 组合"：同步器 App 与 NapCat APK（基于社区 AstrBot APP 方案）两个 APK 配合，同步器自动探测 `localhost:3001` 并引导安装。

## 3. 工程布局

```
band-qq/
├── band-qq/                 # 手环端 Vela 快应用
│   ├── manifest.json        # 包名 com.example.bandqq
│   ├── app.ux
│   ├── common/
│   │   ├── api.js           # interconnect 封装
│   │   ├── protocol.js      # 协议编解码 + seq 管理
│   │   ├── store.js         # 会话/消息内存态缓存（不持久化）
│   │   └── style.css
│   ├── pages/
│   │   ├── index/index.ux   # 会话列表
│   │   ├── chat/chat.ux     # 聊天 + 快捷回复
│   │   └── settings/settings.ux
│   └── i18n/
├── android-sync/            # 同步器 App (Kotlin, minSdk 26)
│   ├── app/src/main/java/com/example/bandqq/
│   │   ├── MainActivity.kt        # 配置界面 + 日志 + NapCat 探测
│   │   ├── sync/
│   │   │   ├── SyncService.kt     # 前台服务（互联 + 保活）
│   │   │   ├── InterconnectBridge.kt
│   │   │   ├── MessageBroker.kt   # 双向转发/协议映射
│   │   │   └── MessageStore.kt    # 消息/会话本地存储（手机端数据中枢）
│   │   ├── onebot/
│   │   │   ├── OneBotClient.kt    # WS + HTTP API
│   │   │   └── OneBotParser.kt    # 事件→手环协议
│   │   └── config/ConfigManager.kt
│   └── AndroidManifest.xml
├── napcat-apk/              # 基于社区 AstrBot APP 方案的构建说明 + 文档
├── docs/
│   ├── napcat-apk.md        # NapCat APK 构建/获取与集成说明
│   └── signing.md           # 签名提取与打包步骤
└── README.md
```

## 4. 通信协议（手环 ⇄ 同步器）

统一 JSON 字符串，通过 `interconnect.send(JSON.stringify(payload))` 传输。所有消息含 `type` 与 `seq`。

### 消息类型

| 方向 | type | 说明 |
|---|---|---|
| 同步器→手环 | `push_message` | 新消息推送（私聊/群聊） |
| 手环→同步器 | `send_message` | 发送消息请求 |
| 手环→同步器 | `get_conversations` | 请求会话列表 |
| 同步器→手环 | `conversation_list` | 会话列表响应 |
| 手环→同步器 | `get_history` | 请求某会话最近历史消息 |
| 同步器→手环 | `history_list` | 历史消息列表响应 |
| 同步器→手环 | `connect_state` | 连接状态（connected/disconnected） |

> **数据归属**：手机同步器是**主数据源**，负责完整消息存储（本地持久化）并向手环推送最近记录、响应历史拉取。手环端为**瘦客户端**：以内存态缓存为主，可本地缓存**少量**最近数据（会话 ≤10、每会话消息 ≤30）作为断连兜底，不承担完整存储。

### push_message（同步器 → 手环）

```json
{
  "type": "push_message",
  "seq": 1,
  "message_type": "group",
  "target_id": "123456789",
  "sender_id": "10001",
  "sender_name": "张三",
  "content": "你好",
  "time": 1700000000
}
```

### send_message（手环 → 同步器）

```json
{
  "type": "send_message",
  "seq": 2,
  "message_type": "group",
  "target_id": "123456789",
  "content": "收到"
}
```

### conversation_list（同步器 → 手环，响应 get_conversations）

```json
{
  "type": "conversation_list",
  "seq": 3,
  "list": [
    { "id": "123456789", "type": "group", "name": "群名", "last_msg": "最后一条", "time": 1700000000 }
  ]
}
```

### connect_state（同步器 → 手环）

```json
{ "type": "connect_state", "seq": 0, "state": "connected" }
```

### get_history（手环 → 同步器，请求某会话历史）

```json
{ "type": "get_history", "seq": 4, "target_id": "123456789", "limit": 50 }
```

### history_list（同步器 → 手环，响应 get_history）

```json
{
  "type": "history_list",
  "seq": 4,
  "target_id": "123456789",
  "list": [
    { "message_type": "group", "sender_id": "10001", "sender_name": "张三", "content": "你好", "time": 1700000000 }
  ]
}
```

### 字段约定

- `message_type`: `"private"` | `"group"`；`target_id`/`sender_id` 一律字符串（避免 JS 大整数精度问题）。
- 非文本消息由同步器解析后降级为 `[图片]`/`[语音]`/`[视频]`/`[文件]` 等文字标签。
- 手环侧 `protocol.js` 维护自增 seq；原型阶段 seq 用于日志与去重。

## 5. 手环端设计（Vela 快应用）

### 页面

| 页面 | 功能 | 关键组件 |
|---|---|---|
| `index/index.ux` 会话列表 | 展示会话（头像占位 + 名称 + 最后一条 + 时间）；点击进聊天；顶部连接状态指示 | `list`/`list-item`、`text` |
| `chat/chat.ux` 聊天详情 | 消息气泡（自己右/对方左）；底部快捷回复词条（`swiper` 横滑）+ 可选文本输入；返回自动刷新 | `scroll`、`swiper`、`input`、`text` |
| `settings/settings.ux` 设置 | 互联连接状态（`connectStatus`）；快捷回复词管理（增删）；NapCat 在线提示 | `list`、`input`、`button` |

### 核心逻辑（common/）

- `api.js` — `interconnect.instance()` 封装：`send()`、`connectStatus()`、`onmessage` 分发；连接事件更新全局状态。
- `protocol.js` — 消息构造器 + seq 自增；`pushMessage`/`sendMessage`/`getConversations` 等；非文本降级。
- `store.js` — **内存态缓存 + 少量本地兜底**：内存保存会话列表与当前会话消息；用 `system.storage` 缓存**最近少量数据**（会话 ≤10、每会话 ≤30 条），用于断连后展示最近记录。数据主体来自同步器推送/拉取，本地仅作小量缓存。

### 数据流

1. 收到 `push_message` → `store.js` 更新内存态会话与消息 → 若在聊天页且为同一会话则刷新气泡。
2. 点击快捷回复 → `protocol.sendMessage()` → `api.send()` → 同步器 → NapCat → QQ。
3. 首页 `onShow` → 请求 `get_conversations` 拉取列表。
4. 进入聊天页 `onShow` → 请求 `get_history` 拉取该会话最近记录（手机端存储）。

### manifest 关键配置

```json
{
  "package": "com.example.bandqq",
  "name": "QQ助手",
  "versionName": "1.0.0",
  "versionCode": 1,
  "deviceTypeList": ["watch"],
  "minAPILevel": 1,
  "features": [
    { "name": "system.interconnect" },
    { "name": "system.storage" },
    { "name": "system.router" }
  ],
  "config": { "designWidth": 192 },
  "router": {
    "entry": "pages/index/index",
    "pages": {
      "pages/index/index": {},
      "pages/chat/chat": {},
      "pages/settings/settings": {}
    }
  }
}
```

## 6. Android 同步器设计

### 模块

1. **前台服务 + 互联桥（`SyncService` + `InterconnectBridge`）**
   - `SyncService` 常驻前台服务（通知栏"同步器运行中"），保证进程存活。
   - `InterconnectBridge` 初始化互联、注册消息接收，将收到的 JSON 转给 `MessageBroker`；蓝牙断开时重连与状态通知。

2. **消息中枢（`MessageBroker`）**
   - 手环 → QQ：收到 `send_message` → `OneBotClient.sendMessage()` → NapCat。
   - QQ → 手环：收到 OneBot 事件 → 转手环协议 → `InterconnectBridge` 发回。
   - 非文本消息段降级为文字标签。
   - **存储职责**：收到 OneBot 事件时，将消息写入本地存储（`MessageStore`）。
   - **历史推送**：收到手环 `get_history` → 从 `MessageStore` 读取最近 N 条 → 回 `history_list`；收到 `get_conversations` → 从 `MessageStore` 聚合会话 → 回 `conversation_list`。

2.5 **消息存储（`MessageStore`）**
   - 本地持久化会话与消息（Room/SQLite 或简单 JSON 文件）。
   - 写入：`onMessage` 事件落库；发送成功回执落库。
   - 查询：`getHistory(targetId, limit)`、`getConversations()`。
   - 限制：每会话保留最近 200 条、会话 100 个，防存储膨胀。

3. **OneBot 客户端（`OneBotClient` + `OneBotParser`）**
   - OkHttp + WebSocket 连接正向 WS（`ws://127.0.0.1:3001`），指数退避自动重连。
   - `OneBotParser` 解析 `message` 事件 → `message_type`/`group_id`/`user_id`/`sender`/`content`。
   - 发消息调 HTTP API：`send_private_msg` / `send_group_msg`。
   - 处理 `get_conversations` 请求，维护会话缓存并返回列表。

4. **配置界面（`MainActivity`）**
   - 设置项：NapCat WS 地址、Access Token、HTTP 地址（默认 `http://127.0.0.1:3000`）。
   - NapCat 探测按钮：检测 `localhost:3001`；不在线且未装 NapCat APK → 引导下载安装。
   - 日志显示区；DataStore 持久化配置。

### 字段映射

手环协议与 OneBot 字段映射在 `OneBotParser`/`MessageBroker` 完成：

- `message_type` private ↔ `user_id`；group ↔ `group_id`
- OneBot `message` 数组（CQ 码）→ 文本，图片段 → `[图片]`

### 签名/包名

- `applicationId = com.example.bandqq`，与手环 manifest 一致。
- 调试包用 `.jks` 提取证书签名 rpk（见 `docs/signing.md`）。

## 7. NapCat APK 集成

- 采用社区 AstrBot APP（基于 Termux，proot 跑 NapCat）方案，封装为独立 APK，扫码登录 QQ，WebUI 管理。
- 同步器 App 启动探测 `127.0.0.1:3001`；离线时检查 NapCat APK 是否安装并引导。
- 详细构建/获取步骤见 `docs/napcat-apk.md`。

## 8. 风险与对策（沿用文档 §3）

- 封号风险：NapCat 逆向协议，用 QQ 小号，避免高频操作。
- 手环断连：App 内重连 + 状态提示。
- 进程被杀：前台服务 + 引导关闭电池优化。
- 带宽限制：非文本降级为文字标签。

## 9. 端到端验证流程

1. 手环与手机蓝牙配对，小米运动健康保持连接。
2. 手机安装并扫码登录 NapCat APK，确认 OneBot WS 在线。
3. 安装同步器 App，确认互联连接成功、探测到 NapCat。
4. 用另一 QQ 号发消息，验证手环收到并展示。
5. 从手环发送快捷回复，验证对方收到。
