# BandQQ — 小米手环 9 QQ 消息助手

[![Version](https://img.shields.io/badge/version-1.1.1-blue)]() [![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Vela-green)]() [![License](https://img.shields.io/badge/license-MIT-brightgreen)]()

**BandQQ** 是一套开源的「小米手环 QQ 消息助手」双端方案：手环端运行 Vela 快应用（rpk），手机端运行安卓同步器（APK），通过蓝牙隧道把 QQ 消息实时同步到手环，支持直接在手环上**查看 / 回复 / 翻历史消息**。

> 关键词：小米手环9 / Mi Band 9 / 小米手环QQ / Vela 快应用 / rpk / OneBot v11 / NapCat / Lagrange / go-cqhttp / QQ 消息同步 / 手环回复QQ / 蓝牙消息助手 / BandQQ / wearable QQ / smartband chat

## 功能特性

### 消息同步
- 实时接收 QQ 群聊 / 私聊消息（OneBot v11 协议，兼容 NapCat、Lagrange、go-cqhttp、LLOneBot 等实现）
- 会话列表：彩色首字头像（Stapxs 风格）、最新消息预览、时间显示、按最新消息排序
- 未读角标（99+ 封顶）、`[@我]` 红色标签
- **@我提醒**：消息橙色描边 + 手环长震动
- **冷启动回放**：手环重启/被杀后，自动补上离线期间的缓存消息（按 message_id 幂等去重，不重复计未读）

### 聊天与输入
- 气泡布局：自己绿色右侧 / 他人深灰左侧 / @我 橙描边（Stapxs-QQ-Lite-X 风格）
- 时间分隔条（≥5 分钟自动插入）
- 群昵称按 ID 稳定取色
- **快捷回复**：9 条常用语一键发送
- **拼音输入法**：全键盘拼音输入，字库覆盖 **CJK 基本区 14718 字**（GB2312 全部 + 生僻字，候选按常用度排序）
- **历史消息翻页**：点击顶部「加载更早」按锚点向前翻页（远端 message_seq 优先 / 本地缓存双通道）

### 图片处理
- 图片消息自动抓取并压缩为 96px 缩略图下发手环（JPEG 55，<12KB，并发 2 + 超时兜底）

### 可靠性
- 手机端前台服务 + **无障碍保活**（不读取屏幕内容）
- OneBot WebSocket(:3001) 优先、HTTP(:3000) 自动回退
- 手环端冷启动自愈（ensureAlive：通道重建 + 重拉全量状态）
- 消息持久化串行写队列（防竞态）

## 系统架构

```
┌──────────┐  蓝牙隧道/WebSocket   ┌─────────────────┐   WS :3001 / HTTP :3000   ┌───────────┐
│ 小米手环9 │ ◄──────────────────► │  BandQQ 同步器   │ ◄────────────────────────► │  OneBot    │
│ Vela快应用│   ws://phone:9810    │  (Android APK)  │        OneBot v11          │ NapCat 等  │
└──────────┘                      └─────────────────┘                            └───────────┘
```

## 安装使用

### 1. 准备 OneBot 服务端
在任意可联网机器上部署 NapCat / Lagrange / go-cqhttp 等 OneBot v11 实现，开启：
- **正向 WebSocket**（默认 ws://IP:3001）
- **HTTP**（默认 http://IP:3000，作为回退通道）

### 2. 安装手机同步器（APK）
- 下载 `bandqq-sync-release-1.1.1.apk` 安装
- 打开 App 填写 OneBot 的 WS/HTTP 地址（有 token 就填 token），保存并重连
- 建议：设置 → 无障碍 → 开启「BandQQ 保活」

### 3. 安装手环应用（rpk）
- 用小米运动健康 / Vela 开发者调试器安装 `bandqq-watch-1.1.1.rpk`
- 手环端默认连接 `ws://127.0.0.1:9810`（蓝牙隧道转发），若连接失败可在手环「设置 → 连接」修改为手机地址

### 4. 开始使用
保持手环与手机蓝牙连接，App 显示「已连接」后即可在手环收发消息。

## 从源码构建

### 手环端（band-qq/）
```bash
cd band-qq
npm i aiot-toolkit@2.0.5 -g   # 或使用 npx
aiot release                  # 产物 dist/*.rpk（release 签名）
```
签名文件在 `band-qq/sign/release/`（private.pem + certificate.pem），可替换为自己的。

### 手机端（android-sync/）
```bash
cd android-sync
./gradlew assembleRelease     # 产物 app/build/outputs/apk/release/*.apk
./gradlew testDebugUnitTest   # 运行单元测试
```
要求：JDK 17+、Android SDK（compileSdk 37）、AGP 8.13.2。

## 目录结构

```
├── band-qq/              # 手环端 Vela 快应用源码
│   ├── src/manifest.json #   应用配置（图标 / 路由 / 特性）
│   ├── src/pages/        #   会话列表 / 聊天 / 设置
│   ├── src/components/   #   拼音输入法（含全量字库 dic.js）
│   ├── src/common/       #   store / protocol / api（连接适配）
│   ├── sign/release/     #   rpk 签名
│   └── test/             #   Node 单元测试（node --test）
├── android-sync/         # 手机端安卓同步器源码（Kotlin + Compose）
│   ├── app/src/main/java/com/bandqq/sync/
│   │   ├── onebot/       #   OneBot v11 客户端 / 协议解析
│   │   ├── sync/         #   消息桥 / 存储 / 手环WS服务 / 缩略图 / 保活
│   │   └── ui/           #   设置界面
│   └── app/src/test/     #   单元测试（协议 + 翻页 + 回放）
└── docs/                 # 文档
```

## 协议说明（手环 ⇄ 手机）

单帧 JSON 文本，UTF-8。

| 方向 | type | 说明 |
|------|------|------|
| 手机→手环 | `message` | 新消息（含 message_id / at_me / thumb） |
| 手机→手环 | `list` | 会话列表（含最后消息摘要） |
| 手机→手环 | `history` | mode=recent/older，has_more 标记 |
| 手机→手环 | `login_info` / `state` / `send_failed` | 登录信息 / 连接状态 / 发送失败 |
| 手环→手机 | `hello` / `refresh` | 握手 / 拉会话列表 |
| 手环→手机 | `send` | 发送消息（target_id / chat_type / content） |
| 手环→手机 | `get_history` | limit / older / before_time（翻页） |

## FAQ

**Q: 手环连不上？**
检查手机 App 是否已连接 OneBot；手环设置里服务器地址应为手机可达地址；蓝牙需保持配对。

**Q: 消息延迟高 / 收不到？**
确认无障碍保活已开启；OneBot 端建议同时开启 WS 与 HTTP。

**Q: 历史翻页没反应？**
部分 OneBot 实现的 `get_group_msg_history` 对 `message_seq` 支持不同，v1.1.1 已做锚点兼容（history_seq → message_id 回退 → 本地缓存兜底）。

## 许可证

MIT License
