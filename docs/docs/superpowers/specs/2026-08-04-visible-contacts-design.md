# 手机端添加好友/群聊并同步到手环 — 设计文档

- 日期：2026-08-04
- 状态：待用户审阅

## 目标

手机端从 NapCat 拉取**已有**的好友列表和群列表，用户勾选"要显示到手环"的联系人（好友和群聊）。手环端会话列表**只常驻显示**被勾选的已添加联系人。未添加的联系人发来新消息时，消息仍推送并**临时显示**（保留在手环本地但标记"临时"），直到重新同步手环列表时才被清除。

重点：**不是添加新好友**，而是把 NapCat 已有联系人中挑选出来的部分同步到手环端显示。

## 背景与现状

- QQ 侧通过 OneBot v11 协议接入，WebSocket 收消息 + HTTP 发消息。
- 帧协议为 JSON 文本帧，经小米 `xms-wearable-lib` 互联通道传递。
- 手机端为会话主存储（`MessageStore`，SharedPreferences 持久化）；手环端本地缓存（`store.js`）。
- 手环会话列表（`index.ux`）当前完全由消息推导聚合（`getConversations()`），无"订阅/勾选"概念。

### 已发现的现存问题（随本功能一并修复）
- `MessageStore.buildConversationFrame()`（L227）序列化会话**缺 `type` 字段**，手环端无法区分私聊/群聊 → 需补上。
- `index.ux` 无按类型渲染不同图标的逻辑。

## 架构与数据流

### 1. 手机端（Android）

**A. NapCat 通用 API 调用**
- 扩展 `OneBotClient`：新增通用请求方法，走既有 HTTP 通道请求 OneBot 标准接口：
  - `get_friend_list` → 好友列表（`user_id`, `nickname`）
  - `get_group_list` → 群列表（`group_id`, `group_name`）
  - 实现：POST 到 `{httpUrl}/api/{action}`，若返回异常则 fallback `{httpUrl}/{action}`。
- 新增 `OneBotParser` 或独立解析函数把响应 JSON 归一化为 `ContactInfo(id, type, name)`。

**B. 可见联系人（visibleContacts）存储**
- 新增数据状态，并入 `MessageStore`：
  - `data class VisibleContact(val id: String, val type: String, val name: String)`
  - 持久化 key：`visible_contacts`，SharedPreferences 存 JSON 数组。
  - 方法：`getVisibleContacts()`、`setVisibleContacts(list)`、`addVisibleContact()`、`removeVisibleContact()`。
  - 与 `getConversations()` 的关系：手环列表以 **visibleContacts 为主**；普通会话聚合保留（供手机端聊天记录查看等场景）。

**C. 手机端 UI**
- `MainActivity` 新增按钮"管理手环联系人" → 打开新 Activity `ContactManagerActivity`。
- `ContactManagerActivity`：顶部 tab 切换"好友 / 群聊"；列表加载自 NapCat（`get_friend_list` / `get_group_list`），每项带复选框；勾选状态回填当前 `visibleContacts`；点"保存"后：
  1. `store.setVisibleContacts(checked)`
  2. 通过 `MessageBroker` 构建并推送 `visible_contacts` 帧到手环。
- 布局参考 `activity_chat_history.xml`（ListView）与 `activity_main.xml`。

**D. MessageBroker 改动**
- 新增帧处理：手环请求 `get_visible_contacts` → 构建 `visible_contacts` 帧回推。
- 收到 NapCat 新消息（`handleOneBotEvent`）时，判断 targetId 是否在 `visibleContacts`：
  - 在 → 正常 `push_message`（`visible: true`）。
  - 不在 → 仍 `push_message`，但 `visible: false`。
- 新增方法构建 `visible_contacts` 帧（含 `seq`）。

### 2. 帧协议

新增 / 修改帧：

| 帧类型 | 方向 | 载荷 | 说明 |
|---|---|---|---|
| `visible_contacts` | 手机→手环 | `{contacts:[{id,type,name}], seq}` | 常驻联系人全量列表 |
| `get_visible_contacts` | 手环→手机 | `{seq}` | 手环请求拉取常驻列表 |
| `push_message` | 手机→手环 | 原有字段 + `visible: bool`（默认 true） | 标记该消息所属联系人是否已添加 |

修复：`conversation_list` 每项补 `type` 字段。

### 3. 手环端（Vela）

**A. 常驻列表（visibleContacts）+ 临时标记**
- `store.js`：
  - 新增 `visibleContacts` 状态 + 持久化 key `visible_contacts`（复用现有 storage 适配器）。
  - `setVisibleContacts(list)`：保存，并**清除**所有"临时"标记会话（临时联系人的消息缓存一并清理）。
  - `conversations` 继续作为列表数据源，但在 `upsertMessage` 中：
    - 增加 `is_temporary: !(msg.visible !== false && 联系人属于 visibleContacts)` 标记。
    - 更清晰的规则：电话号码/目标 id 是否在 `visibleContacts` 中。
- 定义轻量判定函数 `isVisible(id)`：id ∈ visibleContacts。

**B. index.ux 渲染**
- 列表项增加：常驻（已添加）正常显示；临时项显示"临时"角标或不同底色。
- 私聊/群聊图标区分：利用 `type` 字段渲染不同首字符/图标（可沿用 `name.charAt(0)`，但样式区分群聊矩形、私聊圆形）。
- 空态文案区分：无常驻联系人时提示"请在手机端添加联系人"。

**C. app.ux 帧分发**
- 新增分支处理 `visible_contacts`：`store.setVisibleContacts(msg.contacts)` → `emit('visible_contacts')`。
- `push_message` 分支：把 `msg.visible` 传入 `upsertMessage`，由 store 决定是否标记临时。

## 错误处理

- NapCat 拉取好友/群失败（未连接/接口不支持）→ `ContactManagerActivity` 显示错误提示，不崩溃；列表置空。
- 手环端未拿到 `visible_contacts`（手机未推送）→ 回退到现有基于消息的会话展示，保证可用性。
- `get_visible_contacts` 请求超时/无响应 → 手环保留本地已有 visibleContacts 与临时会话。

## 测试策略

- **Android（JVM 单测）**：
  - `MessageStore`：`setVisibleContacts/getVisibleContacts` 持久化往返；`isVisible` 判定正确。
  - `buildConversationFrame` 包含 `type` 字段。
  - `ContactManagerActivity` 的列表归一化逻辑（new/pure 函数）。
- **手环（node --test）**：
  - `store.js`：`setVisibleContacts` 清除临时会话；`upsertMessage` 对未添加联系人生成 `is_temporary` 标记、对已添加联系人正常标记。
- **构建验证**：`npm run build`（手环 rpk）+ `./gradlew assembleDebug`（Android）。

## 范围控制（不做）

- 不实现 NapCat 搜索/加新好友、加群、好友申请审批。
- 不实现手环端发起"添加联系人"请求（仅手机端管理，同步到手环）。
- 不实现个性化头像下载（沿用首字符头像）。
- 不在手环端做联系人移除操作（移除在手机端完成）。