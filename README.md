<div align="center">

# 💬 BandQQ 腕间信使：低轨孤星

**小米手环 / Redmi Watch / 小米手表 上的 QQ 消息助手 · Vela 快应用 + Android 同步器双端方案**

[![Version](https://img.shields.io/badge/version-2.12.0-blue)](https://github.com/Gsjsjzhznsz/BandQQ/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Vela-green)]()
[![Devices](https://img.shields.io/badge/devices-Band%208~11%20%7C%20Watch%20S%20%7C%20Redmi%20Watch-orange)]()
[![License](https://img.shields.io/badge/license-MIT-brightgreen)](LICENSE)
[![Release](https://img.shields.io/badge/download-GitHub%20Releases-9cf)](https://github.com/Gsjsjzhznsz/BandQQ/releases)

![BandQQ 宣传图](docs/promo/banner-3x2.jpg)

**手环上直接看 QQ、回 QQ、翻历史、收图。**
手环端运行 Vela 快应用（rpk），手机端运行安卓同步器（APK），经小米互联蓝牙通道实时同步，
协议端对接 OneBot v11（NapCat / Lagrange / LLOneBot / go-cqhttp 均可）。

</div>

---

## 📱 设备分支（v2.10.0 起单 main 分支 + 构建期出包）

单一源码树经 `band-qq/tools/branch-release.js` 构建期生成各系列独立适配包——
designWidth 对准物理屏宽、样式按视觉密度系数换算、**键盘按分支原生化**（v2.12.0）、圆屏安全边距，杜绝 git 多分支漂移：

| 分支包 | 适配设备 | 屏 | designWidth | 键盘布局（v2.12.0） |
|---|---|---|---|---|
| `bandqq-band` | 小米手环 8 / 9 / 10 / 11 | 192×490 胶囊 | 192 | 胶囊屏全键（滚动字母轮 + 弧形进度） |
| `bandqq-bandpro` | 手环 8Pro / 9Pro / 10Pro | 336×480 方屏 | 336 | 方屏全键（按 336/432 等比换算） |
| `bandqq-redmiwatch` | Redmi Watch 5 / 5 eSIM / 6 | 432×514 方屏 | 432 | 方屏全键（432 原生标尺） |
| `bandqq-xiaomis` | Xiaomi Watch S3 / S4 / S5 | 466×466 圆屏 | 466 | 圆屏 QWERTY 全键（466 原生） |
| `bandqq-universal` | 通用兜底 | 192 | 192 | 同 band |

三套键盘布局全部来自 **AetherZeng1145/Vela-Input-Method-Revise**（见[引用项目](#-引用项目与致谢)），共享同一套**全量拼音字库（419 音节 27398 字，频序候选）**。

> 🧠 AI 协作记忆库：[`MEMORY.md`](MEMORY.md) — 跨会话持久记忆（架构 / bug 台账 / 构建配方）。新会话恢复上下文先读它。

## 界面预览（手环 11 官方 Vela 虚拟机实拍 · 212×520）

| 会话列表（五会话铺满 + @我 标 + 免打扰灰点） | 群聊（@我 金色高亮） |
|:---:|:---:|
| ![会话列表](docs/screenshots-vvm/01-会话列表-免打扰灰点.png) | ![聊天页@我](docs/screenshots-vvm/02-群聊-@我金色高亮.png) |

| 群聊拍一拍特效 | 私聊拍一拍特效 |
|:---:|:---:|
| ![群聊拍一拍](docs/screenshots-vvm/03-群聊-拍一拍特效.png) | ![私聊拍一拍](docs/screenshots-vvm/04-私聊-拍一拍特效.png) |

> 被 @ 时：列表金色「@我」角标 + 聊天页呼吸高亮 + 长震，三重感知；有人拍一拍你：居中紫色特效胶囊 + 晃动动画 + 长震；长按会话可开免打扰。截图由小米官方 Vela 虚拟机（VVD）直接运行 RPK 后 gRPC 实拍，非网页模拟。

## ✨ 功能特性

### 消息同步
- 实时接收 QQ 群聊 / 私聊消息（OneBot v11），@我 提醒、拍一拍特效、撤回灰显
- 彩色首字头像（Stapxs 风格）、未读角标（99+ 封顶）、免打扰、时间分隔条
- **断连补推**：手环重启 / 挂后台期间错过的消息，重连后自动补齐
- 图片消息自动压缩为 96px 缩略图下发（<12KB，并发 2 + 超时兜底）

### 聊天与输入
- **分支原生化拼音键盘**：胶囊 / 方屏 / 圆屏三套布局，全量拼音字库 27398 字（生僻字「燚 龘 齉」均可打），候选按常用度排序
- 快捷回复：9 条常用语一键发送（支持 CQ 码、二次确认防误触）
- 历史消息**分页窗口化**：点一次「加载更早」只前移一个窗口（零网络/零卡顿），DOM 恒 ≤ 渲染上限，另有「回到最新」按钮

### eSIM / 独立线路
- **eSIM 独立直连**（参考 merqury-vela）：手表经 @system.fetch 直连 OneBot HTTP API + Bearer token，互联断开自动切换，手机端连接时零手输下发配置
- **AstrBot 本地伴侣**（v2.12.0）：检测/拉起 AstrBot Bubble，本机 NapCat 一键快连 127.0.0.1，零电脑零局域网

### 可靠性
- OneBot WS(:3001) 优先、HTTP(:3000) 自动回退，API 全部走 echo 路由（WS-only 部署也能拉名单、发消息）
- 手机端前台服务 + 无障碍保活锚点 + 后台保活向导；消息落盘 300ms 防抖合并写
- 双端设置互通（表情渲染 / 震动 / 免打扰），夜间勿扰、群聊推送范围手机端判定，手环零开销

## 📥 安装（GitHub Releases 分发）

到 [**Releases**](https://github.com/Gsjsjzhznsz/BandQQ/releases) 下载对应文件：

1. **手环端**：下载你设备对应的 `bandqq-<分支>-2.12.0.rpk`，经 Vela 快应用开发者模式 / 手环调试助手安装；关于页可核对版本与分支后缀
2. **手机端**：安装 `bandqq-sync-release-2.12.0.apk`（签名 SHA-256 `af8819e2…` 全版本同源，直接覆盖安装），授予后台运行权限
3. **协议端**：NapCat 等开启 WS :3001（推荐）/ HTTP :3000，在同步器设置页填地址与 token，「测试连接」验证
4. 手机与手环经小米运动健康保持连接，打开手环端 QQ 助手即可使用

> 没有协议端？先装 [BandQQ DevTools](https://github.com/Gsjsjzhznsz/BandQQ/releases)（独立调试 APK，内置 OneBot 模拟器）即可完整体验全链路。

## 🏗️ 系统架构

```
┌──────────┐  小米互联蓝牙通道   ┌─────────────────┐   WS :3001 / HTTP :3000   ┌───────────┐
│ 小米手环  │ ◄────────────────► │  BandQQ 同步器   │ ◄────────────────────────► │  OneBot    │
│ Vela快应用│    interconnect    │  (Android APK)  │        OneBot v11          │ NapCat 等  │
└──────────┘                    └─────────────────┘                            └───────────┘
   band-qq/ (rpk)                  android-sync/        ▲                        QQ 服务端
      ▲                                  │               └── 也可跑在本机：
      └── eSIM 独立直连（@system.fetch）──┘       AstrBot Bubble / Termux / DevTools
```

## 目录结构

```
band-qq/        手环端 Vela 快应用源码（aiot-toolkit 构建；tools/branch-release.js 分支出包）
kb-variants/    键盘分支变体快照（rect 方屏 / circle 圆屏，构建期换装）
android-sync/   安卓同步器源码（Kotlin + Compose + miuix；含 AstrBot 伴侣模块）
devtools/       BandQQ DevTools 调试器源码（OneBot 模拟器）
scripts/        构建脚本（build-rpk.sh / build-apk.sh / setup-buildenv.sh 等）
docs/           NapCat 配置、签名说明等文档
legacy/         历史归档（v1.1.0 源码包、旧变体快照、全量字库出处）
```

## 🛠️ 从源码构建

```bash
# 手环端 rpk（单分支）
cd band-qq && node tools/branch-release.js bandpro   # band / bandpro / xiaomis / redmiwatch

# Android 同步器 APK
bash scripts/setup-buildenv.sh                       # JDK17 + Gradle 8.13 + SDK 一键重建
cd android-sync && JAVA_HOME=/home/z/tools/jdk-17.0.20.1+1 \
  /home/z/tools/gradle-8.13/bin/gradle assembleRelease -x lint
```

构建说明见 `scripts/README.md`；字库再生成：`python3 scripts/expand-dict-cjk.py <Unihan_Reading...>`。

## 📦 更新日志

### v2.12.0（当前版本 · vc52）

> 三件事：**键盘分支原生化**（用户报告「以前给其他分支单独适配的键盘不见了」——v2.10.0 分支包里非手环机型仍被塞着胶囊键盘只做数值缩放；现按分支换装三套原生布局：bandpro/redmiwatch 方屏版、xiaomis 圆屏 QWERTY 版，方屏修上游两处笔误 onscroll 拼写/percent 绑定，方屏符号键扩充 ～！？【】「」、·）+ **全量拼音字库**（legacy 变体 20924 字全量池回归，与现役频序字典合并为 **419 音节 27398 字**，常用字频序拱顶不变、生僻字可打）+ **AstrBot 本地伴侣**（集成 MuFengDR/AstrBot-Bubble-Android-App：设置页新增 AstrBot 卡片，检测/一键拉起 com.astrbot.astrbot_bubble、探测本机 NapCat :3001/:3000 就绪状态、一键填入 127.0.0.1 本机地址；不做二进制合并——对方是 Flutter + 64MB Ubuntu rootfs 独立应用，伴侣模式才是性能与体积最优解）+ **README 项目化改版**。

- 验证：四分支 rpk 解包 68 项断言全 PASS（新增键盘布局类残留检查/按键宽换算/滚动总宽词边界正则/全量字库罕见字标记）；node 单测 80 测 79 过（存量基线一致）；APK badging vc52/2.12.0 + AstrBotBridge 多 dex 标记 FOUND
- 版本：RPK 四分支+通用 2.12.0（vc52，versionName 带分支后缀）+ 同步器 2.12.0（vc52，签名同源 af8819e2）

<details>
<summary><b>v2.10.0</b> — 分支级界面适配 + 历史分页窗口化 + eSIM 独立直连 + 性能四项（点击展开）</summary>

- **分支级界面适配**：根治大屏「缩放异常」——单一 designWidth=192 在 432 宽屏被固件放大 2.25 倍；改为构建期四分支包（band 192 / bandpro 336 / xiaomis 466 / redmiwatch 432），样式值按视觉密度系数换算非等比缩放，圆屏安全边距
- **历史消息分页窗口化**：「历史达到额度就异常」根修——渲染 DOM 恒 ≤ renderCap 与数据层解耦，点一次「加载更早」本地窗口前移一批零网络，本地到头才拉手机端，新增「回到最新」
- **eSIM 独立直连线路**：参考 merqury-vela，@system.fetch 直连 OneBot HTTP + Bearer token，direct_config 帧下发配置，sendUpstream 三态编排，chat 4s/列表 20s 前台轮询熄屏即停
- **性能四项**：消息落盘 300ms 防抖合并写 / 排序幂等 WeakSet / 数据层 100→120 / 启动冗余 send 清理
</details>

<details>
<summary><b>v2.9.x</b> — 真实 NapCat 接入五连修（点击展开）</summary>

- **v2.9.6**：RW5E 等非手环设备二级页黑屏根修（flex:1 滚动视口高度计算失败 → 四页统一 absolute 铺满同构改造）；全设备版本号统一（APK/rpk 同版同 vc 单轨）
- **v2.9.5**：发送链路 WS 回退（WS 能连就能发）+ compose 双发根治（先查会话类型只发一条）+ requestApi baseUrl 丢弃回归修复
- **v2.9.4**：群聊识别成个人联系人根治（get_group_info 补名固化 + 蓝「群」徽章）+ 联系人 WS API 通道与 30s×10 重试 + 快捷回复二次确认 + 演示模式清理 + 主页日志面板防坍缩
- **v2.9.1**：主页面列表铺满整屏（Vela scroll 背景只绘制内容高度的引擎行为）；同步器落盘文件日志（免 root 排查 + 导出 + 环境自检 + 授权全链路）
</details>

<details>
<summary><b>v2.7.0 ~ v2.9.0</b> — 自动拉起 / 拍一拍 / 免打扰 / DevTools（点击展开）</summary>

- **v2.9.0**：拍一拍全链路（私聊也有提醒）+ 会话免打扰（红点变灰 + 不拉起）+ 拉起范围收敛「仅@我和拍一拍」+ 演示模式彩蛋
- **v2.8.x**：DevTools 1.x 系列（OneBot 模拟器 / 主线程网络 IO 根修 / 版本互认 / 断连归因）+ 双端设置互通 + 双端关于页 + @我 类型断裂根修
- **v2.7.0**：快应用自动拉起（launchWearApp + 预告通知 + 取消机制）+ 头像组件 + 快捷回复即时同步 + @我 呼吸动效
</details>

<details>
<summary><b>v2.1.0 ~ v2.6.0</b> — 性能架构重构与 stapxs 特性回归（点击展开）</summary>

- **v2.6.0**：预测性返回重做（KernelSU 原版对齐 + PredictiveBackHandler）+ 手环表情支持（80 项映射）+ Vela 虚拟机验收体系 + Band 11 适配
- **v2.5.0**：数组段格式 @我 修复 + Band 11 弹性布局 + 新消息震动 + 测试推送模拟器（9 场景）
- **v2.4.x**：保活向导 / 崩溃三层防御 / 撤回灰显 / 勿扰 / 群聊推送范围
- **v2.1.0**：性能架构重构（手机端预处理 + 手环零计算直渲染）+ 未读角标 / 快捷回复 / 历史翻页 / 彩色头像回归 + miuix UI
- **v1.1.1**：会话列表抖动根治 + 字库全量扩展 + 历史翻页重写 + 冷启动丢消息三层修复
</details>

## 🙏 引用项目与致谢

本项目站立在这些开源项目之上，排名不分先后：

| 项目 | 引用方式 |
|---|---|
| [**AetherZeng1145/Vela-Input-Method-Revise**](https://github.com/AetherZeng1145/Vela-Input-Method-Revise) | 手环端拼音键盘三套布局的直接来源（Capsule 胶囊 / Cube-For-Redmi-Watch 方屏 / QWERTY 圆屏分支），BandQQ 按设备分支构建期换装并扩充符号键 |
| [**NEORUAA/Vela_input_method**](https://github.com/NEORUAA/Vela_input_method) | 上述键盘的上游原版（Vela 手环拼音输入法开山之作），事件与属性接口一脉相承 |
| [**Astroptis/band-qq-assistant**](https://github.com/Astroptis/band-qq-assistant) | 本项目上游，v2.x 以上游 main 为底重写性能架构并回归 stapxs 特性 |
| [**stapxs/stapxs-qq-lite**](https://github.com/ImStapxs/stapxs-qq-lite)（含 Lite-X） | 气泡布局 / 彩色首字头像等视觉范式的参考 |
| [**CoraTech-Wear/merqury-vela**](https://github.com/CoraTech-Wear/merqury-vela) | eSIM 机型独立直连线路的实现参考（@system.fetch 直连 OneBot HTTP） |
| [**MuFengDR/AstrBot-Bubble-Android-App**](https://github.com/MuFengDR/AstrBot-Bubble-Android-App) | AstrBot 本地机器人伴侣（手机 proot 一体化部署 NapCat + AstrBot，BandQQ 设置页一键检测/拉起/快连） |
| [**AstrBot**](https://github.com/AstrBotDevs/AstrBot) | 多平台聊天机器人框架，经 AstrBot Bubble 接入后可为 QQ 号提供大模型自动回复 |
| [**miuix**](https://github.com/miuix-kotlin-multiplatform/miuix) | Android 端 HyperOS 风格 Compose UI 框架 |

> 感谢小米 Vela 团队的快应用生态与官方 VVD 虚拟机——本项目的界面验收全部由其 gRPC 实拍完成。

## 📄 许可证

[MIT](LICENSE)。第三方组件以其自身许可证为准；引用项目版权归原作者所有。

---

> 关键词：小米手环9 / Mi Band 9 / 小米手环10 / 小米手环11 / 小米手环QQ / 小米手环9 Pro / Redmi Watch / Xiaomi Watch S / Vela 快应用 / 快应用 rpk / OneBot v11 / NapCat / Lagrange / LLOneBot / go-cqhttp / QQ 消息同步 / 手环回复QQ / 手环看QQ / 蓝牙消息助手 / eSIM 手表 / AstrBot / wearable QQ / smartband chat
