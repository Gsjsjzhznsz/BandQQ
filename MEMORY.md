# MEMORY.md — 跨会话项目记忆（AI 协作恢复上下文用）

> 本文件是 band-qq 项目的持久化记忆。AI 助手在新会话中克隆本仓库后，应先读本文件 + `docs/` 恢复全部上下文。

## 项目定位
小米手环9 Vela 快应用（手环QQ客户端）+ Android 同步器 APK。
链路：手环 ⇄ 蓝牙 interconnect(xms-wearable-lib) ⇄ 同步器App ⇄ OneBot v11(WS:3001/HTTP:3000) ⇄ SnowLuma(协议端, hook型, WebUI:5099) ⇄ QQ。
上游：https://github.com/Astroptis/band-qq-assistant ；本仓库为完整版镜像（含签名/产物/legacy）。

## 版本线
- 当前正式线：v2.26.1（vc69）—— 新 CI 胖包“解析失败”排查定案 + 打包硬化（doNotStrip 引擎五件套 + v1/v2/v3 全代签名显式启用 + Release 附 SHA256SUMS.txt）（2026-10-05）
- 前情：v2.26.0（vc68）—— NapCat Worker SIGSEGV 崩溃循环终结（四级自愈阶梯 + 版本钉扎 4.18.28 + 看门狗主进程判定根修 + ELECTRON_RUN_AS_NODE 反检测回退链 + fd9 引擎管道）（2026-10-05）
  - CI 首建（用户"哪里有ci，都没有写过工作流文件"）：.github/workflows/ci.yml（push/PR 触发：rpk 四分支+node 单测 / APK 双 flavor 单测+Debug 出包）+ release.yml（tag v* 触发：tag↔versionName 一致性校验+双 APK+四分支 rpk 六资产自动发 GitHub Release）；签名 keystore.jks 与 rpk pem 均已入库全程零 secrets；README 加 CI 徽章
  - AGP 9.0.1 + Gradle 9.1.0 迁移（CI 十一轮定位的终极解）：AGP 8.13.2 的 sdklib 解析不了 Google 新 minor 版本打包（platforms;android-37.0 的 `<api-level>37.0</api-level>` + Platform.Version=17 笔误），hash 'android-37' 永远查不到（FullLoading 路径）且 DirectLoading 需 AGP 默认 build-tools 35.0.0 预存；AGP 9 内置 Kotlin（移除 org.jetbrains.kotlin.android，保留 plugin.compose）+ applicationVariants 移除（产物改名移至 CI 收集阶段）+ manifest 禁 uses-sdk 版本属性（tools:overrideLibrary 仍允许）+ compileSdk=37+compileSdkMinor=0 直接命中原生 platforms;android-37.0，**零元数据手术**；AGP 9.0.1 要求 Gradle ≥9.1.0
  - api.js send 就绪门控真修（本地基线 106/107 挂项）：waitReady/markReady 门控此前只在 connectStatus 生效，send 漏接 → onopen 前业务帧直发丢失；修后 node 107/107 全绿
  - AstrBotScreen.kt 残缺引用 nSecondary→onSurfaceSecondary（v2.25.0 提交时引入的 bundled 编译阻断）
  - branch-release.js 版本行正则通配加固（manifest bump 漏同步 about.ux 不再炸构建）；about.ux 同步 2.25.0→2.25.1
  - 验证：node 107/107；APP 单测 250/250；双 APK vc67/2.25.1（AGP9 无 badging 校验脚本，以 outputs 产物为准）
- 前情：v2.25.0（vc66）—— NapCat 二次启动崩溃根治+反检测自动全开+仅NapCat模式停6199刷屏+LinuxQQ断点续传（2026-10-04）
- 前情：v2.24.0（vc65）—— 发送链根修(params-only)+NapCat 状态/日志/配置三联动+一键填入热重连（2026-10-04）
  - 发送链根修（用户第五份 bandqq-2026-10-04.log 定案：WS 3001 已连+get_group_list ok 但 send_group_msg 一律 retcode=200 "TypeError: Cannot read properties of undefined (reading 'type')"@aye napcat.mjs:74013）：NapCat 4.18.28 HTTP httpApiRequest 把**整个请求体直接当 params**（action 从路径取），旧 WS 风格信封 {action,params} → body.message=undefined → $a(undefined)=[undefined] → aye() 段校验 n.type 崩；修=buildSendParams 拆出 params-only 体（与 direct.js 直连通道同形状——该通道从未失败），WS 通道仍走信封；OneBotClient.sendMessage 改 judge() 语义（HTTP 200 但 retcode!=0 一律业务失败，"不支持的Api"才换通道）
  - NapCat 状态联动（用户"napcat启动完成还在显示启动"）：①脚本 wait_napcat_ports（/dev/tcp 探测 3001/3000，仅 NapCat 模式前台调用出 stage 100 终态，AstrBot 模式后台只打日志）②EngineManager stage≥100 → State.Running（不再停"安装中 100%"）③EngineHooks.napcatConnected（main sourceSet 钩子，astrbot-engine 仅 bundledImplementation 不可直接 import）+ SyncService 包装 OneBotListener onState(true) → EngineManager.onNapcatConnected()（WS onOpen 即推进 Starting/Installing→Running，比端口探测轮询早）④AstrBotScreen 状态卡新增"● 手环直连：已连通/○ 未连通"行（OneBotStateBus 实时驱动，区分"容器在跑"与"QQ 登录后 3001/3000 可连"两层状态）
  - NapCat 日志接入（用户"log没有napcat的log"）：①脚本 napcat_console_tap（tail -F napcat-console.log 逐行剥 ANSI/截 160 加 [NAPCAT] 前缀写回引擎 stdout；引擎停止链 pkill/重入/管道断裂三重收尾）②EngineManager.logSink + logLine 全量转发 ③bundled AstrBotScreen 首帧 LaunchedEffect 挂 sink → LogBus("AstrBotEngine") → 主页实时日志面板
  - 一键填入热重连（用户"一键填写为什么没有用"）：根因=旧 configure() 只换引用不重连，运行中 WS 仍挂旧地址；修=OneBotClient.reconnectWith（关旧 WS→重连循环 1s 巡检用新端点重建，onState(true) 后 broker 自动重拉联系人）+ SyncService.reconnectEndpointNow 静态钩子 + bundled「一键填入本机 NapCat 地址并保存」与设置页「保存」两处接入
  - 看门狗升级「保活+配置升级」双职责（QQ 扫码登录后 NapCat 新生成的 onebot11_<uin>.json 默认无端口服务端→进程活着端口永不监听的慢性死角）：每轮无条件 --step napcat-start（进程死→拉起；配置被 ensure_napcat_configs 升级→自动重启生效；一致→内部跳过 no-op），输出追加 watchdog.log
  - 撤回闭环补完：手环发起 delete_msg retcode=0 后本机 store.recallByMessageId（全会话反查，新 API）灰显+buildRecallFrame+会话帧回推（旧链路只有 toast，手环聊天页永不灰显——此前撤回感知只覆盖对方撤回的 onRecall 事件）
  - 验证：node 106/107 基线（api.test.js 环境失败）；APP 单测双 flavor 250/250 全绿（OneBotClientTest/MessageBrokerTest 同步三参 callback+params-only 断言）；双 APK badging vc65/2.24.0 签名 af8819e2 同源；四分支 rpk manifest 2.24.0-<tag>/vc65/designWidth 正确，compose.js 含 176/236/264 高度链常量
- 前情：v2.23.0（vc64）—— 键盘第五轮高度链收紧铲黑区+AstrBot cwd 污染根修+NapCat per-uin 配置升级+QQ 账号提取（2026-10-04）
  - 键盘第五轮（用户实测偏高+底部黑区）：rect 字母区实际 170px 而 scroll 261 预算死区 91px=黑区根因；修=scroll 261→176/容器 321→236/KB_H_RECT 349→264（28+236），branch-release 同步；VM 双分支实测（redmiwatch a's→按时哀伤阿是+bandpro q'w→请问千万气温，候选栏置顶+三排完整+键带止于 y=456=锚点469−缓冲，剩余底部 45px=系统手势保留区）
  - AstrBot 启动失败根修（第四份日志定案：start_napcat 主 shell cd $HOME 污染工作目录→uv run main.py 在 /root spawn 必败）：修=launcher 子 shell 内 cd+launch_astrbot cd 移到 uv run 前+main.py 前置检查
  - NapCat 检测根修：登录账号只读 onebot11_<uin>.json，模板无效；ensure_napcat_configs 扫描升级全部账号配置+变更自动重启+webui 一致跳写；NAPCAT_OB11_BODY 占位符展开
  - QQ 账号提取（onebot11_<uin>.json 文件名+console.log 兜底）+密码卡状态自动刷新+主页日志上移
- 前情：v2.22.0（vc63）—— 候选选择栏置顶+NapCat启动链四件套+密码复制/AstrBot开关（2026-10-04）
  - NapCat 启动链（用户"napcat没有启动"+第三份 engine-2026-10-04.log 定案：v2.21 全链生效后唯一断点=装而不启，3001/3000 永不监听；且旧 onebot11.json 两个 server 数组为空）：ensure_napcat_configs（onebot11.json 补 HTTP:3000/WS:3001 服务端+旧形状自动升级+webui.json 固定 5099/Token bandqq-napcat）+ start_napcat（Xvfb :20+launcher.sh 后台，napcat-console.log 落盘）+ 看门狗（5min 巡检 napcat-start 步骤重启）+ AstrBot 启动前并行拉起；EngineManager 停止链补杀 qq/Xvfb；探测细化 6185/5099 也算就绪（QQ 未扫码给指引）
  - 密码复制+AstrBot 开关（用户"自动读取2个程序的密码给复制"/"是否启动安装astrbot选项"）：AstrBotScreen「密码与登录」卡（AstrBotSecrets：引擎日志 Initial password + 容器 webui.json token，一键复制+开控制台 :6185+ NapCat 扫码 :5099）+「启动 AstrBot 机器人」Switch（prefs engine_start_astrbot→ASTRBOT_ENABLE 环境变量→脚本仅 NapCat 模式 while 常驻）
  - 验证：verify_2220 82/82；node 106/107 基线；APP 单测 250/250；badging vc63/2.22.0 签名 af8819e2 同源
- 前情：v2.21.0（vc62）—— 方形键盘 VM 根治第三轮（真复现）+ NapCat 离线包预下载（2026-10-04）
  - 键盘：重建 Vela VM（sdk.zip+image.zip CDN 重下，lib64 深拷贝 + LD_LIBRARY_PATH + platforms;android-37.0 package.xml api-level 37.0→37 元数据手术过 Gradle 平台解析）→ redmiw5 432×514 compose 入口包**首次完整复现**用户故障：Z-X-C-V-B-N 排拦腰截断+动作行叠进字母区+下展候选层裁剪。解包取证定案双根因：v2.19「固件幻影 83~85px」实为 #keyboard67 样式表残留 position:absolute;top:82px（62 圆屏时代定位遗产，幻影=自家 CSS）；rect 容器 height:274 装不下 261+60=321，固件把动作行上提 47px 叠进第三排。修=容器 274→321 高度链闭合（28+321=KB_H_RECT 349=dock 同值）+ 铲除 keyboard67 absolute；scroll 261 预算保留兜底。VM 实测 redmiwatch/bandpro 两方形分支全过（三排完整/动作行归位/输入出字 ra→然后让我想想/下展完整含▲收起）
  - 引擎：用户上传第二份 engine-2026-10-04.log（v2.20 实战）分析=会话1-3 历史已修，会话4（10:25）v2.20 全链生效后唯一断点=napcat.sh 内部自带下载不受 gh_fetch 管控（自测代理 ghfast.top 爬至 1.3% curl(18) partial → exit=1）；修=ensure_napcat_zip 预取 NapCat.Shell.zip 至脚本同目录（上游 download_napcat 检测本地包即跳过内部下载，官方支持路径）+ unzip -t 自验 + 失败不阻断留上游兜底；日志分析后删除+commit e254add
  - 验证：verify_2210 86/86 PASS（继承 73 项+新增 13）；node 107/106（基线1）；APP 单测双 flavor 全绿（OneBotParser 37/MessageBroker 22/MessageStore 24 等）；badging vc62/2.21.0 targetSdk28 双包，签名 af8819e2 同源；bundled 含 libbusybox+rootfs+ensure_napcat_zip 脚本—— 引擎日志驱动三线更新（分析用户上传 engine-2026-10-04.log 定案：会话 1 DNS 失败=旧脚本残留属预期；会话 2/3 实证 v2.19.0 DNS 根修+清华镜像竞速+uv/LinuxQQ 签名回退已全部真机生效）：①NapCat 链「sudo 不存在」根修（上游安装脚本硬性 command -v sudo 检查，v2.18.1 压缩必装清单副作用；修=ensure_sudo_shim 透传垫片 /usr/local/bin/sudo: exec "$@"，零联网零体积）②GitHub 下载 0 字节挂死根治（胜出代理 TCP 已连但响应阶段挂死 35s+，--connect-timeout 管不到；修=gh_fetch 统一入口：--speed-time 20 --speed-limit 512 断流自杀+--max-time 900，失败遍历「胜出者→其余存活代理→直连」候选序列（gh_build_candidates），全灭重竞速再试一轮；uv/napcat.sh/AstrBot ls-remote+clone 全接入；LinuxQQ CDN 加断流自杀×签名回退保持；network_test 同会话缓存）③启动可视化+控制台入口（脚本新增 stage() 输出 [STAGE:百分比:描述]，EngineManager 解析驱动 AstrBot 标签页「启动进度」卡 8 阶段大白话+进度条+预估耗时；中断改提示「重试不从头装」；主动停止不再误报 read interrupted；修 L_* 未定义的残缺文案；新增「打开控制台」按钮=泡泡版同款系统 WebView 打开 127.0.0.1:6185 复用 WebUiActivity）；验证 verify_2200 73/73+node 106/107 基线+APP 双 flavor 全绿+双 APK vc61/2.20.0（2026-10-04）
- 前情：v2.19.0（vc60）—— 三线修复+增强：①方形分支键盘「只显示一半」VM 实测根治（redmiw5 官方模拟器 432×514 复现取证：固件底部 ~49px 手势保留区 device.getInfo 不可见（SH 仍报 514）→ 一切 top=屏高-键盘高 写死常量把键盘推进保留区；且该固件对 scroll 内容注入「上方静态流高度」幻影纵向偏移（实测 83~85px，v2.17 字母滚动区显式高后预算不足即末行被裁=历代半屏键盘复发共同机制）；修=kb-dock 改 bottom:0 锚定（零视口常量）+rect 字母区纯流式重构（动作行移字母区下方/T9 死代码删除/进度条删除）+字母滚动区显式高 261=幻影85+行180+余6（KB_H_RECT 283→349）+预览区 bottom 锚定+预览文字单行紧凑；VM 实测 bandpro/redmiwatch/band9 键盘截图全过+拼音组合候选正常）②AstrBot 容器 DNS 根修+多源竞速（rootfs 资产取证实锤发行包 resolv.conf 烙着构建机 systemd-resolved 毒桩 nameserver 127.0.0.53（Azure VM 残留），v2.18.1「已有 nameserver 即跳过」幂等检查被毒桩骗过修复从未生效；修=脚本侧无条件重写+getent 自检，EngineManager 侧宿主生成 resolv.conf+proot -b 绑定（Termux proot-distro 同款）双保险；多源竞速=ubuntu-ports 6 镜像 bash /dev/tcp 并行探测 ≤10s 首个打通者胜出+update 失败遍历可达源重试，GitHub 12 代理串行 240s 改全并发竞速 ≤15s+直连兜底，napcat.sh 走竞速代理，uv sync 换 Python 镜像重试，apt 通信硬化提前落盘）③APK 日志面板位置回移（标签行并入标题行横向滚动，开服务后日志盒 y 坐标恒定不跳）；验证 verify_2190 68/68+node 106/107 基线+APP 125+125 全绿+VM 三档案截图+双 APK badging vc60/2.19.0（2026-10-04）
- 更早：v2.18.0（vc58）—— 三线修复：①引擎「环境不完整」根治（升级用户设备上 v2.17 自愈脚本从未部署：isInstalled=true 跳过安装流程=旧脚本常驻容器；修=每次启动前 assets 强制刷新容器内三脚本/配置（内容一致跳写+保留 REINSTALL_PLUGINS_FLAG），start() 前置 installIfNeeded）②撤回/v11 重试+验证+明细（用户 10-03 日志实证 BandQQ 侧链路全通、失败点=NapCat recallMsg NT 事件超时 retcode=1200 result 5 社区已知瞬态型；v11 通道失败自动重试 3 次 1.2s/2.5s，delete_msg 1200 先 get_msg 验证（消息已不存在按成功回帧），终败透传协议端 retcode；另 HTTP 拒连自适应降级：连 3 次拒连→10min WS-only，configure() 重置）③键盘 dock 顶部锚定+挂载自愈（Vela VM 四分支实测：band9 镜像页面高度解析异常内容高≈665>视口 490，bottom:0 把键盘推出屏外≈175px=用户"只显示一半"；top=视口高−键盘物理高（band157/bandpro197/xiaomis154/redmiwatch231 构建期写入，页面顶部锚定全固件可靠）+ 弹出后 120ms/650ms 两拍 1px 收缩还原强制重排（"输入一下就恢复"的自动版））（2026-10-03）
- 历史线：v2.17.0（57，引擎启动自愈+proot fd 告警摘除+message_id 撤回链三断点+键盘内层 scroll 显式高+渲染器 v2.17）/ v2.16.0（56，rootfs 前缀拍平+message_id 数字形态+直连六类 v11+键盘宿主去 flex 化）/ v2.15.0（55，targetSdk 28 W^X 根修+三层 kbHpx+演示模式补齐）/ v2.14.0（54，AstrBot 独立 Tab+键盘显式总高+智能渲染器+libbusybox 双通道）/ v2.13.0（53，键盘 NEORUAA 换装+AstrBot 胖/瘦双 APK+OneBot v11 扩展+AGPL）/ v2.10.0（51，分支级适配+分页窗口化+eSIM 直连）/ v2.9.6（50，四页 absolute 黑屏根修+版本统一）… 详见各节与 README
- git 分支线 v2.11.x（redmi-watch/xiaomi-watch-s/band-pro）已废弃归档，由构建期四分支包取代

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

## 构建配方（Linux 容器实测 · v2.25.1 起 AGP 9.0.1 + Gradle 9.1.0）
- JDK: Temurin 17 (/home/z/tools/jdk-17.0.20.1+1)；Gradle 9.1.0 (/home/z/tools/gradle-9.1.0/bin，AGP 9.0.1 最低要求 9.1.0)；SDK: /home/z/android-sdk（platform-tools adb 需进 PATH）
- **SDK 装法（v2.25.1 终极简化）**：`sdkmanager "platforms;android-37.0" "build-tools;37.0.0" "build-tools;35.0.0" "platform-tools"` 即可，**零元数据手术**——AGP 9.0.1 + app/build.gradle.kts 的 `compileSdk=37 + compileSdkMinor=0` 直接命中原生 platforms;android-37.0。旧 v2.9.0 的 android-37 目录改名/api-level/Platform.Version 手术配方仅适用于 AGP 8.13.2（其 sdklib 解析不了 minor 打包，Google 官方 zip 自带笔误 Platform.Version=17 雪上加霜），已随 AGP 9 迁移全部作废
- 一键重建：`scripts/setup-buildenv.sh`（v3 配方）
- GRADLE_USER_HOME 用默认 ~/.gradle（654M 缓存全）；/home/z/tools/gradle-home 缓存不全，--offline 会因缺 appcompat 依赖挂
- 基线固有测试失败（非回归）：api.test.js 门控用例（Node24）、GameProtocolDetectorTest localhost 探测（容器环境，102 例中 1 失败）
- rpk: `cd band-qq && npm i && npx aiot release`（sign/release/{private,certificate}.pem 已在仓库；产物在 band-qq/dist/ 非 .temp_band-qq/dist）
- APK: `JAVA_HOME=… gradle assembleRelease`（根目录 keystore.jks, storePassword bandqq123/alias bandqq；签名 SHA-256 af8819e2 同源）
- **Vela 语法坑**：tid 用字段名（tid="id"）；样式不支持 :active 伪类；小尺寸 text 的 onclick 可能不响应点击（gRPC 注入场景），点击区挂大容器（div）兜底
- **VVD 虚拟机（小米官方 Vela 模拟器）**：SDK 手动装 /home/z/my-project/scripts/emulator-setup.sh（官方 CDN vela-watch-5.0 镜像）；vvd-ctl.js create（density 只能枚举值 120..640，Band11 用 320 非 326）/deploy（PATH 需含 adb）/tap/swipe/shot/multtap/longpress；startApp 应答超时但实际启动成功；连点彩蛋用 multtap（跨进程间隔会重置计数）
- 基线固有测试失败（非回归）：api.test.js 门控用例（Node24）、GameProtocolDetectorTest localhost 探测（容器环境）

## 待办 / 已知事项
- 手机离线缓冲（快应用关闭→停 interconnect 发送、消息落盘、冷启动回放）——未做
- 无障碍服务易用性改进——未做
- 多账号管理（Stapxs-QQ-Lite-X 借鉴）——未做
- 历史翻页目前基于手机端本地缓存（200条/会话）；如需拉取更早需接 OneBot get_msg 历史（手机端扩展）
- 字库 8241 字（stapxs 版曾扩到 21197，本基线未带回，InputMethod.ux + dic 结构支持直接替换 dic.js）
- 真实手环固件的 emoji 字形覆盖未验证：v2.6.0 默认透传 emoji，若真机显示方框，手机端设置页「表情原生渲染」关闭即降级（无需重装）；
- aiot gRPC sendMouse 触摸注入时序敏感：App 未完全就绪时点击无效，等页面稳定后再点（vvd-ctl.js 已加重试节奏）

## 关键文件索引
- 手环：band-qq/src/pages/index/index.ux（列表/防抖/签名diff）、pages/chat/chat.ux（翻页/快捷回复/read_chat）、common/protocol.js（协议v2+convSignature）、common/store.js（未读/装饰/快捷回复）、common/api.js（interconnect 封装，勿动）
- 手机：android-sync/.../sync/MessageStore.kt（未读/Display预计算/翻页锚点）、sync/MessageBroker.kt（read_chat/before/quick_replies）、onebot/OneBotParser.kt（CQ剥离）、sync/InterconnectBridge.kt（onConnect 补推）、ui/SettingsScreen.kt（快捷回复编辑+WebUI）、WebUiActivity.kt

## v2.26.1（2026-10-05，vc69）—— 新 CI 胖包“解析失败”排查定案 + 打包硬化

**用户反馈**：v2.25.1（首个纯 CI 出包的 Release，v2.24.0 及此前均为本地构建）胖包安装报“解析包时出现问题”。

**排查全链（本地取证，非猜）**：下载 Release 双包与本地 v2.24.0 可用包做五维对比——①签名：apksigner verify 两包均 v2-only 有效、证书指纹同源（af8819e2…）；②ZIP：经典 32 位无 ZIP64、无 data descriptor、.so 全部 16KB 对齐；③清单/资源：AndroidManifest.xml 与 resources.arsc 逐字节 CRC 同构（仅版本串差异）；④dex：038 版本一致、badging minSdk 26/targetSdk 28/compileSdk 37 一致；⑤ELF：仅 libbusybox.so 不同——CI runner 预装 NDK 28.2，`:app:stripBundledReleaseDebugSymbols` 用 llvm-strip 重写了 osm0sis busybox（NDK r15c 构建）的段表（shstrtab 重排 + 段头改写，143 字节差异，体积零收益），ELF 结构校验两包均合法。**结论：CI 产物结构上挑不出毛病，“解析失败”最大嫌疑是手机端 80MB 大文件下载截断**（截断包安装必报同款错误）；llvm-strip 重写为唯一 CI 独有产物差异，一并根除。模拟器 adb install 实测路径受限于容器 2 核/无 KVM/3GB RAM 未能完成（TCG 拖死系统，已回滚）。

**修法**：①app/build.gradle.kts packaging.jniLibs.keepDebugSymbols 钉住引擎五件套（busybox/proot/bash/loader/talloc）+ libsudo 垫片——预构建静态二进制禁止 llvm-strip 碰；②signingConfigs.release 显式 enableV1/V2/V3Signing=true——个别魔改 ROM 只认 v1 JAR 签名的兼容兜底；③release.yml 新增 SHA256SUMS.txt 生成步骤 + Release 正文附“先校验再安装”指引（截断包哈希必不一致，不再无从对证）；④版本 bump 2.26.1/vc69，rpk manifest/about.ux 同步（v2.26.0 漏同步 rpk 版本，本次补齐 2.25.1→2.26.1）。

**教训**：CI runner 预装组件（NDK/SDK）会静默改变产物——strip/对齐等打包后处理必须显式钉住；发大体积 APK 必须附带哈希，否则“解析失败”类反馈无从定位是包坏还是下载坏；排查 APK 安装失败先做“与可用旧版逐字节 CRC diff”，排除法收圆最快。

## v2.26.0（2026-10-05，vc68）—— NapCat Worker SIGSEGV 崩溃循环终结（四级自愈阶梯 + 版本钉扎 + ELECTRON_RUN_AS_NODE）

**用户第七份日志（engine-2026-10-05.log，436 行）三连实锤**：①v2.25.0 清缓存后的全新启动仍 `[UtilityProcess] Worker退出码11(SIGSEGV)×3→主进程退出`——"脏缓存"定案不完整；②看门狗 07:22:06 宣布"5 分钟内自动清理并重启"，直到 07:28:32 用户手停都无动作（承诺未兑现）；③"反检测自动开启失败（node 不可用）"——v2.25.0 的 node 合并方案在 proot 永远走不通。

**上游考据（决定性）**：NapCat issue #1626 同症状（"更新NapCat后Worker进程退出，退出码 11/139 ×3"，多容器宿主全复现），结论两条——回滚版本即恢复；`NAPCAT_DISABLE_BYPASS=1` 可启动。源码考证（v4.18.28/v4.18.29 base.ts 逐字节相同）：`wrapper.node` 加载后**默认执行 `enableAllBypasses`**（bypass 反检测原生钩子，日志中的 "prepare write and writev hooks" 即其写钩子），该钩子在部分容器/proot 环境触发 Worker 段错误。版本时间线：4.18.29 发布于 10-04 20:30（北京时间），容器 NapCat zip 走 `releases/latest` 静默漂移拉到新版——上晚起进入崩溃循环。

**修法**（astrbot-startup.sh，+180 行）：
1. **四级自愈阶梯**：崩溃特征（.need_clean 或控制台尾部"主进程退出"）→ 计数 +1（.crash_count）；端口真正监听 → 清零。L1 清缓存残锁原样重启（v2.25.0 行为）→ L2 `NAPCAT_DISABLE_BYPASS=1` 启动（官方规避；.bypass_disabled 持久化，后续启动沿用，防每次冷启先崩一轮；恢复=删文件）→ L3 重装钉扎版 NapCat（配置备份恢复；自愈标记文件先摘到 TMPDIR 随后放回）→ L4 深度重置 $HOME/.config/QQ（需重新扫码）。阶梯动作在 start_napcat 内、stage 90 之前执行。
2. **版本钉扎**：`NAPCAT_SHELL_URL` 默认 `.../download/v4.18.28/NapCat.Shell.zip`（NAPCAT_SHELL_VERSION/URL 可覆盖）——releases/latest 漂移 = 不确定性问题源头。
3. **看门狗重启根修**：qq_main_alive() 逐 pid 读 /proc/PID/cmdline，仅"无 --type= 的主进程"算存活（残留 renderer/gpu 子进程不再误判"已在运行"）；崩溃特征判定**优先于**"已在运行"跳过；napcat_kill_stale 补 '/opt/QQ/qq' 模式连子进程一起清；巡检 300s→120s；看门狗循环内端口监听→计数清零。
4. **napcat_node_run 运行时回退链**：node → nodejs → `ELECTRON_RUN_AS_NODE=1 /opt/QQ/qq`（QQ deb 自带 Electron 退化纯 Node，无 GUI 依赖，proot 可用）——反检测 JSON 合并从此不依赖容器装没装 node。JS 落临时文件调用（脚本文件模式 argv[2]=首参；-e 模式 argv[1]=首参，JS 内 argv[2]||argv[1] 双兼容）。
5. **fd9 引擎管道**：主脚本启动时 `exec 9>&1` 捕获引擎读取管道（守卫：fd9 已开则不覆盖，看门狗重入安全）；napcat-console tap 改 `echo >&9`——自愈重启后 [NAPCAT] 日志继续进引擎日志/App 日志面板，不再断流进 watchdog.log。
6. **EngineManager 停止链**：TERM 优雅窗口 4s→8s（NT 大库落盘）+ 停止/杀残模式补 '/opt/QQ/qq'；AstrBotScreen 反检测说明行同步自愈语义。

**验证**：bash -n 过；功能测试 15/15 PASS（scripts/test-v2260-napcat.sh：反检测 JS 首写/幂等 skip rc=3/坏 JSON 重建/自定义键保留/node -e 兼容；heal_count 读取/脏值容错；fd9 关闭捕获/继承不覆盖；qq_main_alive 主进程识别/仅子进程判死）。坑位：MultiEdit 非原子（失败时已应用的前序编辑保留，重跑会产生重复块，须 rg 计数去重）；bash -c 传函数文本时 pgrep 会自匹配自身 cmdline（测试须落临时文件跑）。

**教训**：上游 `releases/latest` 是不确定性来源——基础设施类下载一律钉扎版本；"看门狗宣布会重启"≠"重启逻辑可达"——跳过分支（已在运行）必须排在崩溃处理之后；在无 node 的容器里依赖 node 的设计要用「环境里必然存在的东西」（QQ 的 Electron）做回退。

## v2.2.0（2026-09-10，versionCode 22）
1. **手环图标深色化**：背景改 #0D1015 近黑冷灰（AMOLED 手环融合），前景（蓝圆+白气泡+企鹅）不变；母版 icon_preview.png（用户提供，白底版已废弃），生成脚本 `scripts/darken-watch-icon.py`（flood fill 只换边缘连通白底，108×108 纯 RGB 全出血）
2. **Android 图标**：深色自适应图标（radial 渐变 #1A2129→#0D1015 底 + 前景 PNG 432px 图形占 66% 安全区，脚本 `scripts/gen-android-icon.py`）+ manifest android:icon/roundIcon（此前 v2.1.0 manifest 一直缺 icon 声明）
3. **液态玻璃悬浮栏**（miuix-blur 0.9.3）：BandQQApp 重构为 Box 叠层——内容层 `layerBackdrop()` 登记模糊源，底部 `drawBackdrop{ blur(4dp); colorControls(0.02,1.03,1.4) } + BloomStroke 边缘高光 + surfaceContainer 40% 叠色`；`isRuntimeShaderSupported()` false（Android <13）回退 `FloatingNavigationBar`
   - **坑**：miuix-blur AAR 声明 minSdk 33 → manifest 需 `<uses-sdk tools:overrideLibrary="top.yukonga.miuix.kmp.blur"/>`（放 application 标签无效，必须放 uses-sdk 节点）
   - **坑**：官方示例 LiquidGlassNavigationBar 的 `vibrancy()`/`lens()` 在 v0.9.3 不存在（超前 API），可用的是 `blur(radiusX,radiusY)`（px）、`colorControls(brightness,contrast,saturation)`（位置参数顺序）、Highlight/BloomStroke/LightSource 构造
   - 4 个 Screen 根 padding bottom=96dp 给悬浮栏留穿透空间
4. **构建环境脚本化**：`scripts/setup-buildenv.sh` 一键重建（JDK17+Gradle8.13+SDK+android-37 目录陷阱修复），容器重置后直接跑
- rpk 签名 sign/release/{private,certificate}.pem；APK 签名根目录 keystore.jks（SHA-256 af8819e2... 与 v1.x/v2.1.0 同源，覆盖安装兼容）

## v2.3.0（2026-09-10，versionCode 23）— 渲染修复 + KernelSU 同款主题设置
1. **渲染差异根因（两条铁律，勿再犯）**：
   - 裸 `MiuixTheme(content)` 走 colors 默认值重载 = **永远浅色**，不跟随系统深色、状态栏图标不联动 → 必须走 `ThemeController(colorSchemeMode, isDark)` + `MiuixTheme(controller)`（KernelSU manager 即此方案，其同为 miuix 0.9.3）
   - `rememberLayerBackdrop` 采集层**必须先 drawRect 垫不透明底色再 drawContent()**；背景画在登记层外会让玻璃栏模糊采样到透明像素 → 发黑/花屏
2. **主题系统**：ThemeMode 六模式（跟随系统/浅色/深色/动态取色×3）存 DataStore，设置页 ArrowPreference+WindowDialog 单选，MainActivity collectAsState 直驱 BandQQTheme 即时生效；Monet 模式：Android 13+ 系统色板 / 12+ 系统 Md3 角色 / <12 回退 miuix 蓝种子(0xFF3482FF)；LaunchedEffect 同步 isAppearanceLightStatus/NavigationBars
3. **液态玻璃配方换 KernelSU 同款**：`textureBlur(blurRadius=25f, BlurColors(blendColors=[surface 87%]))`，弃用自试 drawBackdrop+colorControls+BloomStroke；设置页 SwitchPreference 可关（关/低版本回退 FloatingNavigationBar）；miuix-blur 所有效果（含 textureBlur）门控 isRuntimeShaderSupported → 实际生效 Android 13+
4. **依赖**：build.gradle.kts 曾在 v2.x 重写时漏掉 miuix-preference-android:0.9.3（ArrowPreference/SwitchPreference 编译不过），已补回
5. 其他：enableEdgeToEdge（miuix SmallTopAppBar defaultWindowInsetsPadding=true 自处理状态栏 inset，已验证安全）+ values(-night) windowBackground 防深色闪白 + manifest 重复 xmlns:tools 修复

## v2.4.0（2026-09-10，versionCode 24）— KernelSU 液态玻璃栈整体移植 + 全屏主题页
1. **玻璃效果三要素（缺一不可）**：① backdrop 垫底色 drawRect(background) ② 内容必须能穿过底栏（滚动容器去 bottom 内边距 + 末尾 Spacer，v2.3.0 四屏 96dp 内边距导致玻璃"无物可糊"=实色条）③ 用 KernelSU 同款效果链 vibrancy+blur+lens
2. **移植清单**（源=KernelSU manager，包名映射 me.weishu.kernelsu.ui.component.*→com.example.bandqq.ui.*）：liquid/{Vibrancy,CombinedBackdrop,Lens,InnerShadow}.kt、animation/{DampedDragAnimation,InteractiveHighlight,DragGestureInspector}.kt（注意 InteractiveHighlight 的 android.graphics.RuntimeShader 是字段初始化，**只能在 isRuntimeShaderSupported()=true 分支组合**）、component/FloatingBottomBar.kt（含 LocalFloatingBottomBarTabScale）
3. **主题页不再用对话框**：0.9.3 WindowDialog 在本工程打不开（未深究，弃用）；ThemeScreen 全屏推入（AnimatedVisibility slideInVertically + BackHandler），TabRow 三档模式 + 动态取色开关，交互与 KernelSU ColorPaletteScreen 对齐
4. isInDarkTheme 必须标 @Composable（读 CompositionLocal）；BandQQTheme 以 LocalBandQQDarkTheme 提供明暗状态
5. 设置页保存路径改 ConfigHolder.config.copy(...)，避免把 themeMode/navGlass 重置为默认

## v2.4.1（2026-09-10，versionCode 26）— 颗粒底栏修复 + KSU 全量外观设置
1. **底栏缩成颗粒根因（必记）**：FloatingBottomBar 外层 `Box.width(IntrinsicSize.Min)` 下，weight(1f) 子项的 intrinsic 宽度=0 → 必须给 FloatingBottomBarItem 传 `Modifier.defaultMinSize(minWidth = 76.dp)`（KSU BottomBarMiuix.kt 同款），否则整条底栏塌缩成一个颗粒
2. **主界面骨架对齐 KSU MainActivity**：Scaffold(topBar/bottomBar) + HorizontalPager（底栏 animateScrollToPage 联动）+ 双 backdrop：blurBackdrop(rememberBlurBackdrop，13+ 且 enableBlur) 供顶栏/普通底栏 textureBlur（栏本体必须 Color.Transparent）；backdrop(rememberLayerBackdrop 垫 surface) 供悬浮玻璃，仅「悬浮+玻璃」都开才 layerBackdrop 注册
3. **外观设置 = KSU ColorPaletteScreenMiuix 全项**：预览卡片 / TabRow(跟随系统·浅色·深色) / Monet+关键色(0=品牌蓝,15 色 OverlayDropdownPreference) / 模糊(13+) / 悬浮底栏 → 液态玻璃(二级 AnimatedVisibility) / 导航栏角标(未读数挂聊天记录页签) / 预测性返回(14+，manifest enableOnBackInvokedCallback=true) / 界面缩放(Slider 0.8~1.1 keyPoints 磁吸 + ScaleDialog；实现=BandQQTheme 包 LocalDensity 缩放 density+fontScale)
4. **miuix 0.9.3 API 坑**：BadgedBox.badge 参数是 `BoxScope.() -> Unit`（receiver lambda），传声明变量须 `badge = { badge() }` 字面包装；MiuixIcons 图标全在 `icon.extended` 包作扩展属性，须逐个 import
5. **依赖坑**：material-icons-extended（3.5 万类）在 4G 内存容器 mergeDexRelease 必 OOM；miuix-icons extended 图标够用（Theme/Tune/CloudFill/HorizontalSplit/Scan/Pin/Sidebar/GridView），零额外依赖

## v2.4.2（2026-09-10，versionCode 27）— 顶栏遮挡/底栏偏下/预测性返回修复
1. **顶栏遮挡内容根因（KSU 架构差异，必记）**：外层 Scaffold 的 topBar + content 忽略 innerPadding → 页面从 y=0 起铺、被顶栏盖住。KSU 真实结构 = **外层 Scaffold 只放 bottomBar，每页自带 Scaffold(topBar=BlurredBar(SmallTopAppBar), contentWindowInsets=仅水平)**；innerPadding.top=顶栏总高（含状态栏），滚动容器首尾用 Spacer(innerPadding.calculateTopPadding()+16dp)/Spacer(bottomInnerPadding+12dp) 让内容从顶栏下穿过（顶栏玻璃有物可糊）。新增 ui/component/PageScaffold.kt 统一承载此模式
2. **backdrop 分层（KSU 同构）**：每页 PageScaffold 各自 rememberBlurBackdrop 供自己顶栏；外层 BandQQApp 的 blurBackdrop 注册在 pager Box 供普通底栏；悬浮玻璃 backdrop 不变。同一 backdrop 双层注册无害（KSU 亦如此）
3. **悬浮底栏偏下根因**：BottomBar 悬浮分支漏了 KSU BottomBarMiuix 的 padding——`WindowInsets.navigationBars + (inset>0 ? 8dp+inset : 28dp)` + start/end 28dp，外加容器 pointerInput{detectTapGestures{}} 吞掉栏外空白点击防穿透
4. **预测性返回无效果根因（KSU 配方，必记）**：manifest 静态 enableOnBackInvokedCallback=true 会让设置开关永远无效。正确做法=**manifest 不写，Application.onCreate 里 HiddenApiBypass.addHiddenApiExemptions("Landroid/content/pm/ApplicationInfo;->setEnableOnBackInvokedCallback") + 反射 setEnableOnBackInvokedCallback(appInfo, enable)**（API 34+；方法不在公开 SDK，编译期必须反射）；MainActivity.onCreate 幂等重应用以覆盖温启动；切换后需重启/重进生效
5. PaddingValues.calculateBottomPadding() 是成员函数，没有顶层 import（androidx.compose.foundation.layout.calculateBottomPadding 不存在，写了必炸）

## v2.4.3（2026-09-10，versionCode 28）— 主页排版重构 + 后台保活向导 + 动画速度/延迟可调
1. **主页排版**：4 个全宽堆叠按钮（观感散乱）→「快捷操作」Card 内 2×2 网格（启动/停止/检查手环/测试连接），配 SmallTitle 分区（运行状态/快捷操作/实时日志），对齐 miuix 卡片节奏
2. **动画系统统一（UiMotion.kt）**：LocalMotionSpeed(0.5~2.0x) + LocalMotionStagger(0~200ms) 两个 CompositionLocal，MainActivity 按 DataStore 注入；listItemReveal(entered,index) 统一四屏入场（时长=300/速度，逐项延迟=index×间隔/速度）；BandQQApp 推入页时长也跟随速度。ThemeScreen 新增「动画」区两个 ArrowPreference+内嵌 Slider（keyPoints 磁吸+Step 触感，同界面缩放模式）
3. **后台保活向导（KeepAliveScreen.kt）**：设置页入口推入；顶部「电池优化白名单」直接动作卡（ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS 系统弹窗全品牌通用+应用信息页，PowerManager.isIgnoringBatteryOptimizations 状态经 LifecycleEventObserver ON_RESUME 刷新）+ 品牌 chips（FlowRow，按 Build.MANUFACTURER 自动选中：小米/华为荣耀/OPPO一加/vivo/三星/通用）+ 分品牌分步教程卡（自启动/省电策略/锁屏清理内存/最近任务锁定等，老版本路径括号兜底）
4. **API 坑**：ArrowPreference.onClick 可空（纯 Slider 行传 null 不给点击反馈）；androidx.annotation.SuppressLint 在本工程 compile classpath 不可用（未直接依赖，别用）；miuix Button 无 text 具名参数（用内容 lambda { Text(...) }）

## v2.4.4（2026-09-10，versionCode 29）— 预测返回终修 + Monet 下拉修复 + 权限检测 + 动画补全 + 撤回提示
1. **预测性返回两处叠加根因（必记）**：① `ConfigManager.observePredictiveBack()` 默认值 false 与 AppConfig 的 true 不一致 → 启动时反射设置把开关关掉（Flow 默认值必须与 AppConfig 一致）；② 切开关只写配置不立即应用。KSU 真实做法 = 回调里**立即** `setEnableOnBackInvokedCallback(appInfo, on)` + `activity.recreate()`，照搬修复
2. **Monet 关键色「无法调节」根因（必记）**：miuix Scaffold 的 popup slot（下拉/OverlayDialog 浮层，最高 z-index）只覆盖自身 content；推入页（ThemeScreen/KeepAliveScreen）作为外层 Scaffold 的**兄弟节点**声明会整体盖住浮层 → 表现为下拉打不开。修复 = 推入页移入外层 Scaffold content slot 内的覆盖 Box（KSU navDisplay 同构），bottomBar 用 AnimatedVisibility 随推入收起（innerPadding 平滑过渡）。v2.4.0 的「WindowDialog 打不开」同根因
3. **入场动画时有时无根因**：HorizontalPager beyondViewportPageCount=1 预组合邻页 → 页内 `LaunchedEffect(Unit){entered=true}` 在屏外跑完。修复 = BandQQApp 传 `isActive = page==settledPage`，四屏 `LaunchedEffect(isActive){ if(isActive) entered=true }`
4. **后台保活向导升级**：权限检测四项卡（电池优化 PowerManager ✓/✗ + 通知 NotificationManagerCompat + 自启动「需手动」+ 前台服务 SyncService.isRunning @Volatile 标志），ON_RESUME + 2s 轻轮询刷新；品牌自启动管理页**直达 Intent 组件矩阵**（MIUI securitycenter / 华为 startupmgr 2 备选 / 荣耀 hihonor / OPPO coloros+oppo.safe / 一加 oneplus.security / vivo permissionmanager+iqoo.secure 2 备选 / 三星 lool），逐个 try-catch 失败降级应用详情页
5. **Stapxs 借鉴：消息撤回提示**（手环端零改动）：OneBotParser.parseRecallEvent（friend_recall/group_recall）+ OneBotListener.onRecall 默认空实现 + StoredMessage/OneBotMessage 加 messageId（持久化字段 message_id 可缺省）+ MessageStore.recallMessage（内容替换 RECALL_MARK，会话预览即时变）+ broker.onRecall 推会话帧；手环 convSignature diff 自动反映
6. **性能优化（代码重读发现）**：OneBotClient OkHttpClient 全局共享（联系人页刷新每次 new 会新建连接池/线程池）；OneBotStateBus 新增（OneBot 状态原先只能轮询/手动测试感知，useOneBotConnected 改订阅）；BandQQApp 未读角标事件驱动（MessageBus 主线程 post）+ 3s 轮询兜底；LogPanel 去 200 条 AnimatedVisibility 包裹改 key()（纯组合开销）；MessageStore.duplicates 上限 1000 防缓增；listItemReveal 级联 index 封顶 6（否则 200 联系人末项等 20s）
7. **排版**：SettingsScreen 重排（4 个裸 TextField/按钮收进卡片组、入口加 Theme/Lock/CloudFill 图标、全项 reveal、快捷回复说明文案补全）；HistoryScreen 清空按钮移列表末尾（破坏性操作防误触）；ThemeScreen 二级 AnimatedVisibility 补 expandVertically/shrinkVertically+fade（时长跟随动画速度，IntSize 类型）
8. 构建：SDK 重装踩 android-37 目录陷阱变体——**必须 cp 保留 android-37.0 原目录**（AGP 每次构建按库存清单校验会重装），只 mv 会循环重装报 Failed to find target android-37；aapt2 vc29/2.4.4，apksigner af8819e2 同源

## v2.4.5（2026-09-10，versionCode 30）— 崩溃修复+权限补全+动画延迟根治+图标白边根治+手环撤回/@我 实时特性
1. **Monet/预测返回点击崩溃（真因无法远程复现，三层防御）**：
   - v2.4.4 在 ThemeScreen 二级 AnimatedVisibility 加的自定义 expandVertically/shrinkVertically(tween<IntSize>) 是 Monet 点击路径唯一增量，**与 0.9.3 浮层首帧测量冲突嫌疑最大**，已回退 KSU 同款默认动画（勿再加自定义 spec 到含 OverlayDropdownPreference 的容器）；
   - **推入页开关从 rememberSaveable 改 remember**：预测返回开关 activity.recreate() 后若恢复推入态，重建首帧同帧跑「推入页进入动画+底栏退出动画+双 backdrop」，高危窗口；改后 recreate 干净回主页；
   - **新增 CrashGuard 全局崩溃落盘**（files/crash_log.txt，最多 5 段 64KB）+ 设置页「崩溃日志」查看/复制/清空（CrashLogScreen）——下次再崩用户直接给堆栈，不再猜。
2. **通知权限修复**：manifest 补 POST_NOTIFICATIONS；MainActivity 串行请求（⚠️ 同一 launcher 连续 launch 会取消前一请求，必须各用各的 launcher 链式回调）；保活向导通知行改为「申请通知权限」按钮（运行时弹窗，拒绝降级系统设置页）。
3. **无障碍检测项回归**：KeepAliveScreen 权限检测 5 项（电池/通知/无障碍干扰/自启动/前台服务）；无障碍行读 Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 计数，>0 显示 Manual 态提示检查清理类工具，直达 ACTION_ACCESSIBILITY_SETTINGS。
4. **动画「延迟已调最低仍延迟」根因**：四屏 isActive 用 pagerState.settledPage（滑动完全落定才触发入场）→ 改 **currentPage**（过半即播）。底栏选中态同步 currentPage。
5. **图标白边根治（scripts/rebuild-icon.py）**：母版蓝圆外圈设计用白色描边环（宽4px+抗锯齿带）不在 v2.1.0 洪水填充连通域内 → 圆形蒙版边缘露白。修复=蓝像素 bbox 定圆心/半径，内缩 4.5px 圆形羽化裁切，重合成 #0D1015 全出血底（蓝圆占 87% 半画布留深色安全环）；三端产物一次重生成（手环108/Android前景432/母版512）。
6. **手环端新功能（用户点名：功能要落在手环端；性能架构=手机预算/手环零计算）**：
   - **撤回实时灰显**：MessageStore.recallMessage 改返回被撤回消息 time；Broker 推「push_message+recall=1」帧，手环按 time **原位替换**内容+打 rc 标志（不新增消息/不动未读）；聊天页 .bubble-rc 灰底灰字小一号；历史帧同步带 rc。
   - **@我 高亮**：OneBotParser 检测 CQ:at qq=selfId/all → StoredMessage.atMe；历史帧/实时推送带 at=1，手环气泡金色高亮（.bubble-at）；会话帧带 cat=1（未读@我），手环列表绿「@我」角标，read_chat 后手机端清标志自动消失；convSignature 纳入 cat。
7. **SnowLuma 原生安卓调研定论（docs/snowluma-native-android-research.md）**：仍是 native 注入桌面 NTQQ（manual map .so + 闭源 .node），Android 无注入目标+seccomp 禁 ptrace+4.1万行 TS+闭源 addon → 进程内嵌/Kotlin 重写均不可行；**推荐落地=Termux+proot 跑本机**，新增 scripts/snowluma-termux.sh 一键脚本（Ubuntu+Xvfb+Node22+官方 arm64 发行包），设置页文案已更新。
8. 构建：vc30/2.4.5 两端同版本；apksigner af8819e2 同源；本机 Termux 指引进 App。

## v2.4.6（2026-09-10，versionCode 31）— 弹窗崩溃真根因（activity 1.12）+ 无障碍保活服务
1. **Monet/预测性返回点击崩溃真根因（必记，v2.4.5 三层防御方向猜错）**：用户实测再崩并提供堆栈——`IllegalStateException: No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner`，抛点=miuix 弹窗链 MiuixPopupHost → PopupEntry → NavigationBackHandler（androidx.navigationevent.compose）。真因 = `androidx.activity:activity-compose:1.9.1` 过老：navigationevent-compose 的 Local 默认 null + ViewTree 兜底，而 ViewTree owner 仅 activity 1.12+ 的 ComponentActivity 提供（implements NavigationEventDispatcherOwner + initializeViewTreeOwners 挂 decorView，`navigationEventDispatcher = onBackPressedDispatcher.eventDispatcher` 统一管线）。**凡点击后弹弹窗/下拉的选项必崩**（Monet 下拉、预测性返回 SuperDialog、保活确认等）——与选项自身处理器无关。修复 = activity-compose 1.12.4（POM 只要求 compose-runtime 1.7.0，与 BOM 2024.09.03/compose 1.7.2 无冲突，自动携带 navigationevent 1.0.2/lifecycle 2.9.4/core 1.16）+ MainActivity setContent 显式 `LocalNavigationEventDispatcherOwner provides this`（CompositionLocal 传播进 Popup/Dialog 子树，双保险）。验证手段：下载 google maven sources jar rg 确认，勿靠记忆猜版本行为
2. **无障碍权限「没有申请」真因**：v2.4.5 只有「无障碍干扰检查」行（查他人清理工具），应用自身未声明 AccessibilityService → 系统无障碍列表根本没有 BandQQ 可开。新增 sync/KeepAliveAccessibilityService（空实现 + canRetrieveWindowContent=false + typeWindowStateChanged 最小配置）+ manifest BIND_ACCESSIBILITY_SERVICE + res/xml/keep_alive_accessibility_config.xml + strings(label/desc)；KeepAliveScreen 新增「无障碍保活（本应用）」行：Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES 判定自身组件 + ON_RESUME 刷新 + 直达系统无障碍设置；「干扰检查」改 enabledForeignServiceCount 排除自身，开启后不误报
3. 构建：vc31/2.4.6；daemon 再次 OOM（GRADLE_OPTS 降堆 -Xmx1400m + kotlin daemon -Xmx900m 重试即过）；aapt2 验证 service 已打包、apksigner af8819e2 同源可覆盖装

## v2.4.7（2026-09-10，versionCode 32）— 预测开关 UX 修复（recreate 恢复推入页）+ 消息推送策略三件套
1. **预测性返回开关「点击被踢回上级菜单」修复**：开关必须 recreate 才能让系统 flag 生效（ViewRootImpl 仅在 window attach 时读 enableOnBackInvokedCallback，运行时改 ApplicationInfo 不重挂 window 无效，KSU 同样 recreate）——而推入态为防 v2.4.5 崩溃窗口故意 remember 不保存 → 重建后回主页。修复=新增 ui/RecreateCoordinator（进程级单例 reopenScreen+armed），recreate 前记录，BandQQApp LaunchedEffect(Unit) 消费并重新推入（armed 区分 recreate 与冷启动，冷启动不恢复）；推入动画自然重播，用户视角「刷新后仍在设置页」
2. **消息推送策略（用户要"日常刚需"，架构铁律=手机端预算/手环零感知）**：ConfigManager 新增 dndEnabled/dndStart/dndEnd/groupPushMode 四键；MessageBroker.onEvent 在 handleOneBotEvent **之后**仅拦截 bandSender——消息照常入库（历史/未读完整），手环不亮屏不震动，打开会话 get_history 仍能补看（勿扰语义）。群聊三档 0全部/1仅@我(含@全体)/2不推；勿扰窗口 parseHm+跨零点区间判断（start>end 即 23:00→07:00）
3. **一键测试推送**：MessageBroker.pushTestMessage 固定 targetId=bandqq-test 复用同一会话（手环无删除会话功能，避免列表污染），不入手机端历史库；SyncService companion @Volatile testPush 钩子（onCreate 注入/onDestroy 置空），设置页调用，未运行时提示先启动服务
4. 构建：vc32/2.4.7 一次通过；apksigner 同源；README 已补 v2.4.6/v2.4.7 日志

## v2.5.0（2026-09-11，versionCode 33 / RPK vc 31）— 预测开关落盘竞态根治 + 手环11弹性布局 + 解析器数组格式修复 + 新消息振动 + 测试推送模拟器
1. **预测性返回开关「点击刷新后又弹回关闭」根治（v2.4.7 遗留竞态）**：ThemeScreen 旧代码 `scope.launch{写盘}` 后**同步立刻** `recreate()`——重组作用域随旧 Activity 销毁被取消，写协程在首次调度前就被杀掉（DataStore edit 的 transform 未及入队），配置从未落盘；重建后 observePredictiveBack 读回 false → 开关弹回。修复=写入+反射+recreate 收进**同一个协程顺序执行**（先落盘再重建，runCatching 兜底）。教训：**「launch 后立即 recreate」= 必然竞态，凡是 recreate 前要持久化的状态必须 await 落盘**。
2. **手环 11 适配（212×520 PPI326，与 Band9 192×490 双屏通吃）**：四个页面（index/chat/settings/compose）定高容器改 flex:1 弹性伸展（原高度和恰好 490，designWidth=192 在 212 宽屏上放大 1.104 倍后溢出 21px 裁掉底栏）；compose 的 .page 顺带补 width/height:100%（原缺失，flex 无参照会塌陷）；InputMethod 键盘已有 screenWidth 运行时自适应无需改。**要点：Vela 页面主内容区永远用 flex:1，勿写定高**。
3. **解析器数组段格式修复（NapCat/SnowLuma 默认格式真实 bug）**：①atMe 检测旧逻辑只查 "[CQ:at," 字符串，数组格式 @我 永远失效 → 新增 OneBotParser.isAtMe 双格式兼容（数组段遍历 + CQ 字符串正则）；②degradeContent 数组段 `at` 从 [其他] 改为 @昵称/@全体成员、`reply` 显示 [回复]；③新增 degradeCqString：string 格式 message 原样透传的 CQ 码降级为 [图片]/[表情]/[语音]/[视频]/[文件]/[回复]/@… 可读标记。
4. **手环新消息振动提醒**：app.ux handleMessage push_message 分支，条件 is_self!==true && recall!==1 && visible!==false 时 `require('@system.vibrator').vibrate({mode:'short'})`（api.js 同款惰性 require 模式）；勿扰/群聊过滤手机端已完成，手环零额外判断。
5. **测试推送模拟器**：pushTestMessage(chatType, scenario) 构造真实 OneBot v11 事件 JSON（数组段格式）走 parseMessageEvent 完整管线（含 atMe/降级），private+group × text/at/image/face/reply/recall/voice/file/long 九场景；发送者昵称六人轮换（id 固定不堆会话），不入手机端历史库；撤回场景=先推文本再推同 time 撤回帧原位灰显；SyncService.testPush 钩子签名改 (String,String)->String 返回 toast 描述；SettingsScreen 模拟器 UI（会话类型两档 + 场景九宫格）。
6. 构建：APK vc33/2.5.0（apksigner 同源 af8819e2）+ RPK 2.5.0/vc31；容器重置后工具链重建（Temurin JDK17 tarball + commandlinetools + platforms;android-37.0 注意 SDK37 新版号是 android-37.0 非 android-37）；band-qq 单测 51/52（api.js 门控测试为存量失败，与本轮无关）。

## v2.7.0（2026-09-11，versionCode 35 / RPK vc 33）— 快应用自动拉起 + 联系人头像 + 清空同步根治 + 图标纯黑 + @动效 + 快捷回复即时同步

**用户置顶需求：互联自动拉起快应用**（快应用没打开时收消息不显示）：
- `sync/AutoLauncher.kt`：MessageBroker.onEvent 推送未拦截后触发 `scheduleIfEnabled()`；判定链 = 开关开 && !bandConnected（无 pong）&& 节点就绪；先发系统通知「将于 N 秒后自动打开」（运动健康通知同步镜像到手环，即用户说的"系统信息会被应用同步到手环"）→ 延迟后 `InterconnectBridge.launchWearAppNow()`（新增，只调 launchWearApp 不动鉴权/监听链，避免与重连循环并发冲突）；消息风暴去重（pendingJob 活跃即跳过）；onConnect/onBandConnected、点通知（MainActivity onNewIntent + EXTRA_CANCEL_AUTO_LAUNCH）、关开关三个路径都会取消；勿扰/群聊过滤命中不触发
- ConfigManager 新键：autoLaunchEnabled(默认 false)/autoLaunchDelaySec(默认 10)；设置页新专区「快应用自动拉起」（SwitchPreference + 5/10/15/30s 档位，后续 reveal index 顺延：WebUI=6 关于=7）

**其余改动**：
- 清空同步根治（手环清了又重新同步回来）：根因 = 手环 doClear 的 `api.send` 未 await 且失败静默，清空请求丢失后手机端记录全部推回。修复：手环 await+3 次重试+成功后回拉确认；手机端 clear_all_history 处理后回推 buildConversationFrame 作为 ack；HistoryScreen 清空按钮加二次确认弹窗（androidx Dialog）；两端确认文案明示"会请求对端同步清除"；clearAllHistory 补清 atUnreadByTarget
- 联系人头像：新 `ui/component/AvatarCircle.kt`（MessageStore.Display.hueOf/avatarChar 与手环同源），ContactScreen/HistoryScreen 行首 40dp 圆，Row CenterVertically 修复"偏下"观感
- 手环图标纯黑：底色 #0D1015→#000000（scripts/recolor-icon-black.py，通道级重着色含过渡带压暗；icon_preview.png+手环 icon.png，Android fg 透明底不动）
- @动效：chat.ux .bubble-at 金边+keyframes atPulse 呼吸（border-color/背景亮度，不触发布局）；index.ux .item-at 同款；RPK 编译产物已验证 keyframes 生成（chat.js 内 keyframes 描述符）
- 快捷回复即时同步：SyncService.pushQuickRepliesNow hook；设置页快捷回复卡新按钮「保存并同步到手环（即时生效）」+ 主保存也附带；手环端 app.ux quick_replies→emit→chat.ux 监听链路早已就绪，此前缺的只是手机端主动推
- 存量测试失败（非本批引入，基线同样失败）：RPK 端 api.test.js 1 例、APK 端 GameProtocolDetectorTest 1 例（容器 localhost 探测环境限制）；受影响模块 sync/config/parser 测试全部通过

## v2.6.0（2026-09-11，versionCode 34 / RPK vc 32）— 预测返回 KSU 原版重做 + PredictiveBackHandler 真实手势动画 + 测试消息入库 + 手环表情支持 + Vela 虚拟机双屏验收
1. **预测性返回终修（克隆 KernelSU 源码逐行实证，此前 v2.4.2~v2.5.0 四轮都没修对）**：KSU 真实做法 = ①manifest 不写 enableOnBackInvokedCallback；②仅 Application.onCreate（API34+）HiddenApiBypass 豁免 + 反射 `ApplicationInfo.setEnableOnBackInvokedCallback(持久化值)`；③**开关处理器只写偏好+更新 UI，不反射不 recreate（下次启动生效）**。我方此前的「toggle→立即反射→recreate」路线就是闪烁/踢回上级菜单/开关回弹三症状的总根源。现在 ThemeScreen 开关与 KSU ColorPaletteScreen 完全同构（摘要注明重启生效），BandQQApplication/MainActivity 原有逻辑保留。
2. **预测手势从无效果变真实可见**：flag 开了没动画的根因=自定义导航栈没有消费进度事件。BandQQApp 新增无条件 `PredictiveBackHandler(enabled=overlayOpen)`（activity-compose 1.12.4 源码实证：预测路径走 channel 流；**离散返回也会启动一次性 session 立即 close，即 collect 完整走完 → 一个 handler 通吃两种模式**，替代原三个 BackHandler）；手势期间顶层推入页 graphicsLayer 位移30%宽+缩放0.92+淡出，提交关闭/取消回弹（catch CancellationException 重抛）。推入页开关回归 rememberSaveable（不再 recreate 就无恢复崩溃风险），RecreateCoordinator 删除。
3. **「手环消息一会就删除」双保险根治**：①手机端 pushTestMessage 入库（v2.5.0 刻意不入库是误判——手环会话/历史以手机端为事实源，不入库=下次同步必被清；用户明确要求进聊天记录）；②band-qq store.js setVisibleContacts 旧逻辑无条件删除不在 visibleIds 的会话+消息缓存 → 改为「有本地消息的临时会话保留」，无消息骨架才清。
4. **表情支持（face→emoji 映射 + 可降级开关）**：FACE_EMOJI 80 项映射进 OneBotParser（id→名称以 NapCat napcat-core/external/face_config.json 官方数据为准：0=惊讶 13=呲牙 14=微笑，勿信「0=微笑」旧民俗表）；degradeCqString 先 CQ_FACE 后通用 CQ；文本 emoji 不再强制 [表情]。**ConfigManager.emojiNative 开关**（默认 true）控制透传/降级——设置页「消息推送策略」卡内「表情原生渲染」。RPK protocol.js 旧端兼容路径同款小映射。
5. **Vela 虚拟机验收体系（重大基建）**：aiot-toolkit 自带 VVD 模拟器（QEMU arm，@aiot-toolkit/emulator）。配方：`~/.vela/sdk` 手动装 SDK（emulator/qa/skins/modem + **vela-watch-5.0 镜像 220MB 唯一支持自定义分辨率**；vela-miwear-watch-5.0 383MB 固定尺寸）；hw.lcd.density 只接受枚举值（326 非法→320，否则 qemu 退出 138）；每台独立 grpcPort；`-no-window` 无头 + gRPC getScreenshot 截屏 + sendMouse 触摸（时序敏感，App 就绪后点击才有效）；adb 走 @miwt/adb（需 platform-tools 在 PATH），pushRpk→`pm install`→`am start`。**脚本 /home/z/my-project/scripts/vvd-ctl.js + emulator-setup.sh**。实测发现并修复：首页空态文案 212 折行孤字（字号 18+lines:1）、设置页清空项双折行（缩 4 字+flex-shrink:0）。键盘组件 480px 系 scroll-x 横滑设计非溢出。**Vela watch5.0 镜像字体无 emoji 字形（含 2600-27BF/1F000-1FAFF 全 tofu），GBK 符号（→←★☆○●◎§№）与 ASCII 颜文字正常**——真实手环固件未验证，故 emoji 透传做成可降级开关。
6. 构建：APK vc34/2.6.0 + RPK 2.6.0/vc32 同源验签；android 单测除存量 GameProtocolDetectorTest 失败外全绿；band-qq 单测 53/54（存量 api 门控失败）。

## v2.8.0（2026-09-11，versionCode 36 / RPK vc 34）— DevTools 模拟协议端 APK + 拉起后手环震动 + 双端设置互通 + 双端关于页

**BandQQ DevTools 开发者测试工具 APK（全新 module :devtools，独立安装）**：
- 用户没有 OneBot 服务器 → 手写零依赖模拟协议端：`WsServer.kt`（RFC6455 手写帧编解码：Accept=b64(sha1(key+GUID))、客户端帧掩码 XOR、126/127 扩展长度、continuation 分片、ping/pong/close；~250 行）+ `HttpApiServer.kt`（ServerSocket 极简 HTTP：POST 路由 /send_private_msg /send_group_msg → retcode 0 + 可选自动回推对方消息（闭环：「手环回复→协议端收到→对方再回复」WS 下发 message 事件，手环直接看到对话流）；/get_friend_list /get_group_list → 模拟联系人，APP 连接后自动拉取出现在联系人页）；UI 纯代码构建（无 XML 布局），org.json 内置 JSON
- MsgBuilder：与 APP 端 pushTestMessage 同源的 OneBot v11 数组段载荷（self_id=10000、time=Unix秒、message_id=dev_*），九场景 + 自定义 + recallFlow（先文本后 notice.group_recall/friend_recall）
- 使用法：启动服务器（端口基号默认 3000→WS=base+1）→ APP 保持默认地址 127.0.0.1:3001/3000 → 启动同步服务自动连上 → 点按钮发消息到手环；同机 loopback 共享网络栈，双 APK 直连无障碍
- ⚠️ AGP 需要 platforms/android-37（非 android-37.0）：sdkmanager 装的是 37.0 且 source.properties ApiLevel=37.0，AGP 找 hash string 'android-37' —— 必须 cp -r android-37.0 android-37 并 sed 修 ApiLevel=37（v2.4.4 起的目录陷阱新变体，三份目录共存：37.0/.bak/37）

**自动拉起后手环震动（band_alert 帧）**：
- AutoLauncher：launchWearAppNow 后若 autoLaunchVibrate>0 置位 pendingVibrate(AtomicBoolean)+时间戳；onBandConnected（快应用 pong 连上）消费——60s 窗口防拉起失败残留误震；launchAlertHook 由 SyncService.initAlertHook 注入（bandAlert(broker) 发 {"type":"band_alert","mode":1|2}）
- RPK app.ux：case 'band_alert' → mode 2=长震 / mode 1=短震+260ms 后再短震（与消息单次短震区分）
- ConfigManager.autoLaunchVibrate: Int = 1（0不震/1短震×2/2长震）；设置页自动拉起专区三档按钮；手动打开快应用不经过该路径天然不震

**双端设置互通（settings_state/settings_update 帧）**：
- 字段：msg_vibrate（新消息手环振动，新 ConfigManager 键 bandMsgVibrate 默认 true）+ emoji_native（表情渲染，复用现有键）；手机端为权威源
- APP→手环：MessageBroker.buildSettingsStateFrame/pushSettingsState；下发时机=InterconnectBridge.onConnect、SyncService.pushSettingsNow 钩子（设置页开关变化即调）、手环回传有变化后的确认
- 手环→APP：settings.ux toggleVibrate/toggleEmoji → protocol.updateSettings（只带变化字段）→ MessageBroker.onBandFrame "settings_update" → settingsWriter（SyncService 注入 ConfigManager.applyBandSettings——值变才落盘返回 true）→ 回推 settings_state 确认；无变化不回推防同步风暴
- 手环端：store.js setSettings/getSettings（storage key band_settings 持久化+启动加载+Object.assign 合并默认）；app.ux case settings_state → setSettings+emit('settings')；onCreate/onOpen pullAll 补发 get_settings
- push_message 震动判定加 `store.getSettings().msg_vibrate !== false`；settings.ux 新增「消息震动/表情渲染」两行（点行切换 + toast），on('settings') 实时刷新文案

**双端关于页**：
- APP：ui/AboutScreen.kt（推入页同构 CrashLogScreen：Scaffold+BlurredBar+SmallTopAppBar；图标+版本 BuildConfig.VERSION_NAME（app buildFeatures 开 buildConfig=true，AGP8 默认关）+ 作者一秋/QQ群 885186458 点击复制/仓库 Gsjsjzhznsz/BandQQ 点击开浏览器/项目简介/双按钮）；BandQQApp showAbout rememberSaveable 推入 + PredictiveBackHandler 优先级链插入
- RPK：pages/about/about.ux（hero 区图标/版本 + 简介/作者/联系/仓库卡片；192px body 双屏通吃）+ manifest router 注册 + settings.ux「关于」行 goAbout

**交付**：bandqq-sync-release-2.8.0.apk（12.2MB vc36）+ bandqq-devtools-release-1.0.0.apk（4.7MB vc1 同签名）+ bandqq-watch-release-2.8.0.rpk（256KB vc34，包内验证 about 页/band_alert/settings 协议齐全）；RPK node 单测 53/54+49/49（protocol/store 全过，api.test.js 1 例存量失败）；GameProtocolDetectorTest 1 例存量失败（与 v2.7.0 基线一致，容器环境限制）

## v2.8.1（2026-09-11，versionCode 37 / RPK vc 35）— DevTools 联调反馈修复：WS 断连三层加固 + 双端关于页修复 + 设置页宽松化 + DevTools 身份配置

**DevTools 发送消息全链路不通（核心，用户实测：每发一条 WS 就断开重连）**：
- 根因一（主）：OkHttp RealWebSocket.loopReader 是 catch-all —— listener.onMessage/onOpen 回调内任何异常都被当作 WebSocket failure → 立即断连。而 BandQQ 处理链（parse→入库→bandSender→互联 SDK→AutoLauncher）任一环节（尤其 xms-wearable sendMessage 同步抛、通知/节点失效）都可能抛。三层加固：① OneBotClient.onMessage/onOpen/onClosed 全链 try-catch(Throwable)（异常写 LogBus ERROR「WS onMessage exception (connection kept)」，设置页日志可查）② MessageBroker.onEvent/onState/onRecall 全链兜底（AutoLauncher.scheduleIfEnabled 单独再包）③ InterconnectBridge.sendToBand 的 messageApi.sendMessage try-catch
- 根因二：OneBotClient.reconnect() 多重连循环竞态 —— start() 每次 ACTION_START 都新建 scope.launch 循环且旧 Job 不取消 → 两个循环在 !connected 时并发 connectOnce → 双 WS 连接（DevTools 日志「当前 2 个」）+ ws 引用互相覆盖泄漏。修复：reconnect() 先 reconnectJob?.cancel()；connectOnce() 先 runCatching{ ws?.close(1000,"reconnect") } 清旧连接
- 诊断方法论：用户贴的 DevTools 日志模式「已发送→客户端断开→5s 重连」+「连接后立即断开」+「当前 2 个」三个特征直接锁定断连根因与双连接竞态，无需真机堆栈

**手机端关于页闪退**：AboutScreen 用 painterResource(R.mipmap.ic_launcher) —— API26+ ic_launcher 是 adaptive-icon XML，Compose painterResource 只支持 Vector/Bitmap drawable → AdaptiveIconDrawable 抛 IllegalStateException（唯一用 mipmap 图标的推入页所以只有它崩）。修复：ContextCompat.getDrawable + core-ktx toBitmap()（能绘制 AdaptiveIconDrawable）→ asImageBitmap；remember 缓存 + runCatching 空安全降级

**手环关于页点不进去**：settings.ux goAbout 用 uri:'/pages/about/about'（无效路由，router.push 静默失败）—— manifest 注册 path='/about'。修复=uri:'/about'（对照工作正常的 /settings /chat /compose：**Vela router.push 的 uri 用 manifest path 不是页面文件路径**）

**手环设置页宽松化**：六项挤一坨 → 新增「连接/偏好/更多」节标题（.section 15px 透明度分层）；行高 52→58、边距 8→10、行距 4→6、尾部 16px 留白

**DevTools「我的身份」配置**：MainActivity 新增 selfQq/selfNick 输入框（getPreferences 持久化，onPause 保存 onRestore 恢复）；MsgBuilder.messageEvent/scenarioSegments 参数化 selfId（@我 场景 at 段 qq=selfId 与事件 self_id 一致）；HttpApiServer 新增 get_login_info 标准动作（selfInfo lambda 注入返回配置身份）——@判定链（at 段==self_id==get_login_info）三处对齐，用户可配真实 QQ 号验证

**其他**：AboutScreen 仓库行改完整 URL（https://github.com/Gsjsjzhznsz/BandQQ）clickable 开浏览器；about.ux GitHub 行点击复制链接（@system.clipboard feature 新注册）；版本 app vc37/2.8.1 + devtools vc2/1.1.0 + RPK 2.8.1/vc35

**交付**：bandqq-sync-release-2.8.1.apk（12.2MB vc37）+ bandqq-devtools-release-1.1.0.apk（4.7MB vc2 同签名）+ bandqq-watch-release-2.8.1.rpk（257KB vc35，包内验证 settings 分组/about copyRepo/uri:'/about'/clipboard feature 全入包）；node 单测 53 过 1 挂（api.test.js 存量基线一致）

**构建环境备忘**：sdkmanager 平台包名是 platforms;android-37.0（android-37 不存在）；cp -r android-37.0 android-37 后须 sed 改 package.xml 的 android-37.0→android-37 与 source.properties 的 ApiLevel=37.0→37；系统 Java21 是 JRE 无 javac（Toolchain does not provide JAVA_COMPILER），必须 Temurin JDK17（/home/z/tools/jdk-17.0.20.1+1）；4GB 内存容器 gradle 须 --no-daemon + GRADLE_OPTS -Xmx1100m/-Xmx700m 否则 daemon 被杀

## v2.8.2 + DevTools 1.2.0（2026-09-12，app versionCode 38 / DevTools vc 3 / RPK 无改动）— 版本互认断连定位 + DevTools 协议补全 + HTTP 中文 body 字节读修复

**用户二次实测仍断连的定位结论（重要方法论）**：新日志「已发送→客户端断开→1~5s 重连」的间隔节奏（连接 5s 内失败=下次 5s 档，>5s 失败=1s 档）与 OneBotClient.reconnect 循环（!connected 分支 delay(5000) / connected 分支 delay(1000)）完全吻合 → 断连源于 APP 端 WS failure。v2.8.1 的 try-catch 加固在 APP 2.8.1 内，旧版 2.8.0 APP 收事件异常仍断连 → 高度怀疑用户未更新同步器 APK。为终结猜谜加了**版本互认**：
- APP 2.8.2：OneBotClient.onOpen 后立即经 WS 发 `{"action":"get_version_info","params":{},"echo":"bandqq-${BuildConfig.VERSION_NAME}"}`（Stapxs 同款标准行为，真实协议端正常应答无副作用）
- DevTools 1.2.0：WS 收到动作帧 → ActionRouter 应答 + echo 回带 + 日志「WS 动作 get_version_info（BandQQ APP x.y.z）→ 已应答」——用户日志无此行=旧版 APP 一眼识破
- 下发事件后 4s 内断开自动追加升级提示；同步器启动日志记录版本 v2.8.2(vc38)

**DevTools 1.2.0 协议补全**：
- WsServer 重写：handshake 后下发 lifecycle connect 元事件；全局 30s 心跳 meta_event（ScheduleAtFixedRate，clients 空跳过）；客户端动作帧解析应答（此前只打日志从不回包——标准 OneBot 客户端连上后等 echo 应答）；断连归因（close 帧解析 code/reason 如测试连接的 probe done / TCP EOF / 60s 读空闲收割——客户端 20s ping 未达=对端已死被冻结）；broadcast 逐条上报写入失败（含 deadReason）
- HttpApiServer 重写：**修复中文 body 读取 bug**——此前 BufferedReader 按字符数读 Content-Length（字节数），中文请求体（手环快捷回复几乎全中文）必然少读阻塞 8s 超时不回包 → 手环回复 APP 端 send failed 且 DevTools 无日志（v2.8.0~1.1.0 手环回复收不到的隐藏元凶！）。改字节级：读头到 \r\n\r\n（StringBuilder 尾 4 字节判定，StringBuilder 无 endsWith）→ 读满 Content-Length 字节 → UTF-8 解码
- 新增 ActionRouter：HTTP 与 WS 共用一套动作应答（get_version_info 真实数据/get_login_info/get_status/联系人/send_*/通用成功）；注意 selfId() 直取方法不能走 handle("get_login_info") 否则每 30s 心跳刷动作日志

**交付**：bandqq-sync-release-2.8.2.apk（12.2MB vc38）+ bandqq-devtools-release-1.2.0.apk（4.7MB vc3 同签名）；RPK 保持 2.8.1 不变；app 单测 94 过 1 挂（GameProtocolDetectorTest 存量基线一致）；测试依赖 junit/mockwebserver 需在线解析（容器离线缓存无）

## DevTools 1.2.1（2026-09-12，DevTools vc 4 / app 与 RPK 无改动）— NetworkOnMainThreadException：三轮联调的真正根因

**根因（1.2.0 的断连归因日志立功）**：用户第三次实测日志出现 `下发失败：NetworkOnMainThreadException: null`——WsServer.broadcast() 在 **UI 主线程**被调用（MainActivity.sendScenario/sendCustom 点击路径），sendRaw→output.write() 主线程网络 IO 必抛异常 → catch 后 closed=true → removeClient **服务端自己关了连接**。「发一条断一条/不发长连/APP 2.8.2 动作应答全正常」三点全部由此解释；此前怀疑的 APP 解析异常、心跳超时、版本问题全部排除。
- **修复**：WsServer 新增 `ws-sender` 单线程 executor，broadcast() 改异步投递（fire-and-forget，任意线程安全）；失败经 onEvent 逐条上报并移除死连接；stop() 时 sender.shutdownNow()。心跳/动作应答/握手 lifecycle 均在后台线程本就正常。
- **教训**：手写 socket 服务器，接收路径天然在 accept 线程，但「外部主动发送」极易从 UI 线程直达 write()——Android 主线程网络异常只在真机现形（编译期无感知），联调日志要打全异常类名才能一眼定位。
- **交付**：bandqq-devtools-release-1.2.1.apk（4.7MB vc4 同签名 af8819e2 覆盖安装）；同步器 2.8.2 与 RPK 2.8.1 不变；单测 49/49 过；README 更新三层断连排查史


## v2.9.1-app（2026-09-12）同步器文件日志系统（免 root 排查）

**用户反馈**：社区有人「小米运动健康的设备授权管理里找不到 BandQQ」，无法判断机型适配还是权限问题；要求日志系统写到应用自身数据目录（免 root）而非 /data/data。

**实现**（app vc40 / 2.9.1）：
1. FileLogger（sync/FileLogger.kt）：挂 LogBus.sink（LogBus 新增 sink 字段），全量日志单线程异步落盘 `Android/data/com.example.bandqq/files/logs/bandqq-YYYY-MM-DD.log`（getExternalFilesDir，外部存储不可用回退 filesDir）；按天分文件 + 保留 7 天 + 8MB 滚动 .old.log + 会话头（版本分隔）
2. 环境自检 logEnvironment（Application.onCreate 每次进程启动）：机型/系统/HyperOS(SystemProperties 反射 ro.mi.os.version.name)/运动健康 com.xiaomi.wearable 版本/蓝牙+通知权限/电池优化白名单/无障碍保活/授权条目生成时机提示
3. InterconnectBridge：checkPermissions 结果逐项 granted[i]=x 落盘 + requestPermission 成功回调补日志（此前只有失败回调，用户确认授权无记录）；connect 记录 node id
4. CrashGuard 崩溃同步进当天日志；LogPanel 加「导出」chip（FileLogger.shareZip：全部日志+crash_log.txt 打包 cacheDir → FileProvider ACTION_SEND）
5. Manifest 加 FileProvider(com.example.bandqq.fileprovider) + res/xml/file_paths.xml（external-files-path logs/ + files-path crash + cache-path）

**授权问题结论**（已写 README 排查指引）：设备授权管理条目只在 APP 发起 DEVICE_MANAGER requestPermission 后生成——常见原因排序：①手环机型不支持（8 及更早/Redmi 系）②运动健康未连手环或版本过旧 ③装了 APK 但没开服务没触发授权请求。互联链路全步日志落盘后可远程精确定位。

**教训**：requestPermission 只挂 FailureListener 会漏记用户实际确认结果；LogBus.sink 模式让内存日志升级文件日志零侵入（单测 sink=null 天然 no-op）。

## v2.9.1（2026-09-12）主页面列表铺满整屏 + 演示模式五会话

**用户发现**：RPK 主页面只能显示半页联系人（v2.9.0 虚拟机实拍同屏暴露，当时未察觉）。

1. **根因（像素级实证）**：Vela 引擎 scroll 组件的 `background-color` **只绘制内容高度区域、不铺满 flex:1 拉伸后的视口**——行亮度扫描证明：卡片内容带下方至状态栏之间纯黑（0,0,0），列表背景 rgba(0,122,255,0.08) 只存在于内容区；scroll 占位本身正常（状态栏仍被推到底）。数据少时下半屏全黑，观感=半页。
2. **修复（index.ux）**：背景移至外层普通 div `.list-wrap`（flex:1 撑满中区+画背景），scroll 改 `position:absolute` 铺满 wrap（引擎无关的确定高度，不再依赖引擎对 scroll-flex 的实现差异）；空态同步改 absolute 覆盖不参与 flex。绝对定位父级需 `position:relative`（已加）。
3. **演示模式扩充**：injectDemo 2 → 5 会话（BandQQ 体验群@我/家人群拍一拍/项目同步群免打扰灰点/马化腾私聊拍一拍+免打扰/张三无未读），铺满 212×520 整页；**@我 与拍一拍消息置于最新**——VVD 聊天页嵌套 scroll 无法 gRPC 滚动（见坑位），打开即滚底可见特效。测试更新：单测断言 5 会话+双免打扰+家人群 poke，56/56 过。
4. **VVD 新坑位（重要）**：① **聊天页嵌套 scroll（msg-area）gRPC sendMouse 拖动完全无效**（字节级验证三连拍 identical），设置页页级 scroll 可滚——引擎仅页级 scroll 响应合成拖动；② swipe 按下点落在快捷回复区（scroll-x）会被横滑拦截，无效；③ tap 后自动滚底窗口仅 ~30ms（burst 15 张 10ms 级连拍验证），抢拍不可行——**从数据侧解决：让目标消息成为最新**；④ `adb shell input` 在 Vela 上挂死（勿用）；⑤ 重部署流程：pkill qemu → adb kill-server → deploy（install 成功 + startApp FATAL Timeout 属正常）→ storage 保留旧演示数据，需重走彩蛋覆盖。
5. 构建：RPK 2.9.1/vc39（包内验证 list-wrap/absolute×6/五会话数据）；app/DevTools 无改动不重发。截图 5 张重拍（五会话列表/@我金泡/家人群拍一拍/私聊拍一拍/关于页 v2.9.1）。

## v2.9.0（2026-09-12）拍一拍全链路 + 会话免打扰 + 拉起收敛 + VVD 虚拟机实拍

**用户业务规则澄清（关键认知修正）**：QQ **私聊没有 @，只有拍一拍；群聊才有 @** —— DevTools 1.2.x 的「私聊·@我」按钮本身就是伪命题，用户多轮实测私聊场景永远不可能出 @ 动效（部分解释了四轮「还是没动效」）。@ 段协议层只有 QQ 号，显示名称需手机端解析（联系人缓存映射），判定也在手机端完成（架构铁律不变）。

1. **拍一拍（poke）全链路**：OneBotParser.parsePokeEvent（notice_type=notify & sub_type=poke，兼容 group_poke/friend_poke；pokeMe=target_id==self_id，无 target 的私聊形态默认拍我）；MessageBroker.onPoke 只处理拍我（拍别人不提醒），联系人缓存解析昵称 → content「XX 拍了拍你」入库（StoredMessage.poke 新字段，持久化/历史帧 poke:1）→ buildPokeFrame（push_message+poke:1）→ AutoLauncher.scheduleIfEnabled(important=true)。手环端 store upsert poke=1/true 双形态，chat.ux 居中紫色特效胶囊（pokeShake 晃动×3，transform 动画不重排），app.ux poke 与 at 同级长震。
2. **会话免打扰**：手环 index.ux 长按会话 → 底部弹层菜单「消息免打扰」开关（store.toggleMute，settings.mute_list 逗号分隔全量字符串）→ settings_update 帧上报（ConfigManager.applyBandSettings 第三参 muteList 落盘 mutedChats）→ 手机端回推 settings_state（含 mute_list）双端一致。效果：未读徽标红点变灰（.badge-muted #6b6b6b）、该会话消息手环不震（app.ux push_message 查 isMuted）、AutoLauncher 拦截不拉起。convSignature 加入 muted 位防签名误跳过。
3. **自动拉起范围收敛**：autoLaunchScope 默认 1=仅 @我/拍一拍我（important），0=所有消息；免打扰会话永不拉起。SettingsScreen 拉起专区新增「拉起范围」两档。
4. **@名称解析**：MessageBroker.handleOneBotEvent 用 store.contactName 把「@10086」替换为「@昵称」（AT_QQ_REF 正则 5 位以上数字才尝试，命中才替换），入库+下发同源。
5. **DevTools 1.3.0**：pokeEvent 构造器；场景重排（拍一拍前置、@我·仅群聊）；私聊误选 @我 自动改发拍一拍并提示；拍一拍自检日志。版本 vc6/1.3.0。
6. **演示模式彩蛋**：about.ux 连点版本区 7 次 → store.injectDemo() 注入两个演示会话（群：@我+拍一拍+普通；私聊：文本+拍一拍；马化腾会话默认免打扰展示灰点）。用途：Vela 虚拟机无互联环境实拍界面。**教训：Vela text 小点击区 onclick 可能不响应（gRPC sendMouse down→up 不触发），点击区要挂到大容器（hero div）**。
7. **小米官方 Vela 虚拟机（VVD）实拍流程**：SDK 手动装（/home/z/my-project/scripts/emulator-setup.sh，vela-ide.cnbj3-fusion.mi-fs.com 官方 CDN，vela-watch-5.0 镜像 20250716）→ vvd-ctl.js（create Band11 212 520 320；**density 只能用枚举值 120..640，326 报 Bad LCD density**；deploy 后台跑需 PATH 含 /home/z/android-sdk/platform-tools/adb；startApp 应答超时属正常，app 实际已启动）→ gRPC tap/swipe/longpress/multtap/shot（脚本已扩展）。am start/pm install 偶发阻塞 adb shell，重启模拟器最省事。截图 = getScreenshot PNG 原生 212×520。
8. 构建：RPK 2.9.0/vc38（包内验证 pokeShake/badge-muted/toggleMute/onVerTap/injectDemo/pokeMe 全入包）+ app vc39/2.9.0 + devtools vc6/1.3.0；手环单测 54/54（新增 poke/免打扰/演示 3 例）+ ux 语法 12 文件全过 + APP 侧 poke 单测 5 例。


## 29. 分支制重构 + AetherZeng 键盘全量换装（2025-09-12）

**分支模型**（用户指令：恢复 2.9.1 仅手环版，设备各自分支另出 RPK 不合一）：
- `main` = 手环 9/10/11 专用线（2.9.1 基线 + fork Capsule 键盘 = 2.9.2/vc46）；`redmi-watch` = RW5/6（基于 2.11.0 观感成果拆出，2.11.0-rw/vc62）；`xiaomi-watch-s` = S3/S4/S5（2.11.0-s/vc61）；`unified-2.11.0` + tag `v2.11.0-unified` = 合一版归档。

**fork 键盘换装配方**（AetherZeng1145/Vela-Input-Method-Revise）：
- 分支对应：Capsule-For-Xiaomi-Band(192×490)→main；Cube-For-Redmi-Watch(432×514)→redmi-watch；QWERTY(466×466 圆屏)→xiaomi-watch-s；组件按「designWidth=物理宽」设计，宿主 designWidth 恒 192 → **px 常量 ×(192/物理宽) 整数化**（scripts/rescale_ux_px.py，跳过 script 段）。
- props 仅 hide/maxlength/vibratemode（无 keyboardtype/screentype）；事件 complete{content}/delete/keyDown 与 NEORUAA 同 API；词库内置 dic.js 不需 system.file feature；根容器 absolute 贴底（宿主用 relative kb-wrap 占位键盘高度：Capsule 305 / Cube 123 / QWERTY 133 design px）。

**VVD 宽/圆屏 VM 三连大坑（本轮最大教训）**：
1. **vela_data.bin 快照持久化**：触摸失效 / 页面状态陈旧 / onInit 被跳过 /「新代码不生效」，根因全是 VM 数据分区快照复活旧实例——删 `~/.vela/vvd/<name>.vvd/vela_data.bin` 冷启即彻底重置（重建 vvd、卸载、closeApp 都不够）。
2. **setTimeout 不触发**：宽/圆屏 VM 上页面 setTimeout 永不回调（Band11 正常）——诊断 hook / 异步逻辑在 VM 上必须同步执行。
3. **router.push 带 params 静默失败**（含中文 name）：弹回不改页；用 `router.replace({uri})` 裸参可跳。验收自动导航配方 = 删 vela_data.bin + onInit 同步 router.replace。
- 附：tap 点击原语（hover/微移/长按/sendTouch/零位移滑动全变体）在快照污染态全灭而滑动正常，勿再误判「触摸通道死亡」；多 VM 并行 gRPC 端口派发不可靠，单机串行默认 18777 是唯一稳态。

**四机验收结论**：Band11(Capsule)/S4·S5(QWERTY 弧形三行)/RW5(Cube 大键帽横滚) 渲染+键位触控+拼音组合+候选上屏全链路通过；实拍 download/bandqq-screenshots-vbranch/（11 张）。

## v2.11.0-pro —— band-pro 分支（手环 9 Pro / 10 Pro，336×480）

- **分支**：`band-pro` 自 unified-base(46ee250) 切出；manifest `designWidth: 336`（VM 物理 px 1:1，非 192 等比放大）；versionName `2.11.0-pro` / vc95；键盘 = skb 紧凑 QWERTY（自建，非 AetherZeng fork——其 Cube-For-Xiaomi-Band-Pro 分支只有 README 无代码）
- **classify 四分**：`ar<0.55 → band`（0.39~0.41）/ `ar<0.78 → bandpro`（Pro 系 0.70）/ `ar>=0.95 → round` / `w>=380 → wide`（RW5 0.84）。DEVICE_STYLES.bandpro 定向表覆盖 index/chat/settings/about/compose 全键（列表项压矮至 70px 五会话全显、名字 max-width 170 不截断、气泡 210 宽）
- **skb 键盘样式重建**：unified 2.11.0 重构时 compose.ux 的 `.skb*` 样式段整段丢失（模板有 class 无样式 → 引擎默认大字号 → 键盘溢出屏幕）。照 RW5 2.10.0 实拍重建基线 5 类 + DEVICE_STYLES 三档 ds.skbRow/skbKey/skbFn/skbSpace 键
- **【基线致命 bug 已修】** chat.ux onInit `def.on('device_profile')` 的 `def` 未定义（onInit 后面才 `const def`，应为 `def0`）→ ReferenceError → entry 直达 chat 页数据绑定全空。band-pro 已修（def0）；**redmi-watch / xiaomi-watch-s / unified 分支同 bug 未修**，后续 cherry-pick
- **【336×480 VVM 新坑入库】**
  1. gRPC sendMouse/sendTouch 四种点击变体（含 8px 位移/双点采样/长按）全灭，截屏通道正常；`adb shell input/logcat` 挂死（376⑤ 应验）→ **代码路径导航是唯一 UI 验收手段**
  2. onShow 阶段 router.push/replace（含裸 uri）被引擎静默丢弃 → **导航钩子必须在 onReady 之后**；onReady 后裸 uri push 可用
  3. setTimeout 不触发（397② 应验）+ 带中文/编码 query 的导航失败 → 同步链 + 纯裸 uri + setLastTarget 预置 + 目标页 getLastTarget 兜底
  4. VM 复用（redeploy closeApp+install）后 gRPC startApp 全灭；**fresh cold boot 后首次 startApp 成功率高** → 每个验收态冷启一次 deploy
  5. 截图哈希对比判"部署未生效"不可靠：列表页无动画时帧间哈希天然相同；改用屏上打点（statusText/dcDebug 写 H1/H2/H3）定位执行进度
- **entry 直达法**：manifest router.entry 改目标页 + 版本号自增，比 router 导航更可靠（router 全灭时的兜底验收通道）；发布前 entry 还原 pages/index
- 验收钩子：index.ux `vvdShotHook()`（vs='' 发布态跳过；'list'/'chat'/'kb' 三态注入演示+导航）；chat.ux entry 兜底 `if(!targetId && 0)` 发布态关闭

## v2.9.4（2026-09-26，RPK vc48 + app vc43）—— 真实 NapCat 五连修

**背景**：用户从 DevTools 模拟协议端切换到真实 NapCat 服务器（4.18.28）后实测暴露五个问题（当天日志 bandqq-2026-09-26.log 已随修复从仓库删除）。

1. **群聊被识别成个人联系人**：OneBot v11 群事件只带群号不带群名；群名缓存缺失（HTTP 名单拉取失败）时 conversationName 回退「最后发送者昵称」→ 群会话顶着私人昵称。修复三件套：①conversationName 群缺名回退「QQ群 <群号>」②MessageBroker 收未知群消息按需 get_group_info 补名（in-flight 去重，拉到固化 rememberContactName + 补推会话帧）③手环 index.ux 列表加蓝色「群」徽章 + convSignature 纳入 type。
2. **联系人不自动添加**：名单拉取只走 HTTP 且只在连接时试一次；真实 NapCat 常只开 WS。修复：OneBotClient 新增 WS API 通道（echo=bandqq-api-* 路由 + 8s 超时 + 重连清 pending），requestApi HTTP 两路失败自动回退 WS；MessageBroker.onState 名单未拉到时 30s×10 重试；全链路日志。MessageSender 接口加 requestApiAction 默认实现（带参数 API，get_group_info 用）。
3. **快捷回复无二次确认**：chat.ux 点击先弹确认框（目标+完整内容，80 字截断展示），确认才发；stopBubble 阻断冒泡。
4. **演示模式清理残留**：clearAllMessages 此前只清消息+会话，演示联系人作为可见联系人骨架残留。现在按 DEMO_CONTACT_IDS（20001/30001/40001/10001/10002）同步移除 + 清其免打扰标记；真实联系人骨架保留。新增 2 例单测。
5. **主页日志面板压成一条线**：LogPanel 重构 —— LazyColumn（seq 唯一 key，根除同毫秒同内容撞 key）+ scrollToItem 即时滚动（去 animateScrollTo 风暴互抢）+ weight 外 heightIn(min=140dp) 坍缩兜底 + DragInteraction 上滑暂停自动跟随/拖回底部恢复 + 单行 400 字截断。LogEntry 加 seq 自增（默认 0 兼容）。

**构建环境**：容器重置后 tools/SDK 全失；scripts/setup-buildenv.sh 按新配方重建（JDK17 + Gradle 8.13 + SDK android-37.0 → **cp -r 保留双目录** + 副本三处元数据去 .0：package.xml path/api-level、source.properties ApiLevel、build.prop sdk_full）；脚本存 /home/z/my-project/scripts/bandqq-setup-env-v2.sh。系统 Java21 是 JRE 无 javac（老坑复验）。
**验证**：RPK 包内 2.9.4/vc48 + item-grp/confirmReply/confirmQuickSend/demo id 全入包；APK 2.9.4/vc43 签名 af8819e2 同源 + dex 六标记（bandqq-api-/group name resolved/autoFetch retry/get_group_info/ws fallback/QQ群）；手环单测 61 测 60 过（api 门控 1 例存量失败基线一致）。

## v2.9.5（2026-09-26，RPK vc49 + app vc44）—— 发送链路 WS 回退 + compose 双发根治

**背景**：用户真实 NapCat 实测第二弹「现在信息发不出去」。09-26 日志（已随修复从仓库删除）铁证：拉名单已走 WS 回退成功（api get_friend_list via ws），但 send_private_msg/send_group_msg 只试 HTTP 双路（/send_*_msg 与 /api/send_*_msg）即 ConnectException 失败 —— 手环显示「已发送」实为本地回显，QQ 侧从未收到。v2.9.4 只给联系人拉取补了 WS 回退，发送漏了。

1. **OneBotClient.sendMessage 补 WS 回退**：新增 sendViaWs(body, action, callback) —— 从 buildSendRequest 的 {action,params} 信封取 params，复用 requestViaWs 的 echo 路由（bandqq-api-*），应答 retcode==0 判成功；日志 `send via ws send_*_msg -> ok(retcode=0)`。WS-only 部署从此「能连就能发」。
2. **compose.ux 双发根治**：原实现硬编码 private 先发一条 + correctConversationType 后台补发正确类型（日志表现为群会话 28ms 内 private+group 双发，private 把群号当 QQ 号）。现发送前 store.getConversations() 查类型（与 chat.ux doSend 同语义），只发一条、回显同类型；correctConversationType 整体删除。

**验证**：手环单测 61 测 60 过（api 门控 1 例存量基线）；APK 新增 sendMessage WS 回退单测 2 例（MockWebServer withWebSocketUpgrade 模拟 WS-only 部署：HTTP 两路 500 → WS 应答 retcode=0 → callback(true)）；RPK 包内 2.9.5/vc49；APK 2.9.5/vc44 签名同源。

**追加③（同版）requestApi baseUrl 丢弃回归**：v2.9.4 重构 requestApi→requestApiAction 时把 requestApi(action,baseUrl,callback) 的 baseUrl 静默丢弃（内部只用 config.httpUrl）——ContactScreen 手动刷新/SyncService 定时拉取传的自定义地址全失效。修复=OneBotClient 增 4 参 requestApiAction(action,params,baseUrl,callback) 重载，3 参接口实现委托之。**排查插曲（教训复验）**：跑 OneBotClientTest 整类必挂死——真凶=旧测试 3 因 baseUrl 丢失请求发去 127.0.0.1:3000，MockWebServer 收不到请求，`takeRequest()` 无超时永久等待；jstack 一发锁定。修：①baseUrl 透传 ②全部 takeRequest 加 3s 上限 ③3 例 v2.9.4 行为变更未同步的过期断言更新（群名回退 QQ群<群号> ×2、GameProtocolDetector 的 MockWebServer hostName localhost≠127.0.0.1 硬编码）。**手机端单测 104 测 104 过（历史首次全绿）**。另：MultiEdit「失败前序编辑已生效」陷阱本轮第三次命中（4 参重载函数体被截坏），单 Edit 按实况修复；gradle 卡死先 pkill+清 ~/.gradle/*.lock 再 --no-daemon。

## v2.9.6（2026-10-02，RPK vc50 + app vc50）—— RW5E 等非手环设备二级页黑屏根修 + 全设备版本号统一

**背景**：用户开始把手环 rpk 装到手环外 Vela 设备（RW5E=Redmi Watch 5E，432×514 方屏）实测：主页正常，点「更多选项」（index 右上 3 个点 = goSettings → router.push('/settings')）后**二级页纯黑，右划系统返回可退出**；Vela 虚拟机（VVD）上同页正常。历史 v2.11.7 实验版（二级页黑屏二分诊断分支包，band 2.9.3/bandpro·redmiwatch·xiaomiwatch 2.11.7 混编）已随本版根修从 Release 清除。

1. **黑屏根因定案（证据差推理）**：index 页 scroll 在 v2.9.1 已因"引擎 scroll 背景只绘内容区"改为 absolute 铺满（不依赖引擎 flex），RW5E 上正常；settings/about 页 `.body { flex:1; width:192px }`、chat 页 `.msg-area { flex:1 }`、compose 页 `.preview-scroll { flex:1 }` 全部仍是 flex:1 scroll —— RW5E 固件对 flex:1 滚动视口的高度计算失败 → 内容区 0 高全灭 → 页面只剩 #000000 背景 = 纯黑。
2. **修复（四页统一 index 同构）**：`<div class="*-wrap { position:relative; flex:1 }">` 包裹 + scroll 改 `{ position:absolute; left:0; top:0; width:100%; height:100% }`；chat 保留 id=msgList 与 scroll-top 滚底绑定；compose 的 preview-box padding 转移到 preview-text。四页 = settings/about/chat/compose，一次全修（chat/compose 在 RW5E 上属必然同病，先修防复发）。
3. **全设备版本号统一**：rpk 与 APK 从本版起 versionName+versionCode 完全一致 **2.9.6 (50)**（rpk 49→50 顺升，APK 44→50 跳升锚定，此后恒同步 +1）；about.ux 关于页版本显示 v2.9.4→v2.9.6（2.9.5 漏同步的显示层遗留）。

**验证**：node 单测 61/60 过（api 门控 1 例存量基线一致）；rpk 解包 = manifest 2.9.6/vc50 + 五页齐全 + body-wrap/msg-area-wrap/preview-box relative + absolute 编译形态入包 + about "v2.9.6 · 快应用端"（\xb7）；APK aapt badging vc50/2.9.6 + V2 签名 af8819e2 同源。构建环境容器重置第 N 次重建（scripts/setup-buildenv.sh 幂等复跑）：新坑=nohup 前台会话 & 后台任务随 Bash 工具调用结束被杀（必须 disown）；gradle.zip 从 services.gradle.org 102MB 处截断两次 → 腾讯镜像 mirrors.cloud.tencent.com/gradle 一次到位；ANDROID_HOME=/home/z/android-sdk 必须显式传。

## v2.10.0（2026-10-02，versionCode 51）
- 分支适配：tools/branch-release.js 四分支（band 192 k1.0/bandpro 336 k1.25/xiaomis 466 k1.35 圆屏/redmiwatch 432 k1.20）；构建期 designWidth 对准物理宽 + <style> 与内联 style 值换算（1px 保留）+ InputMethod 键盘常量（screenWidth/192 设计宽/633 滚动总宽）+ xiaomis 安全边距 + 大屏 msg-item width:100%；src/common/branch.js PROFILE 编译期锁档
- 分页窗口化：chat.ux viewStart/viewEnd 两段式（本地窗口前移 pageSize 零网络→本地到头时间锚拉手机端 prepend 后窗口前移），DOM 恒 ≤renderCap（30/42/40/45），"回到最新"按钮，新消息仅锚定最新时滚底
- eSIM 直连：src/common/direct.js（@system.fetch POST {action}+Bearer+/api 回退+8s 超时；send_message/get_history/get_visible_contacts 翻义；raw_message CQ 降级 at/poke）；app.ux sendUpstream 三态（互联连→互联失败回退直连/未连有配置→直连优先/都无→原路径）；MessageBroker.pushDirectConfig（127.0.0.1/localhost 不下发）；settings 独立线路状态行；chat 4s/index 20s 前台轮询
- 性能：store 落盘 300ms 尾沿防抖（快照回调时重取，clearAll 不复活）；排序幂等 WeakSet（数组自定义属性会污染 deepEqual）；MAX_MESSAGES 120；app.onCreate 四连 send 清理
- 交付：download/ 四分支+通用 rpk（154KB 级）+ APK 12,225,650B；Release v2.10.0 id=402007052 六附件匿名 200

## v2.13.0（2026-10-03，versionCode 53）

- 键盘全分支换装 NEORUAA/Vela_input_method 单组件（三屏形内置，screentype 构建期写入 + xiaomis 466/480 换算 636→617；词库分片按需加载 + 27398 字合入 cn.txt）；kb-variants/ 退役
- AstrBot 双包：bundled 胖包内嵌引擎（astrbot-engine 模块 proot+rootfs62MB，EngineManager/EngineService）+ companion 瘦包伴侣；flavor sourceSet 同签名 AstrBotSection 隔离
- OneBot v11 扩展：send_like/friend_poke/group_poke/send_group_sign/set_msg_emoji_like/delete_msg/get_stranger_info/get_group_member_info；MessageSender callback(+messageId)；action_result/user_info 帧；DevTools ActionRouter 同步模拟
- DevTools 1.4.0 Compose+miuix 重写（vc7）；许可证 MIT→AGPL-3.0-only
- 注意坑：branch-release 步骤③样式换算曾覆盖步骤②screentype（composeTagged 须作为换算基线）；gradle flavor 配置用 add("bundledImplementation", ...)；libsudo.so 为占位文件跳过；miuix-blur 需 tools:overrideLibrary

## v2.12.0（2026-10-03，versionCode 52）
- 键盘分支原生化：用户报告「以前给其他分支单独适配的键盘不见了」——v2.10.0 四分支包里非手环机型仍装着胶囊键盘只做数值缩放。本轮 kb-variants/（仓库根，**不能放 band-qq/ 项目内：aiot 工具链会扫描项目内全部 .ux 并误编译报 not in src**）存三套完整 InputMethod 组件快照，branch-release.js 构建期 swapKeyboard 整体替换 src/components/InputMethod/（tmpdir 暂存→finally 恢复）：
  - band=pill（Capsule 原样）；bandpro=rect×kbScale(336/432)=0.7778；redmiwatch=rect×1.0（432 原生）；xiaomis=circle×1.0（466 原生 QWERTY）
  - rect 版修上游两 bug：模板 onscroll="handelScroll"→handleRectScroll 拼写、progress percent 绑定 {{percent}}（不存在的变量）→{{scrollPercent}}
  - rect 符号键扩充：第二行+～！？第三行+【】「」、·，最宽行=64+15×(62+4)=1054（432 标尺），bandpro 构建期换算 820
  - circle 版移除 hide 死按钮（compose 不监听 hide 事件）
  - ⚠️ 教训：**JS 同名函数声明提升，旧版 scaleKeyboardConsts(src,b) 残留会静默覆盖新版 3 参版**（bandpro 1054→820 替换空转，verify 词边界正则才抓到）；verify 断言勿用「编译产物含注释」——编译器剥注释，用数据字（罕见字「錒」=新字库非 GB2312 标记）
- 全量拼音字库：legacy/com-bandqq-variant 全量池（20924 字数组格式）与现役频序字典（6763 字符串格式）合并 → **419 音节 27398 字**（字符串格式兼容 dicUtil split('')；频序拱顶保留，脚本 scripts/convert_dic.py）；kb-variants/*/assets/dic.js 同步
- AstrBot 本地伴侣（APK）：集成 MuFengDR/AstrBot-Bubble-Android-App 为伴侣模式（**不做二进制合并**：对方 Flutter+64MB Ubuntu rootfs）。app/…/astrbot/AstrBotBridge.kt：检测 com.astrbot.astrbot_bubble(+.profile/.debug)→getLaunchIntentForPackage 拉起；probeLocalNapcat TCP 探测 127.0.0.1:3001/3000；LOCAL_WS_URL/LOCAL_HTTP_URL 一键填入。manifest 加 <queries> 包可见性；SettingsScreen 新增「AstrBot 本地机器人」卡片（入场序号 6，后续卡片顺移）
- README 项目化改版：徽章/分支矩阵含键盘列/功能分区/安装指向 Releases/更新日志 <details> 折叠/引用致谢表（Vela-Input-Method-Revise、NEORUAA、上游、stapxs、merqury-vela、AstrBot-Bubble、AstrBot、miuix）
- 验证：四分支 rpk 解包 68 断言全 PASS；node 单测 80/79（基线）；APK badging vc52/2.12.0 + AstrBotBridge 多 dex FOUND；构建环境第 N 次重建：/home/z/tools+jdk17、/home/z/android-sdk 存活，scripts/setup-buildenv.sh 已改腾讯 gradle 镜像

## v2.14.0（2026-10-03，versionCode 54）

**胖包 AstrBot 独立标签页**：
- Tab 体系 flavor 分治：`AppTab` 枚举（main）新增 `AstrBot`（History 与 Settings 之间）；每 flavor sourceSet 一份 `AppTabSet.kt` 定义 `VisibleTabs`——bundled 5 页（含 AstrBot）/ companion 4 页不变；BandQQApp 的 HorizontalPager 与 BottomBar 全部改以 VisibleTabs 为准。
- bundled 新增 `AstrBotScreen.kt`（PageScaffold 全页）：状态卡（含 Installing 百分比/Error 详情）+ 启动/停止 + 检测本机 NapCat + 日志开关 + **一键填入并保存**（直接 configManager.save endpoint.copy(ws/http=127.0.0.1, Token 保持) + pushQuickRepliesNow，不再回设置页手填）+ 日志区放宽 80 行。companion 同签名 stub（永不渲染，仅满足 main when 编译）。
- bundled 设置页 `AstrBotSection` 降级为指引卡（防双入口状态不同步）；companion 卡片（伴侣模式）原样保留。

**键盘非手环机型两修复**（用户报：①看不到输入预览 ②无输入时键盘只显示一半、输入一下恢复）：
- 根因：InputMethod 根容器 `position:absolute;bottom:0` + `height:auto`——部分固件首帧对 auto 测量坍缩（bottom 锚定后内容下半出屏），任意数据变化触发重排才恢复；拼音行随输入增减高度是同族触发器。对照上游 NEORUAA 原文件逐字节一致 → 宿主/固件布局时序问题非合并引入。
- 修复：①根高显式化——脚本顶部 `KB_H_CIRCLE=321/KB_H_RECT=283/KB_H_PILL=333` + `kbTotalFor()`，onInit/watchHidePropsChange 同步 `kbHpx`（hide→0px），模板改 `height:{{kbHpx}}`；②拼音行 `show` 从 wrap 移到 text——wrap 恒在流内占 28px，键盘总高不随输入变化（常量布局，首帧即完整）。
- branch-release.js `scaleKeyboardConsts` 扩展：kbScale≠1（xiaomis）时三个 KB_H 常量按 466/480 同步换算（312/275/323），与样式逐值换算（321→312/255→248+28→27）恒一致。

**智能自动渲染器**（"自动渲染器智能化"落点=消息段→手环可读文本链路）：
- 手机端 OneBotParser：新增 `cqUnescape`/`jsonCardSummary`（meta.prompt → meta.{news,music,app,software,structured}.title+describe/singer → 顶层 title/desc → config.desc → 兜底）/`xmlCardSummary`（brief 属性/<title>）/`markdownToPlain`（AstrBot 回复可读：去 #/**/*/__/`/链接括号保留锚文本/图片→[图片]/列表→·）/`fileLabel`（文件名截 24）/`capLen`（800 字符护栏）。
- degradeContent 数组段 8 类 → 20+ 类：json/xml/markdown/forward/mface/poke/dice/rps/share/location/tts/contact/gift + image GIF 识别（url/file 后缀）+ file 带文件名；degradeCqString 增加 [CQ:json/xml] 卡片渲染（data 值 CQ 转义先反转义）、file 提取 name=/filename=、forward/mface/image.gif 判定。
- 手环端 protocol.js degradeContent（旧手机端兜底通道）同步同规则；测试：protocol.test.js +10 项、OneBotParserTest +11 项（json prompt/音乐/非法/xml title+brief/markdown/新段型矩阵/json 整链/CQ 串/800 护栏/cqUnescape）。

**libbusybox.so 缺失（用户真机报"缺失 libbusybox.so（仅支持 arm64 真机）"）**：
- EngineManager.installIfNeeded bin 组装改双通道：nativeLibraryDir 有 → copy；缺 → `extractSoFromApk`（ZipFile 扫 applicationInfo.sourceDir 内 lib/arm64-v8a/<so>，退化匹配任意 lib/<abi>/）；两者皆无 → 报错附带 nativeLibraryDir 路径 + Build.SUPPORTED_ABIS + APK 路径诊断。覆盖 ROM 解压目录不全 / 历版 APK 未打 .so 场景。

**验证基线**：手环端 node 单测 90 测 89 过（新增 10 项渲染器全绿；api.test.js 1 例存量环境失败基线一致）；构建/解包断言详见当轮 worklog。

**NapCat 4.18.29 崩溃循环根治（v2.27.0，用户 10-05 第八份日志 engine-2026-10-05.log 定案，分析后已删）**：
- 三重定案：①钉扎只管新下载——漂移窗口内已装的 4.18.29 从未被降级（check_napcat_ready 通过即跳过重装；自愈 L3 要连跨三轮巡检）②看门狗 18:48 承诺"2 分钟内重启"5 分钟内从未发生（自愈动作全进 watchdog.log 引擎日志零动静，内层巡检链疑似被 QQ Electron RUN_AS_NODE 路径挂起冻结）③node 三连退真机全军覆没（"反检测自动开启失败（无可用 node 运行时）"）而 rootfs 自带 python3.12 一直闲置
- 修法四件：版本钉扎强制执行（napcat.mjs 字面量探测 + 立即重装 + 15 分钟退避）；反检测写入 python3 化（语义与 JS 逐项对齐，node 降兜底）；wait_napcat_ports 立即自愈（检测"主进程退出"直接同步调 napcat-start，不等看门狗，每等待期最多 3 轮）；看门狗硬化（timeout 480 包裹内层调用 + 端口未监听时 [NAPCAT-WD] 摘要写 fd9 引擎管道）
- 版本探测不做 semver 解析：钉扎版本号以字面量烙在 napcat.mjs（"4.18.28"），grep -qF 即判匹配；NapCat.Shell.zip 的 package.json version 恒为 0.0.1 不可用
- 测试：21 项断言全绿（python3 patch 四场景/幂等/守卫/真假版本探测/退避/端到端自愈链冒烟 L1→L2→L3 升级+tap 可见性+重装兜底）
