# Miuix 手机端 UI 重构设计文档

日期：2026-08-10
状态：待审阅

## 背景与目标

手机端（`android-sync`）当前为传统 Android View + ViewBinding 界面，功能分散在
`MainActivity`（XML 布局）、`ChatHistoryActivity`、`ContactManagerActivity` 三个界面。
本次将手机端 UI 全面重构为 **Compose + Miuix**（小米 HyperOS 风格的 Compose Multiplatform
组件库），保留全部业务逻辑不变，只重写 UI 层为底部导航单 Activity 结构。

### 非目标

- 不迁移为 KMP 多平台工程（仅纯 Android Compose）
- 不改动业务层：`SyncService`、`OneBotClient`、`MessageStore`、`InterconnectBridge`、
  `ConfigManager`、`GameProtocolDetector` 原样保留
- 不更换存储方案（继续使用 SharedPreferences-based `MessageStore`）

## 技术选型

| 项 | 选择 | 说明 |
|----|------|------|
| UI 组件库 | Miuix `0.9.3`（`top.yukonga.miuix.kmp:miuix-ui-android`） | 最新版，Maven Central 已确认可达 |
| Compose | 最新前沿组合（Compose Multiplatform BOM / androidx.compose BOM 按需） | 与 Gradle 8.7 + JDK17 匹配 |
| 导航 | Miuix 底部导航栏 | 四个 Tab 常驻底部切换 |
| Activity | 单 `MainActivity`，Compose 全屏 | 删除其余两个 Activity |

## 模块结构

```
app/src/main/java/com/example/bandqq/
├── MainActivity.kt              # 重建：Compose 入口 + 权限 + 状态刷新
├── ui/
│   ├── BandQQApp.kt             # MiuixTheme + Scaffold + 底部导航 + 路由
│   ├── HomeScreen.kt            # 主页（手环/SnowLuma 状态）
│   ├── ContactScreen.kt         # 联系人管理
│   ├── HistoryScreen.kt         # 聊天记录管理
│   └── SettingsScreen.kt        # 设置（SnowLuma 配置）
├── config/                      # 保留
├── onebot/                       # 保留
└── sync/                         # 保留
```

## 四个界面设计

### 1. 主页 HomeScreen

- **手环连接状态卡**：读 `SyncState.bandConnected`、监听 `BandStateBus`；
  显示"已连接/未连接"，按钮「检查手环连接」调用 `InterconnectBridge.connect()`
- **SnowLuma 状态卡**：读 `SyncState.oneBotConnected`；显示"在线/离线"
- **同步服务开关**：启动/停止 `SyncService`，反映 `SyncState` 当前状态
- 清空聊天记录入口移到聊天记录页，主页不再放置

### 2. 联系人管理 ContactScreen

- 数据源：`MessageStore.getCachedContacts()`（分批读取 private/group）
- 列表项：头像圆标（首字）、名称、类型标签（私聊/群聊）
- 可见联系人选择：勾选后写回本地可见集合（沿用 `MessageStore` 可见性语义，
  避免在未连接时被空列表清空——保留现有防护逻辑）
- 提供「刷新联系人」动作（触发 `autoFetch`）

### 3. 聊天记录管理 HistoryScreen

- 数据源：`MessageStore.getConversations()` 返回会话列表（按时间倒序）
- 列表项：会话名、最后一条消息摘要、时间
- 点击会话：展开该会话完整消息列表（可用二级 Compose 页面或弹窗）
- 顶部「清空全部」按钮：`clearAllHistory()` + 向手环下发 `clear_all_history` 帧

### 4. 设置 SettingsScreen

- 表单：WS 地址、WS Token、HTTP 地址、HTTP Token（复用现有 `EditText` 语义）
- 读取/保存：`ConfigManager.load()` / `ConfigManager.save()`，保存后 Toast 提示
- 「测试连接」按钮：`GameProtocolDetector.testConnection()` 显示 WS/HTTP 可达性
- 应用即生效（`ConfigHolder` 已在全局承载）

## 数据流

- UI → `SyncService`（启动/停止）、`InterconnectBridge`（检查连接）
- UI → `ConfigManager`（读写 SnowLuma 配置）
- UI → `MessageStore`（联系人/会话历史读取、可见性写回、清空）
- 状态回传：`SyncState` 读写 + `BandStateBus`/`MessageBus` 观察者回调刷新 Compose 状态

## 错误处理

- 连接测试失败：Toast 显示 WS/HTTP 各自可达性
- 空数据：联系人为空显示"暂无联系人"，会话为空显示"暂无聊天记录"
- 手环未连接时可见性写回：沿用 store 现有守卫，避免清空本地联系人

## 测试

- 编译：`assembleRelease` 作为门禁（脚本已在本机验证可用）
- 单元测试保留：`MessageStoreTest` 等现有 JVM 测试不动
- 手工验证路径：启动 → 配置 SnowLuma → 测试连接 → 开启服务 → 查联系人/记录 → 清空

## 风险点与缓解

1. **Miuix 0.9.3 与 Compose 版本配对**：最新版可能要求特定 Kotlin/Compose BOM。
   先做最小依赖探测（空 Compose Activity 构建通过）再铺开。
2. **删除旧 View 层**：需同步清理 `ChatHistoryActivity`/`ContactManagerActivity` 及
   其 XML、Manifest 声明，确保无残留引用。
3. **viewBinding 移除**：`buildFeatures { viewBinding = true }` 改为 Compose 配置
   （`compose = true` + Kotlin compose 插件）。
4. **AAR 兼容**：`xms-wearable-lib`、okhttp、datastore 等现有依赖全部保留，仅叠加 Compose。