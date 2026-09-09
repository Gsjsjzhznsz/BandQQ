# BandQQ v2.1.0 — 小米手环 9 QQ 消息助手（手环快应用 + 安卓同步器）

> 🧠 **AI 协作记忆库**：[`MEMORY.md`](MEMORY.md) — 本项目的跨会话持久记忆（架构 / bug 台账 / 构建配方 / 任务清单）。任何新会话恢复上下文，先读它。

[![Version](https://img.shields.io/badge/version-2.1.0-blue)]() [![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Vela-green)]() [![License](https://img.shields.io/badge/license-MIT-brightgreen)]()

**BandQQ** 是一套开源的「小米手环 QQ 消息助手」双端方案：手环端运行 Vela 快应用（rpk），手机端运行安卓同步器（APK），通过小米互联蓝牙通道把 QQ 消息实时同步到手环，支持直接在手环上**查看 / 回复 / 翻历史消息 / 收图**。

> **上游仓库说明**：本项目上游为 [Astroptis/band-qq-assistant](https://github.com/Astroptis/band-qq-assistant)。v2.x 系列以上游 main（opencode 基线）为底重写：性能架构升级为「手机端预处理 + 手环零计算直渲染」，并带回未读角标 / 快捷回复 / 历史翻页 / 彩色头像等特性。感谢上游作者的奠基工作。

> 关键词：小米手环9 / Mi Band 9 / 小米手环QQ / 小米手环9 Pro / Redmi Watch / Vela 快应用 / 快应用 rpk / OneBot v11 / NapCat / Lagrange / LLOneBot / go-cqhttp / QQ 消息同步 / 手环回复QQ / 手环看QQ / 蓝牙消息助手 / Stapxs-QQ-Lite-X / wearable QQ / smartband chat / Mi Band QQ client

## v2.1.0 更新日志（当前版本）

### 性能与体验
1. **性能架构重构（防重启死机）**：所有非渲染必需的处理全部移到手机端——CQ 码剥离、emoji 降级、名称截短、头像字符与色相、预览截短、时间格式化、未读计数均在 APK 完成后随帧下发，手环端只做字段透传与渲染，热路径零字符扫描、零日期运算。
2. **会话列表抖动根治**：`tid` 静态键控 + 120ms 尾沿防抖 + 列表内容签名比对（内容未变不进渲染管线）+ 列表项定高 86px + 角标绝对定位，消息风暴下列表纹丝不动。
3. **手环图标白边修复**：根因是 RGBA 透明画布被启动器合成露白；重制为全出血 108×108 纯 RGB（无 alpha）图标，物理上不可能出现白边；versionCode 升至 21 刷图标缓存。

### 功能（stapxs 特性在本基线重做）
4. **未读角标**：手机端为唯一事实源，按会话持久化、封顶 99；手环打开聊天自动回执 `read_chat` 清零并回推列表。
5. **快捷回复**：手机端设置页可自定义（每行一条，支持 CQ 码）；下发手环的按钮标签自动剥离 CQ/emoji 成纯文本（不再显示格式代码），发送内容保留 CQ 码原文；群聊自动按群类型发送。
6. **历史翻页**：`before` 时间锚点 + `has_more` 判定，聊天页顶部「加载更早的消息」，prepend 保持阅读位置不跳动。
7. **会话列表信息升级**：彩色头像（按 targetId 稳定色相）、会话预览、时间串、临时会话标记。
8. **SnowLuma WebUI 内嵌**：设置页一键打开内嵌 WebView（默认 `http://协议端主机:5099`），扫码登录 / 开端点 / 配置 token 不用切应用。关于「把 SnowLuma 塞进 APK」：SnowLuma 是 hook 型协议端（需 ptrace 注入真实 QQ 进程 + Node 22+），受 Android seccomp 与其许可证双重限制**不可内嵌**，Termux(proot) 部署路径见下文。

### 界面
9. **miuix (HyperOS 风格) UI**：Android 端为 Compose + miuix 0.9.3（参考 HyperCeiler 视觉），底部四 Tab（主页/联系人/聊天记录/设置）。

### 保持不变
- APK 签名与 v1.1.x 完全一致（SHA-256 `af8819e2…b004`），可**直接覆盖安装**；rpk 与 APK 同源证书。

## v1.1.1 更新日志（历史版本）

### 修复
1. **会话列表抖动**：列表项启用 `tid` 键控 diff + 内容签名比对（无变化不重排）+ 消息事件 150ms 防抖，群聊连发不再整表闪烁跳动。
2. **输入法字库全量扩展**：8241 字 → **CJK 基本区全量 21197 字**（U+4E00–U+9FFF，基于 Unicode Unihan `kMandarin` 读音，常用字保持频率排序在前），生僻字「燚 龘 齉」等均可打。
3. **历史消息翻页失效**：重写手机端翻页链路——锚点优先用 OneBot 响应的 `message_seq`（兼容 NapCat），私聊附加 Lagrange `time` 秒级参数双兼容；本地无锚点时两段式先拉最新页建立锚点；锚点过新时自动续翻（≤3 跳）；`has_more` 按原始返回条数判定，不再误判「没有更早的消息」。
4. **冷启动丢消息**：三层修复——
   - 手机端记录手环离线期间到达消息的会话，手环重连成功后**自动回推每会话最近 30 条**（手环端按 `time|content` 幂等去重）；
   - 手环端本地持久化上限 30 条/会话 → **60 条/会话**、10 个会话 → **20 个会话**；
   - 手机端聊天记录持久化由单 key 全量重写改为**按会话分 key**，消除大字符串写入失败导致的整库丢失（旧数据自动迁移）；手环端同步可见联系人时统一字符串比较，杜绝数字/字符串 id 混用误清全部本地消息。
5. **rpk 应用图标**：图标按 Vela 快应用规范重制为 **108×108**，并升级 versionCode 触发手环端图标缓存刷新，桌面不再显示空白图标。
6. **其他**：私聊历史响应中自己发送的消息 targetId 重映射（此前会整条丢弃）；自发消息回填 OneBot 响应的 `message_id` 作翻页锚点；翻页收集去重；去重集合加上限保护；首次打开会话本地历史不足时自动从 OneBot 补齐最新一页。

### 保持不变
- 签名与 v1.1.0 完全一致（SHA-256 `af8819e2…b004`），APK / rpk 均可**直接覆盖安装**，无需卸载旧版。

## 功能特性

### 消息同步
- 实时接收 QQ 群聊 / 私聊消息（OneBot v11，兼容 NapCat、Lagrange、LLOneBot、go-cqhttp 等）
- 会话列表：彩色首字头像（Stapxs 风格）、最新消息预览、时间显示、按最新消息排序
- 未读角标（99+ 封顶）、`[@我]` 红色标签；@我消息橙色描边 + 手环长震动
- **断连补推**：手环重启 / 挂后台期间的错过的消息，重连后自动补齐

### 聊天与输入
- 气泡布局：自己绿色右侧 / 他人深灰左侧（Stapxs-QQ-Lite-X 风格）
- 时间分隔条（≥5 分钟自动插入）、群昵称按 ID 稳定取色
- **快捷回复**：9 条常用语一键发送
- **拼音输入法**：全键盘拼音输入，字库 **CJK 基本区 21197 字**，候选按常用度排序
- **历史消息翻页**：「加载更早」按 message_seq / message_id 锚点向前翻页，OneBot 不可用时回退本地缓存
- 图片消息自动抓取压缩为 96px 缩略图下发手环

### 可靠性
- OneBot WebSocket(:3001) 优先、HTTP(:3000) 自动回退，严格校验业务 retcode（发送失败回显原因）
- 手环端冷启动自愈（互联通道重建 + 全量状态重拉）
- 手机端前台服务常驻；聊天记录按会话分 key 持久化

## 系统架构

```
┌──────────┐  小米互联蓝牙通道   ┌─────────────────┐   WS :3001 / HTTP :3000   ┌───────────┐
│ 小米手环9 │ ◄────────────────► │  BandQQ 同步器   │ ◄────────────────────────► │  OneBot    │
│ Vela快应用│    interconnect    │  (Android APK)  │        OneBot v11          │ NapCat 等  │
└──────────┘                    └─────────────────┘                            └───────────┘
   band-qq/ (rpk)                  android-sync/                                  QQ 服务端
```

## 目录结构

```
band-qq/        手环端 Vela 快应用源码（aiot-toolkit 构建）
android-sync/   安卓同步器源码（Kotlin + Compose，AGP 8.13）
scripts/        构建脚本（build-rpk.sh / build-apk.sh / expand-dict-cjk.py 等）
docs/           NapCat 配置、签名说明等文档
dist/           v1.1.1 构建产物（rpk + APK）
legacy/         历史归档（v1.1.0 源码包、旧变体快照）
```

## 快速开始

1. 手环端：将 `dist/bandqq-watch-1.1.1.rpk` 安装到手环（Vela 快应用开发者模式 / 手环调试助手）。
2. 手机端：安装 `dist/bandqq-sync-release-1.1.1.apk`，授予后台运行权限。
3. 协议端：NapCat 开 WebSocket 服务端口 3001（或 HTTP 3000），在同步器设置页填入地址与 token。
4. 手环与手机通过小米运动健康保持连接，打开手环端 QQ 助手即可使用。

构建说明见 `scripts/README.md`；字库再生成：`python3 scripts/expand-dict-cjk.py <Unihan_Readings.txt>`。

## 许可证

MIT License（见 LICENSE）。基于上游 [Astroptis/band-qq-assistant](https://github.com/Astroptis/band-qq-assistant) 开发，聊天界面部分交互移植自 [stapxs/Stapxs-QQ-Lite-X](https://github.com/stapxs/Stapxs-QQ-Lite-X)。
