# MEMORY.md — 跨会话项目记忆（AI 协作恢复上下文用）

> 本文件是 band-qq 项目的持久化记忆。AI 助手在新会话中克隆本仓库后，应先读本文件 + `docs/` 恢复全部上下文。

## 项目定位
小米手环9 Vela 快应用（手环QQ客户端）+ Android 同步器 APK。
链路：手环 ⇄ 蓝牙 interconnect(xms-wearable-lib) ⇄ 同步器App ⇄ OneBot v11(WS:3001/HTTP:3000) ⇄ SnowLuma(协议端, hook型, WebUI:5099) ⇄ QQ。
上游：https://github.com/Astroptis/band-qq-assistant ；本仓库为完整版镜像（含签名/产物/legacy）。

## 版本线
- v1.1.x：旧 lineage（stapxs 移植版，源码已失传，legacy/ 有 7z 分卷）
- v2.x：基于 Astroptis main（opencode 基线）重做。**当前 v2.1.0**（versionCode 21）
- 签名一致性：rpk 与 APK 同源证书，APK SHA-256 = af8819e27a6ec8d84537ec86937cf016e780376305328abaa4eb79e8c626b004

## v2.1.0 已完成（2026-09-09）
1. **手环图标白边**：根因=RGBA 透明画布（768 边缘像素全透明），Vela 启动器合成露白。修复=全出血 108×108 纯 RGB 图标（绿底白 Q 环），versionCode 21 刷缓存
2. **列表抖动根治**：`tid` 必须是静态字段名字符串（Vela 语法，不能插值）；120ms 尾沿防抖；convSignature 签名比对（内容不变不进渲染管线）；列表项定高 86px；未读角标绝对定位
3. **性能架构 v2 协议**：重活全在手机端——CQ码剥离、emoji降级、名字截短(n9)、头像字符(achar)、色相(hue)、预览截短(prev)、时间格式化(tstr)、未读计数，手环零计算直渲染；手环热路径无字符扫描
4. **新功能（stapxs 特性重做）**：未读角标（手机端事实源，read_chat 回执清零）、会话预览+时间、彩色头像(hue→hsl)、快捷回复（手机端配置 CQ 码原文，按钮显示剥离后纯文本，最多6条）、历史翻页（before 时间锚点 + has_more）
5. **Android miuix UI**（基线已含，本版保留）：Compose + top.yukonga.miuix.kmp:miuix-ui-android:0.9.3
6. **SnowLuma WebUI 内嵌**：SettingsScreen 一键打开内嵌 WebView（默认 http://主机:5099 扫码登录/配置协议端）

## SnowLuma 内嵌结论（调研定论，勿再试）
SnowLuma 是 **hook 型**协议端（ptrace 注入真实 Linux QQ 进程，NTQQ packet sniffer + Node 原生 addon）：
- nodejs-mobile 停更在 Node 18 < SnowLuma 要求的 22.13+，且非 root Android seccomp 禁 ptrace → 进程内嵌不可行
- 官方 Android 路径 = Termux + proot Ubuntu + Linux QQ arm64 + Xvfb/noVNC（官方标注"不保证可用"）
- 许可证：非商业，公开分发修改版需书面授权（motricseven@foxmail.com）
- 最终方案：App 只做 OneBot 客户端 + 内嵌 WebUI WebView + README 提供 Termux 部署指引

## 构建配方（Linux 容器实测）
- JDK: Temurin 17 (/home/z/tools/jdk)；Gradle 8.13 (/home/z/tools/gradle/gradle-8.13)
- SDK: /home/z/tools/android-sdk；**目录陷阱**：包名 platforms;android-37.0 装出 android-37.0 目录，AGP 找 android-37 → `cp -r android-37.0 android-37` + 改 android-37/package.xml 的 path + source.properties 的 ApiLevel=37/Platform.Version=37
- gradle.properties: `android.suppressUnsupportedCompileSdk=37.0`
- rpk: `cd band-qq && npm i && npx aiot release`（sign/release/{private,certificate}.pem 已在仓库）
- APK: `JAVA_HOME=… gradle assembleRelease`（根目录 keystore.jks, storePassword bandqq123/alias bandqq）
- **Vela 语法坑**：tid 用字段名（tid="id"）；样式不支持 :active 伪类
- 基线固有测试失败（非回归）：api.test.js 门控用例（Node24）、GameProtocolDetectorTest localhost 探测（容器环境）

## 待办 / 已知事项
- 手机离线缓冲（快应用关闭→停 interconnect 发送、消息落盘、冷启动回放）——未做
- 无障碍服务易用性改进——未做
- 多账号管理（Stapxs-QQ-Lite-X 借鉴）——未做
- 历史翻页目前基于手机端本地缓存（200条/会话）；如需拉取更早需接 OneBot get_msg 历史（手机端扩展）
- 字库 8241 字（stapxs 版曾扩到 21197，本基线未带回，InputMethod.ux + dic 结构支持直接替换 dic.js）

## 关键文件索引
- 手环：band-qq/src/pages/index/index.ux（列表/防抖/签名diff）、pages/chat/chat.ux（翻页/快捷回复/read_chat）、common/protocol.js（协议v2+convSignature）、common/store.js（未读/装饰/快捷回复）、common/api.js（interconnect 封装，勿动）
- 手机：android-sync/.../sync/MessageStore.kt（未读/Display预计算/翻页锚点）、sync/MessageBroker.kt（read_chat/before/quick_replies）、onebot/OneBotParser.kt（CQ剥离）、sync/InterconnectBridge.kt（onConnect 补推）、ui/SettingsScreen.kt（快捷回复编辑+WebUI）、WebUiActivity.kt

## v2.2.0（2026-09-10，versionCode 22）
1. **手环图标深色化**：背景改 #0D1015 近黑冷灰（AMOLED 手环融合），前景（蓝圆+白气泡+企鹅）不变；母版 icon_preview.png（用户提供，白底版已废弃），生成脚本 `scripts/darken-watch-icon.py`（flood fill 只换边缘连通白底，108×108 纯 RGB 全出血）
2. **Android 图标**：深色自适应图标（radial 渐变 #1A2129→#0D1015 底 + 前景 PNG 432px 图形占 66% 安全区，脚本 `scripts/gen-android-icon.py`）+ manifest android:icon/roundIcon（此前 v2.1.0 manifest 一直缺 icon 声明）
3. **液态玻璃悬浮栏**（miuix-blur 0.9.3）：BandQQApp 重构为 Box 叠层——内容层 `layerBackdrop()` 登记模糊源，底部 `drawBackdrop{ blur(4dp); colorControls(0.02,1.03,1.4) } + BloomStroke 边缘高光 + surfaceContainer 40% 叠色`；`isRuntimeShaderSupported()` false（Android <13）回退 `FloatingNavigationBar`
   - **坑**：miuix-blur AAR 声明 minSdk 33 → manifest 需 `<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp.blur"/>`（放 application 标签无效，必须放 uses-sdk 节点）
   - **坑**：官方示例 LiquidGlassNavigationBar 的 `vibrancy()`/`lens()` 在 v0.9.3 不存在（超前 API），可用的是 `blur(radiusX,radiusY)`（px）、`colorControls(brightness,contrast,saturation)`（位置参数顺序）、Highlight/BloomStroke/LightSource 构造
   - 4 个 Screen 根 padding bottom=96dp 给悬浮栏留穿透空间
4. **构建环境脚本化**：`scripts/setup-buildenv.sh` 一键重建（JDK17+Gradle8.13+SDK+android-37 目录陷阱修复），容器重置后直接跑
- rpk 签名 sign/release/{private,certificate}.pem；APK 签名根目录 keystore.jks（SHA-256 af8819e2... 与 v1.x/v2.1.0 同源，覆盖安装兼容）
