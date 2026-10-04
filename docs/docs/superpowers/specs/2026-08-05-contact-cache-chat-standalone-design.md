# 连接后自动缓存联系人 + 手环未连接也可查看聊天记录

日期：2026-08-05

## 背景与目标

SnowLuma 已可正常连接（HTTP/WS 双通道鉴权通过）。当前存在两个痛点：

1. **联系人没有后台缓存**：仅在进入「管理手环联系人」页面时才调用
   `get_friend_list` / `get_group_list` 拉取，进入页面需要等待网络返回，且
   SnowLuma 连接成功后不会主动拉取一次。
2. **手环未连接时无法在 App 查看聊天记录**：`StoreHolder.store` 只在
   `SyncService` 运行时（onCreate）才被赋值（SyncService.kt:88）。若用户只测试
   连接、未启动同步服务（或同步服务未运行），store 为 null，
   `ChatHistoryActivity` 读到 `null ?: emptyList()` 即为空白页，无法回看已保存的
   历史记录。

目标：
- SnowLuma 连接成功后，后台自动拉取一次好友/群列表并缓存到本地持久化存储。
- 聊天记录查看不依赖手环连接与否，也不依赖同步服务是否正在运行。

## 架构与设计

### 1. 全量联系人缓存（MessageStore）

在 `MessageStore`（`sync/MessageStore.kt`）中新增一份「全量联系人缓存」，与现有
「可见联系人」（`visibleContacts`，手环勾选展示）区分开：

- 新增 KV key：`contact_cache`（JSON 数组），字段沿用 `VisibleContact`
  （id / type / name），type 为 `private` / `group`。
- 新增方法：
  - `setCachedContacts(list: List<VisibleContact>)`：写入缓存并持久化。
  - `getCachedContacts(): List<VisibleContact>`：读取缓存。
- 缓存不参与手环可见性判断（`isVisibleContact` 逻辑不变）。

### 2. 连接成功后自动拉取（SyncService）

在 `SyncService` 中监听 OneBot 连接状态：

- `MessageBroker.onState(connected)` 已回调 `SyncState.oneBotConnected` 并推帧。
  在其基础上，当 `connected == true` 时，首次触发一次联系人拉取：
  - 调用 `oneBot.requestApi("get_friend_list")` 与 `oneBot.requestApi("get_group_list")`
    （HTTP，token 已内置在 OneBotClient）。
  - 解析结果为 `List<VisibleContact>`，调用 `store.setCachedContacts(...)` 持久化。
  - 拉取失败静默（下次连接成功或手动刷新重试），不影响主流程。
- 为避免每次重连都重复拉取，只在 `connected` 从 false→true 的边沿触发，
  并加一个「已自动拉取」标记（本次服务生命周期内只拉一次）。

### 3. 联系人管理页改造（ContactManagerActivity）

- 顶部新增「刷新联系人」按钮（`refreshContactsBtn`），点击时重新调用
  `loadContacts()`。
- 页面打开流程：
  1. 先立即显示 `store.getCachedContacts()`（本地缓存，无需等待网络）。
  2. 再异步调用 `loadContacts()` 拉取最新列表刷新（与现有行为一致，
     覆盖/合并缓存）。
- `loadContacts()` 成功后同步写回缓存（`setCachedContacts`），使缓存常新。
- 若进入页面时 `StoreHolder.store == null`（同步服务未运行），按第 4 节方式兜底
  创建持久化 store，保证缓存读写与列表展示可独立工作。

### 4. 聊天记录兜底初始化（ChatHistoryActivity）

- 在 `ChatHistoryActivity.setupList()` 前，若 `StoreHolder.store == null`，
  用 `MessageStore(SyncPreferencesKv(this))` 创建一份持久化 store 并回填
  `StoreHolder.setStore(...)`。
- 这样即使同步服务未运行，也能读取 SharedPreferences 中已保存的历史聊天记录。
- 若同步服务已运行（store 非 null），保持现有路径不变（同一份持久化数据，
  无冲突）。

## 数据流

```
SnowLuma 连接成功 (WS onOpen / onState true)
  └─ SyncService 自动拉取 get_friend_list + get_group_list (HTTP)
       └─ 解析 → store.setCachedContacts() → SharedPreferences 持久化
            └─ ContactManagerActivity 打开时先显示缓存，再异步刷新

ChatHistoryActivity 打开
  └─ StoreHolder.store 为空？→ 用 SyncPreferencesKv 兜底创建 MessageStore
       └─ 读取 SharedPreferences 中的会话/消息 → 正常展示历史记录
```

## 错误处理

- 自动拉取联系人失败（HTTP 超时、401 等）：静默，保留旧缓存；不打断主流程。
- 缓存解析失败/损坏：回退为空列表，下次拉取成功后覆盖。
- 兜底创建的 store 与同步服务实例共享同一 SharedPreferences 数据源，
  读取语义一致；不做跨进程并发写入（同步服务与 Activity 同进程）。

## 测试

- `MessageStoreTest`：新增缓存读写/持久化用例（set/get、覆盖、损坏回退）。
- 拉取逻辑（解析 friend/group 响应）可复用 `ContactManagerActivity` 现有解析思路，
  抽成纯函数以便单测。
- 手动验证：
  - 启动同步并连接 SnowLuma → 打开联系人页立即见缓存，随后列表刷新；
  - 不启动同步服务 → 打开「查看聊天记录」能看到历史记录。
