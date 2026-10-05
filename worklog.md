# worklog.md

---
Task ID: 9
Agent: main (Super Z)
Task: band-qq v2.4.4 —— 用户第七轮反馈：预测返回终修 + Monet 修复 + 保活权限检测 + 动画补全 + Stapxs 借鉴 + 代码重读优化

Work Log:
- 容器重置：重克隆 BandQQ、重建构建环境（JDK17/Gradle8.13/SDK37）；发现 android-37 目录陷阱新变体——AGP 每次构建按库存清单重装 android-37.0，必须 cp 保留两份不能 mv
- 预测性返回双重根因：① observePredictiveBack() Flow 默认值 false ≠ AppConfig true → 启动反射设置误关；② 开关只写配置不立即应用。修复=默认值统一 + ThemeScreen 回调立即反射设置 + activity.recreate()（KSU 同款）
- Monet 无法调节根因：推入页（ThemeScreen/KeepAliveScreen）是外层 Scaffold 兄弟节点，整体盖住外层 MiuixPopupHost 的下拉浮层（popup slot 只覆盖 content 内部）。修复=推入页移入 content slot 覆盖 Box + bottomBar 随推入收起（KSU 同构）
- 动画补全：四屏 isActive 参数（预组合页不再提前跑完 reveal）；ThemeScreen 二级展开 expand/shrink+fade 跟随动画速度；listItemReveal 级联 index 封顶 6（修 200 联系人末项等 20s 缺陷）
- KeepAliveScreen 重写：4 项权限检测卡（电池优化/通知/自启动需手动/前台服务）+ 状态 chip + 品牌自启动页直达 Intent 矩阵（7 品牌 10 组件，失败降级应用详情）
- Stapxs 借鉴（性能优先）：消息撤回提示移植——parseRecallEvent + recallMessage 内容替换 + 会话帧推送，手环端零改动零流量
- 代码重读 6 项优化：OkHttpClient 全局共享、OneBotStateBus（连接状态实时感知）、未读角标事件驱动、LogPanel 去 AnimatedVisibility、duplicates 上限、Settings/History 排版重排
- 构建 v2.4.4（vc29），aapt2/apksigner 验证同源（af8819e2），交付 download/bandqq-sync-release-2.4.4.apk；git commit 本地完成，push 因容器重置后无 GitHub 凭据未完成（需用户提供 token 或自行推送）

Stage Summary:
- 产物：/home/z/my-project/download/bandqq-sync-release-2.4.4.apk
- 三大 bug 根因与修复全部入库仓库 MEMORY.md v2.4.4 节，commit 1831ddb

---
Task ID: 10
Agent: main (Super Z)
Task: band-qq v2.4.5 全量交付（崩溃/权限/动画/图标/手环功能/SnowLuma 调研）

Work Log:
- 崩溃三层防御：ThemeScreen 回退 KSU 默认动画、推入页 remember（防 recreate 恢复推入态）、CrashGuard 崩溃落盘+设置页查看
- 通知权限：manifest+串行链式请求+保活行内申请；无障碍检测行回归（5 项）
- 动画延迟根治：settledPage→currentPage；图标白边根治：rebuild-icon.py 圆裁切去描边环三端重生成
- 手环端新功能：撤回实时原位替换+灰显、@我 金色高亮+列表角标（手机预算/手环零计算）
- SnowLuma 调研：docs/snowluma-native-android-research.md + scripts/snowluma-termux.sh 一键本机部署
- 构建 vc30/2.4.5 验签同源交付；MEMORY.md/worklog 更新；git push（用户 token）成功 4c51ca3..25719ef

Stage Summary:
- download/bandqq-sync-release-2.4.5.apk（12.1MB）+ bandqq-watch-release-2.4.5.rpk（252KB）
- 建议用户：装 APK+rpk 实测；若再崩溃用设置页「崩溃日志」复制反馈

---
Task ID: 11
Agent: main (Super Z)
Task: band-qq v2.4.6 —— 用户第八轮反馈：两选项点击仍崩（附堆栈）+ 无障碍权限没有申请 + 更新 README/记忆文件

Work Log:
- 堆栈定位：崩溃点=MiuixPopupHost.PopupEntry → NavigationBackHandler → "No NavigationEventDispatcher was provided via LocalNavigationEventDispatcherOwner"——点击 Monet/预测性返回都会弹窗，一个根因两个崩溃，与选项处理器无关
- 根因实证：下载 google maven sources jar 验证——ComponentActivity 1.12.4 才实现 NavigationEventDispatcherOwner + initializeViewTreeOwners 挂 decorView + onBackPressedDispatcher.eventDispatcher 统一管线；navigationevent-compose 的 Local 默认 null 靠 ViewTree 兜底；工程 activity-compose 1.9.1 必崩
- 修复：activity-compose 1.12.4（POM 要求 compose-runtime 1.7.0，与 BOM 2024.09.03 无冲突，自带 navigationevent 1.0.2）+ MainActivity setContent 显式 CompositionLocalProvider 注入 owner（Popup/Dialog 子树双保险）
- 无障碍：v2.4.5 只有"干扰检查"行、应用自身无服务可开。新增 KeepAliveAccessibilityService（空实现+canRetrieveWindowContent=false）+ manifest + res/xml 配置 + strings；KeepAliveScreen 新增「无障碍保活（本应用）」行（Settings.Secure 判定自身组件 + ON_RESUME 刷新 + 直达设置）；干扰检查改 enabledForeignServiceCount 排除自身
- 通知权限核实：v2.4.5 的 manifest+串行请求已存在（本轮无需改动）
- 文档（用户点名）：README 标题/徽章 v2.1.0→v2.4.6，补 v2.4.6 与 v2.4.2~v2.4.5 全部缺失更新日志；MEMORY.md 当前版本行 + v2.4.6 章节（含"勿靠记忆猜版本行为，下载 sources jar 实证"备忘）
- 构建：vc31/2.4.6；daemon OOM 一次（GRADLE_OPTS 降堆 -Xmx1400m/-Xmx900m 重试过）；aapt2 验 service 打包；apksigner af8819e2 同源；交付 download/bandqq-sync-release-2.4.6.apk（12MB）
- git commit 7f15b81，token 推送成功 25719ef..7f15b81

Stage Summary:
- 产物：/home/z/my-project/download/bandqq-sync-release-2.4.6.apk（覆盖安装即可）
- 双崩溃根治 + 无障碍可申请；建议用户装后重点回归：Monet 下拉、预测性返回开关、保活向导无障碍开启

---
Task ID: 12
Agent: main (Super Z)
Task: band-qq v2.4.7 —— 预测开关点击被踢回上级菜单修复 + APP 实用功能增强

Work Log:
- 预测开关 UX 根因：flag 需 recreate 生效（ViewRootImpl 仅 attach 时读）+ 推入态故意 remember → 重建回主页。新增 ui/RecreateCoordinator（reopenScreen+armed），recreate 前记录、BandQQApp LaunchedEffect 消费自动重新推入；armed 区分冷启动
- 新功能三件套（手机端判断/手环零感知，符合架构铁律）：ConfigManager 四新键；MessageBroker.onEvent 在入库后仅拦截 bandSender（勿扰跨零点判断 + 群聊 0全部/1仅@我/2不推）；pushTestMessage 固定 bandqq-test 会话 + SyncService companion testPush 钩子
- SettingsScreen 新增「消息推送策略」区块（SwitchPreference 勿扰+时间校验保存、三档按钮、一键测试推送），reveal index 顺延
- 构建 vc32/2.4.7 一次通过，验签同源，交付 download/bandqq-sync-release-2.4.7.apk
- README 补 v2.4.7 日志、MEMORY.md v2.4.7 章节，git 推送 7f15b81..7c7d149

Stage Summary:
- 产物：/home/z/my-project/download/bandqq-sync-release-2.4.7.apk（12MB，覆盖安装）
- 建议用户回归：①预测开关点击后应刷新并留在主题设置页 ②设置页勿扰/群聊三档/测试推送

---
Task ID: 13
Agent: main (Super Z)
Task: band-qq v2.5.0 —— 预测开关落盘竞态根治 + 手环11适配 + 解析器修复 + 新消息振动 + 测试推送模拟器

Work Log:
- 容器重置恢复：重新克隆（7c7d149）+ 重建工具链（Temurin JDK17/Gradle 8.13/cmdline-tools/android-37.0+build-tools 37.0.0）
- 预测开关根因：launch{写盘} 后同步 recreate()，写协程未及调度即被销毁 → 同一协程顺序执行修复
- 手环11：四页定高容器改 flex:1 + compose .page 补 100% + RPK manifest 2.5.0/vc31
- 解析器：isAtMe 双格式 + at/reply 段可读化 + degradeCqString
- 手环振动：app.ux push_message 短振动（三重过滤）
- 模拟器：pushTestMessage(chatType,scenario) 九场景真实管线，钩子改 (String,String)->String，SettingsScreen 九宫格 UI
- 构建：APK vc33 验签同源 + RPK 2.5.0 交付 download/；README/MEMORY/worklog 更新；git push 7c7d149..2c36544

Stage Summary:
- 产物：bandqq-sync-release-2.5.0.apk（12.2MB）+ bandqq-watch-release-2.5.0.rpk（252KB）
- 回归建议：预测开关刷新后保持开启、模拟器九场景、Band11 布局、新消息振动

---
Task ID: 14
Agent: main (Super Z)
Task: band-qq v2.6.0 —— 预测返回 KSU 原版重做+真实手势动画 / 测试消息入库防掉同步 / 手环表情支持 / Band 9/10 视觉修复 / Vela 虚拟机多模态验收

Work Log:
- 容器重置恢复：重克隆(2c36544) + 工具链重建（Temurin JDK17 + Gradle8.13 + platforms;android-37.0 两份拷贝陷阱复现处理）
- 克隆 KernelSU 实证预测返回实现：开关只落盘不 recreate（下次启动生效）；我方 recreate 路线即闪烁根源 → ThemeScreen 同构重写 + RecreateCoordinator 删除 + 推入页回归 rememberSaveable
- BandQQApp 新增 PredictiveBackHandler：手势期间推入页跟手位移/缩放/淡出，离散返回兼容（activity-compose 1.12.4 源码验证），替代三个 BackHandler
- 「手环一会就删除」双保险：pushTestMessage 入库手机聊天记录 + RPK store.js setVisibleContacts 保留有消息临时会话
- 表情支持：OneBotParser 80 项 QQ face→emoji（NapCat 官方 id 表），emojiNative 配置 + 设置页开关（防真机 tofu）
- Vela 虚拟机基建：aiot VVD 手动装 SDK（vela-watch-5.0 自定义分辨率镜像）+ vvd-ctl.js（创建/无头启动/gRPC截屏/触摸）+ emulator-setup.sh；Band11 212x520 与 Band9 192x490 双机并行
- 多模态截图验收：首页/设置/聊天/输入四页双分辨率全部截图检查；发现并修复首页空态折行、设置页清空项竖排两 bug；键盘 480px 系横滑设计非溢出；watch5.0 镜像字体无 emoji 字形（GBK 符号/颜文字正常）
- 构建 APK vc34/2.6.0 + RPK 2.6.0 同源验签交付 download/；README/MEMORY 更新；git 推送

Stage Summary:
- 产物：bandqq-sync-release-2.6.0.apk + bandqq-watch-release-2.6.0.rpk（覆盖安装）
- 回归建议：①预测开关点击无闪烁、重启后手势有跟手动画 ②测试推送消息在手机聊天记录可见且手环不掉 ③表情渲染（若真机方框→设置页关「表情原生渲染」）④Band 10/11 首页/设置无折行
---
Task ID: 15
Agent: main (Super Z)
Task: band-qq v2.7.0 —— 互联自动拉起快应用(置顶) + 联系人头像/偏下修复 + 手环图标纯黑 + @动效 + 快捷回复即时同步 + 清空记录同步根治

Work Log:
- 摸底：InterconnectBridge 已有 launchWearApp 链路；清空同步根因定位=手环 doClear api.send 未 await 失败静默；图标底色实测 (13,16,21) 非纯黑；快捷回复仅连接时推送；手机端列表无头像
- 新建 sync/AutoLauncher.kt（通知预告+延迟 launchWearAppNow+三路取消+风暴去重）；InterconnectBridge 新增 launchWearAppNow()/onConnect 挂钩；MessageBroker.onEvent 推送后触发
- ConfigManager 新键 autoLaunchEnabled/autoLaunchDelaySec；SettingsScreen 新「快应用自动拉起」专区（开关+5/10/15/30s 档），reveal index 顺延
- 快捷回复：SyncService.pushQuickRepliesNow hook + 设置页「保存并同步到手环（即时生效）」按钮 + 主保存附带
- 清空：手环 await+3 次重试+回拉确认；手机端 ack 空会话帧；HistoryScreen 二次确认弹窗；clearAllHistory 补清 atUnread
- 头像：新建 AvatarCircle.kt（hue/avatarChar 与手环同源），ContactScreen/HistoryScreen 行首 40dp 居中
- 图标：scripts/recolor-icon-black.py 通道级重着色 #0D1015→#000000（含过渡带压暗），icon_preview+手环 icon 更新，多模态复验四角 (0,0,0) 且蓝圆企鹅无损
- 手环端：chat.ux .bubble-at 金边+atPulse 呼吸动画（RPK 编译产物验证 keyframes 描述符生成）；index.ux .item-at 同款；settings.ux doClear 重写 + 确认文案更新
- 版本：APK vc35/2.7.0（签名 af8819e2 同源）+ RPK 2.7.0/vc33；受影响模块单测全过（api.test.js/GameProtocolDetectorTest 2 例为存量环境失败，基线复跑同样失败）
- README/MEMORY/worklog 更新；git 提交并推送（含此前滞留未推的 v2.6.0 commit）

Stage Summary:
- 产物：/home/z/band-qq-v2/dist/bandqq-sync-release-2.7.0.apk（12.2MB）+ dist/bandqq-watch-release-2.7.0.rpk（253KB），同步交付 /home/z/my-project/download/
- 回归建议：①设置开启自动拉起→退出手环快应用→等一条新消息→通知预告+N 秒后快应用自动打开 ②改快捷回复点「保存并同步」→手环聊天页立即出新按钮 ③手环清空→确认文案含"请求手机清除"→手机端同步清空不再回灌 ④手环桌面看图标与背景无色差 ⑤设置里发 @我 测试消息→金色气泡呼吸动效

---
Task ID: 16
Agent: main (Super Z)
Task: band-qq v2.8.0 —— DevTools 模拟 OneBot 协议端 APK（用户无服务器）+ 拉起后手环震动 + 双端设置互通 + 双端关于页

Work Log:
- 容器重置恢复：重克隆(39a2c46) + 工具链重建（JDK17/Gradle8.13/cmdline-tools/android-37.0+build-tools 37.0.0）；新陷阱：AGP 找 hash string 'android-37' 而 sdkmanager 装的是 37.0 → cp -r android-37.0 android-37 + sed 修 ApiLevel=37
- DevTools APK（新 module :devtools，独立签名同源）：WsServer.kt 手写 RFC6455（握手/掩码帧/扩展长度/分片/ping-pong，零依赖）+ HttpApiServer.kt 极简 HTTP（send_private_msg/send_group_msg 回 retcode 0+可选自动回推对方消息闭环、get_friend_list/get_group_list 模拟联系人）+ MsgBuilder 九场景 OneBot v11 数组段载荷 + MainActivity 纯代码 UI（快捷按钮/自定义发送/自动回推开关/事件日志/端口记忆）
- 拉起震动：AutoLauncher pendingVibrate(AtomicBoolean+60s窗口)+launchAlertHook；SyncService bandAlert 发 band_alert 帧；RPK app.ux mode=1 短震×2/mode=2 长震；设置页三档（autoLaunchVibrate 0/1/2）
- 双端互通：settings_state/settings_update 帧协议（msg_vibrate+emoji_native）；MessageBroker get_settings/settings_update 处理+settingsWriter hook；ConfigManager.applyBandSettings 变化才落盘；store.js band_settings 持久化；settings.ux 消息震动/表情渲染两行双向同步；push_message 震动判定加开关
- 关于页：APP AboutScreen.kt（版本/作者一秋/QQ群 885186458复制/仓库跳转/简介）+ BandQQApp showAbout 推入；RPK pages/about/about.ux + manifest 注册 + settings.ux 关于行；buildConfig=true 开启（AGP8）
- 构建：APK vc36/2.8.0 + devtools vc1/1.0.0 验签同源（af8819e2）+ RPK 2.8.0/vc34（包内验证新协议/页面齐全）；node 单测 49/49 过（protocol/store），api.test.js 与 GameProtocolDetectorTest 各 1 例存量环境失败（基线一致）
- 交付 download/ 三件产物；README/MEMORY 更新

Stage Summary:
- 产物：bandqq-sync-release-2.8.0.apk + bandqq-devtools-release-1.0.0.apk + bandqq-watch-release-2.8.0.rpk
- 回归建议：①装 DevTools→启动服务器→APP 默认地址直连→点模拟按钮消息到手环 ②手环回复→DevTools 日志+自动回推对方消息 ③手环设置页改「消息震动」→APP 设置页状态同步 ④开启自动拉起→拉起后手环短震×2 ⑤双端关于页信息完整

---
Task ID: 17
Agent: main (Super Z)
Task: band-qq v2.9.6 —— RW5E 等非手环设备二级页黑屏根修（四页 scroll absolute 化）+ 全设备版本号统一（2.9.6/vc50）

Work Log:
- 用户实测 RW5E（Redmi Watch 5E）虚拟机正常、真机点 index 右上 3 个点（goSettings→/settings）后二级页纯黑，右划可退出
- 根因：settings/about 的 .body、chat 的 .msg-area、compose 的 .preview-scroll 全是 flex:1 scroll——RW5E 固件 flex:1 滚动视口高度计算失败致内容全灭；index 因 v2.9.1 已改 absolute 铺满而幸免（证据差定案）
- 修复：四页统一「wrap(position:relative;flex:1) + scroll(position:absolute;inset:0;width:100%;height:100%)」index 同构改造；chat 保留 msgList id 与 scroll-top 滚底绑定；compose padding 转移 preview-text
- 版本统一：manifest 2.9.6/vc50 + gradle 2.9.6/vc50（APK 44→50 跳升锚定，此后 rpk/APK 版本号恒一致）+ about 页显示 v2.9.6（2.9.5 漏改）
- 构建：环境重建（disown 防后台任务被杀 / 腾讯镜像下 gradle / ANDROID_HOME 显式传 / android-37 双目录陷阱按 MEMORY 配方）→ rpk 148,702B + APK 12,225,650B
- 验证：node 61/60（基线一致）；rpk 解包版本/五页/wrap+absolute 形态全 PASS；APK badging vc50/2.9.6 + V2 签名 af8819e2 同源
- v2.11.7 历史实验 Release（版本混编 2.9.3+2.11.7）已删除；Release v2.9.6 双附件发布

Stage Summary:
- 产物：bandqq-watch-release-2.9.6.rpk + bandqq-sync-release-2.9.6.apk（GitHub Release 分发）
- 回归建议：①RW5E 实装：点 3 个点进设置（应见分组卡片非黑屏）→ 关于 → 返回 ②聊天页消息滚动/快捷回复 ③撰写页键盘+预览 ④手环 9/10/11 全页回归（渲染路径改动）⑤两端关于页版本号应同显 2.9.6

---

## v2.14.0（2026-10-03，versionCode 54）—— 胖包 AstrBot 独立标签页 + 键盘非手环机型两修复 + 智能自动渲染器 + libbusybox 双通道

- 用户四指令：胖包 AstrBot 单独开一个标签页 / 缺失 libbusybox.so（仅支持 arm64 真机）/ 非手环机型键盘看不到输入预览 + 空输入时只显示一半输入一下恢复 / 加强自动渲染器智能化（CI 一事用户撤回）。
- 现场：本地浅克隆落后 11 提交，ff 到 4db491d（v2.13.0）；Token 重新写入 remote URL 持久保留。
- Tab 分治、键盘显式根高（KB_H_* 常量 + kbHpx + 拼音行恒占 28px + xiaomis 312/275/323 换算）、渲染器 20+ 段型（OneBotParser + protocol.js 双端同规则）、EngineManager APK 直取回退 —— 详见 MEMORY.md v2.14.0 节。
- 构建环境重建：setup-buildenv.sh 的 adoptium 源失效 → 清华 Adoptium 镜像（17.0.20.1_1）+ 腾讯 gradle 8.13 + google cmdtools；android-37 目录陷阱修复照旧。gradle 首跑 daemon 被 OOM 杀（4GB 容器）→ 分模块 --no-daemon -Xmx1024m workers.max=1 分步完成。
- 验证：verify_2140.js 42 项全 PASS（kbHpx 入包 / xiaomis 常量 312 / screentype 逐分支 / 渲染器标记）；APP 单测 228/228（双 flavor，历史首次全绿，含新增 11 项渲染器）；手环端 89/90（新增 10 项全绿，api.test.js 1 例存量基线）；胖包 lib/arm64-v8a/libbusybox.so 在包核实（用户报错根因=设备主 ABI 非 arm64 或历版包缺 so，双通道均覆盖）。
- 推送 4db491d..071d719（remote 凭据保留）；Release v2.14.0 八资产（scripts/gh_release_2140.py，可复用模式：下版改 TAG/ASSETS/BODY）。

## v2.15.0（2026-10-03，versionCode 55）—— busybox error=13 根修（targetSdk 28）+ 键盘高度链全显式化 + 演示模式补齐新特性

- 用户真机反馈三件：①胖包装引擎报 `Cannot run program .../files/engine/bin/busybox: error=13, Permission denied`；②键盘还是只显示一半、输入都没有展开（v2.14 修复无效）；③演示模式把新加的特性补上。
- error=13 根因定案：Android 10+ 对 targetSdk≥29 应用启用 W^X（SELinux untrusted_app_29+ 禁止 exec app 数据目录内二进制），v2.14 的"双通道"只解决了 so 来源问题，没有解决 exec 位置问题——引擎链路 busybox/bash/proot→rootfs 全在 files/engine 下，逐级 exec 全被拦。根修 = targetSdk 34→28（Termux/UserLAnd 同款方案；权限代码全按 SDK_INT 守卫，POST_NOTIFICATIONS/BLUETOOTH_CONNECT 运行时申请不受 targetSdk 影响；FGS 类型已声明）。EngineManager 安装/启动两处 catch 附 W^X 诊断提示（旧包用户可自诊）。
- 键盘根因定案：v2.14 只显式化了根容器，懒建层（if）与黑底层（show）仍 height:auto —— 固件首帧 auto 测量坍缩依旧；且根高固定后输入重排不再波及中间层，v2.13 的"输入一下就恢复"随之消失（用户实测吻合）。修复 = 根/懒建层/黑底层三层全部 kbHpx 显式定高，高度链零 auto 测量点；KB_H_* 分区常量不变。
- 演示模式：injectDemo 补齐 v2.13/2.14 新形态（JSON 音乐卡/XML 红包卡/markdown/文件名/合并转发/GIF/表情包/骰子/位置/链接/回复+链接组合/语音/撤回 rc 灰显），全部经 degradeContent 真实渲染链生成（与真机同路径，渲染器退化即刻暴露）；store.test.js 新增 14 项断言。
- 验证：verify_2150.js 63/63 PASS（kbHpx≥3 处绑定/演示模式 4 标记/渲染器标记/版本单轨/designWidth 逐分支）；APP 单测 228/228（双 flavor 各 114）；手环端 90/91（api.test.js 1 例存量基线）；双 APK badging vc55/2.15.0/targetSdk 28；胖包 lib/arm64-v8a 五件套 + 64MB rootfs 在包；dex 检索 W^X 提示/extractSoFromApk 在包；签名 af8819e2 同源。
- 构建：setup-buildenv.sh 全自动重建（JDK/gradle/cmdtools/SDK 均幂等跳过或直装）；gradle 分模块 --no-daemon -Xmx1024m（daemon 首跑仍被 OOM 杀，产物经 badging+时间戳核实为 fresh）。
- Release v2.15.0 八资产（scripts/gh_release_2150.py）；交付 download/bandqq-2.15.0/。

## v2.16.0（2026-10-03，versionCode 56）—— rootfs 前缀拍平 + message_id 数字形态 + 直连 v11 + 键盘宿主去 flex 化（VM 实测驱动）

- 用户真机反馈三件：①胖包安装报 `rootfs 解压后缺 bin/bash，包可能损坏`；②键盘依旧只显示一半、输入预览依旧看不到；③撤回功能无法使用、OneBot v11 特性基本用不了。
- ①根因定案：proot-distro 发行包（ubuntu-noble-aarch64-pd-v4.18.0.tar.xz）全部条目带 `ubuntu-noble-aarch64/` 顶层前缀，v2.15 直接 `tar -x -C rootfs` 后实体落在 `<rootfs>/ubuntu-noble-aarch64/` 之下，`rootfs/bin/bash` 校验必然失败（v2.13/14 死于 W^X exec、v2.15 死于前缀——引擎从无一台真机装成功过）。根修 = EngineManager 两段式解压（busybox xz -d 落地 tar → tar -x，退出码独立可判）+ 前缀拍平（检测 `<distro>/usr/bin` 嵌套后逐项 rename 上移，兼容无前缀包）+ 安装前清残留（无 stamp 即整目录清空）+ 双路径校验（usr/bin/bash 实体 + bin/bash 符号链接）+ busyboxApplets 诊断（失败时打 applet 清单）。
- ②键盘第四轮修复改用 VM 实测驱动：重建 Vela QEMU 环境（镜像 383MB 分片下载 + setup2 + emulator 启动三修：SDK 目录 cp -a 补齐/lib64/qt/lib 入 LD_LIBRARY_PATH/-no-window 禁 GL）。VM 关键发现：**guest 固件视口固定 466（镜像原生），与 AVD hw.lcd 无关**——466 恰=xiaomis 分支 designWidth，故 xiaomis rpk 在 VM 上 1:1 忠实渲染（bandpro/band 则缩放失真）。真机"半键盘"根因定案 = compose 宿主 `flex:1` 预览区在真机固件上挤压键盘（v2.13"输入一下就恢复"=重排偶发修正，v2.14/15 显式定高反而冻结布局）；修复 = compose 全Layout去 flex 化：top-bar 流内固定高 + preview-box absolute（显式高=屏高-top-bar）+ kb-dock absolute 贴底锚（显式高=键盘物理高，铁律 V4 防零高裁剪）+ preview-text padding-bottom=键盘物理高（分支 override 精确对齐）+ 预览 scroll 改有界文本（lines:3）。VM 全链路取证：xiaomis 键盘完整渲染/拼音预览行(n'y_)/候选(那样 能源 纽约 嗯)/选字上屏(那样)全部 PASS；bandpro 功能级 PASS。
- ③v11 动作两处修复：MessageBroker `delete_msg`/`set_msg_emoji_like` 的 message_id 由字符串改数字形态（OneBot v11 规范 int32，NapCat MessageUnique 按 number 索引，字符串 key 查不到=静默失败；safe-integer 外的长串 id 保留字符串兼容 LLOneBot）；direct.js 补 v11 动作直连翻译（send_like/send_poke/group_sign/message_action delete·emoji/get_user_info 六类，eSIM 独立线路特性不再整体失效）+ numericId 安全转换。
- 验证：verify_2160.js 83/83 PASS（kb-dock/preview-box/padding-bottom 分支值逐包核对、flex:1 退役判据、直连 v11 标记、渲染器/演示模式回归）；APP 单测 232/232（双 flavor 各 116，新增 2 例数字 message_id 断言）；手环端 116/117（direct 新增 7 例全绿，api.test.js 1 例存量基线）；双 APK badging vc56/2.16.0/targetSdk 28、胖包五件套+rootfs 在包、EngineManager 新逻辑字节级核实（rootfs-plain/busyboxApplets）。
- VM 通道资产沉淀：scripts/bandqq_vm_drive.sh（装包+演示模式注入+导航驱动）、scripts/vela_vdrag.js（sendMouse 垂直拖拽——本 VM 仅 sendMouse 通道有效，sendTouch 注入无效）。

## v2.17.0（2026-10-03，versionCode 57）—— 引擎启动自愈 + message_id 撤回链全接 + 键盘内层 scroll 显式高 + 渲染器智能化 v2.17
- 用户真机反馈三件：①引擎启动日志 missing curl/git/uv + missing AstrBot/.venv → MANUAL_ENV → exit=1（proot 已能启动=bin/bash 已修好，暴露下一层）②撤回依旧/v11 特性基本用不了 ③键盘依旧半屏（预览已可见）。
- ①根因：启动脚本沿用上游「Environment Manager」设计，预检失败即退（_ASTRBOT_MANUAL_ENV_REQUIRED_），而 BandQQ 无该 UI，脚本 `--step all` 安装能力从未被调用。修=launch_astrbot 预检失败自动补装（install_sudo_curl_git→install_uv→install_napcat→install_astrbot，全幂等），自愈失败才输出 MANUAL_ENV+排查提示；附带摘除 proot /proc/self/fd/0/1/2 三绑定（ProcessBuilder stdin 为管道 → sanitize 失败刷 warning），容器内经 -b /proc + fd:/dev/fd 正常解析；端口就绪探测 10→30 分钟。
- ②message_id 撤回链四处断点全接：handleOneBotEvent 入库带 messageId（v2.13 起丢在入库 → 撤回事件 recallMessage 匹配必败）；buildHistoryFrame 下发 message_id（空 id 省字节）→ 手环撤回/回应菜单恢复；direct.js normalizeHistoryMessage 透传 OneBot 原始 id（number）；app.ux viaDirect 发送成功 bindMessageId 回填本地回显（互联通道由 action_result 帧承担、直连此前无人回填）。
- ③键盘第五轮：rect/pill 字母键区 `<scroll keyboard67/keyboard66>` auto 高度=高度链最后一个 auto 测量点（v2.15 三层显式化未覆盖内层），非手环固件首帧坍缩只渲染动作行（band 固件容忍故正常）。修=两 scroll 显式高 170px（内容 60+55+55）。band 固件行为不变（显式高=原 auto 值）。
- 渲染器智能化 v2.17（双端同规则同步）：QQ红包识别（json wcpay/xml wcpayinfo）；forward 转发摘要（NapCat 节点数组前两条文本，兼容嵌套 content 包裹+递归+CQ 字符串）；file 段大小人类可读；markdown 表格压平（分隔行丢弃）。演示模式 injectDemo 补四形态；手机端 pushTestMessage 新增 redpacket/forward/sign 三场景 + SettingsScreen 按钮行。
- 验证：verify_2170.js 120/120 PASS（四分支+universal；键盘内层 scroll 显式高逐包 170/165、渲染器 v2.17 标记、直连 message_id 链、演示模式新形态、版本 57）；APP 单测 125+125 全绿（双 flavor，新增渲染器 6 例+message_id 链 4 例；注意 Kotlin 反引号函数名禁 `.`、测试断言时间按 normalizeTime 毫秒）；手环端 106/107（新增 8 例全绿，api.test.js 1 例存量基线）；双 APK badging vc57/2.17.0/targetSdk 28、签名 af8819e2 同源；胖包五件套+64MB rootfs+自愈脚本在包、dex 无 fd0 绑定残留。
- 发布：commit+push（Token 保留 remote）→ Release v2.17.0 九资产 → download/bandqq-2.17.0/ 九件（含 README 本版修复说明）。

---
Task ID: 27
Agent: Super Z (main)
Task: band-qq v2.18.0 —— 用户四报修复：AstrBot 环境缺失自愈未部署根修 + NapCat retcode=1200 撤回重试验证 + 键盘 band9 镜像 bottom:0 锚定实证与顶部锚定重做

Work Log:
- 同步：本地 264763e → origin/main 23a18f5（v2.17.0，ff-only；remote URL 补回用户 Token 持久保留）
- 撤回链实证（用户 10-03 20:57 日志）：message_action delete → 数字 message_id → WS delete_msg → NapCat 应答 {"retcode":1200,"message":"Timeout: NTEvent NodeIKernelMsgService/recallMsg EventRet:{result:5}"}——BandQQ 侧四断点已通（v2.17 生效），失败点在协议端；社区检索证实 1200+NTEvent Timeout 为 sendMsg/recallMsg 均有的已知瞬态型问题
- 引擎根修（EngineManager.kt）：升级用户设备上 v2.17 自愈脚本从未部署（isInstalled=true 时安装流程整体跳过=旧脚本常驻容器，日志铁证=旧版英文 "Environment Manager" 提示 + 无"自动补装"进度行 + 无 fd 告警）；新增 refreshContainerScripts()：每次启动前 assets 重写 astrbot-startup.sh/installer-bootstrap/cmd_config.json（内容一致跳写、保留 REINSTALL_PLUGINS_FLAG=1）；start() 前置 installIfNeeded（未装引擎直接启动必败老问题）
- 撤回/v11（MessageBroker.kt）：callOneBot → attemptCall 三次重试（1.2s/2.5s 间隔），delete_msg retcode=1200 先 get_msg 验证（查无此消息=已撤回按成功回帧），终败 toast 透传协议端 retcode；OneBotClient.kt：HTTP 拒连自适应降级（ConnectException 连 3 次→10min WS-only，onResponse/configure() 重置），砍掉每动作两路空转与日志刷屏
- 键盘（VM 四分支实测取证）：重建 QEMU 环境（镜像 581MB 分片下载+lib64 深拷贝修复 setup2 浅拷贝缺陷）；xiaomis/redmiwatch/bandpro 键盘完整，band9（192x490 镜像）实证页面高度解析异常（内容高≈665>视口 490）→ kb-dock absolute bottom:0 锚到内容底，键盘整体下移≈175px、第三排字母腰斩、进度条不可见=用户"只显示一半，输入不展开"；页面顶部锚定四分支截图全精确 → compose.ux kb-dock 改 top=视口高−键盘物理高（band 157 源码基准/bandpro 197/xiaomis 154/redmiwatch 231 由 branch-release.js 构建期写入，摘除 bottom:0）；InputMethod.ux nudgeRelayout()（弹出后 120ms/650ms 两拍 1px 收缩还原强制重排，"输入一下就恢复"自动化，onInit+watch 双路径，onDestroy 清理）
- 修复 A/B 实证：band9 同会话旧包（半键盘）→ 新包（完整胶囊键盘，60px 键 3.2 键/屏原生横滚）全链截图归档 vm-shots/217-*、218-*
- 构建：vc58/2.18.0 四分支 rpk + verify_2180 124/124 PASS（新增 dockTop 四值/不再 bottom 锚定/nudgeRelayout 断言）；node 单测 107 测 106 过（基线 1 不变）；双 APK badging vc58/2.18.0/targetSdk28，bundled 80MB（rootfs+libbusybox+refreshContainerScripts 在包）/companion 12MB（flavor 隔离正确），apksigner af8819e2 同源
- 文档：README v2.18.0 节 + v2.15~v2.17 折叠补录；MEMORY 版本线更新；产物交付 download/bandqq-2.18.0/

Stage Summary:
- 产物：bandqq-{2.18.0-bundled,2.18.0-companion}.apk + bandqq-{band,bandpro,xiaomis,redmiwatch}-2.18.0.rpk
- 待用户回归：①胖包 AstrBot 启动：应出现"启动脚本已与 APK 资产对齐"→"运行环境不完整，自动补装"进度→首次联网装 curl/git/uv/NapCat/AstrBot（数百 MB，数分钟）②撤回：失败自动重试，NapCat 瞬态超时多数自愈；若 toast 报 retcode 请反馈数值 ③键盘：手环/手表端 compose 输入页键盘应完整；若再遇半键盘请说明机型与分支包
- 建议用户：手机 APK 与手环 rpk 需同时更新（手环端 rpk 装对应分支包：RW5=redmiwatch）

---
Task ID: 29
Agent: Super Z (main)
Task: band-qq v2.21.0 —— 用户上传引擎日志（v2.20 实战）分析删除 + 方形键盘第三轮 VM 真复现根治 + NapCat 离线包预下载

Work Log:
- 日志分析：git pull 取用户上传 engine-2026-10-04.log（4 会话 2041 行）→ 会话1-3 为 v2.20 前历史（旧脚本 DNS/sudo/挂死均已修）；会话4（10:25）实证 v2.20 全链生效（sudo 垫片/STAGE 可视化/DNS 重写/镜像竞速），唯一剩余断点=napcat.sh 上游脚本内部自带的 NapCat.Shell.zip 下载（自测代理 ghfast.top，4.5 分钟爬至 1.3% 后 curl(18) Transferred a partial file → exit=1），不受 v2.20 gh_fetch 管控；分析后删除该文件（commit e254add）
- 键盘第三轮（用户反馈"还是没有修好，虚拟机都能看到的"）：重建 Vela VM 环境（vela SDK+miwear 镜像 CDN 重下 581MB；修 libandroid-emu-agents.so 加载=lib64/lib/qemu/resources/bin64 深拷贝进 emulator/ + LD_LIBRARY_PATH；发现平台级规律=模拟器进程随派生工具调用结束被回收→所有 VM 会话必须单次长调用内完成）；compose 入口调试包（manifest router.entry 构建期改 /pages/compose）实现零导航直达键盘页
- VM 真复现（redmiw5 432×514）：v2.20 代码完整复现用户故障——第三排 Z-X-C-V-B-N 拦腰截断+动作行叠进字母区+键盘仅占 190px 下方 95px 全黑；解包 rpk 取证编译产物：#keyboard67 样式表带 position:absolute;top:82px（=v2.19 误判的"固件幻影 82~85px"，实为 62 圆屏时代定位遗产）+ rect 容器 height:274 装不下 261(scroll 预算)+60(动作行)=321（差 47px → 固件把动作行上提叠进第三排）
- 修复（最小两处）：InputMethod.ux #keyboard67 铲除 absolute 定位（height 170→261 对齐内联）+ rect 容器 274→321（高度链闭合：28 拼音行+321=KB_H_RECT 349=dock 同值）；scroll 261 预算保留（真固件内容注入偏移兜底，实测代价=字母行与动作行间 ~90px 空隙，优先保完整不裁行）
- VM 修复验证：redmiwatch 432×514（三排 145..314 完整/动作行 406..465 无叠压/输入 ra→然后 让我想想 让我看看 绕行/下展候选层完整含▲收起）+ bandpro 336×480（三排完整/输入 ea→额阿额阿俄恶）两方形分支全过
- NapCat 预下载：astrbot-startup.sh 新增 ensure_napcat_zip（gh_fetch 多源竞速+20s 断流自杀预取 https://github.com/NapNeko/NapCatQQ/releases/latest/download/NapCat.Shell.zip → $HOME/NapCat.Shell.zip；上游 download_napcat 检测同目录包即跳过内部下载——拉取上游 install.sh 取证该官方支持路径；unzip -t 自验，失败不阻断留上游兜底）；接线 install_napcat（ensure_sudo_shim 之后 bash napcat.sh 之前）
- 版本 vc62/2.21.0 三处同步（manifest/build.gradle.kts/about.ux）；verify_2210 新建 86 项断言（继承 73+新增键盘高度链/absolute 铲除/预下载接线 13 项）；APK 构建环境重建（setup-buildenv.sh 幂等重装 JDK17/Gradle8.13/SDK；新坑=gradle 每次自动补装 platforms;android-37.0 且按 package.xml api-level "37.0" 解析、与 compileSdk=37 的 hash 'android-37' 不匹配报 Failed to find target——元数据手术 api-level 37.0→37 过解析）；双 APK badging vc62/2.21.0 targetSdk28 签名 af8819e2 同源；bundled 含 libbusybox+64MB rootfs+ensure_napcat_zip 脚本
- 发布：commit+push → Release v2.21.0 六资产（scripts/gh_release_2210.py）→ download/bandqq-2.21.0/

Stage Summary:
- 产物：bandqq-{2.21.0-bundled,2.21.0-companion}.apk + bandqq-{band,bandpro,xiaomis,redmiwatch}-2.21.0.rpk
- 待用户回归：①方形分支（Watch 5=redmiwatch 包）键盘三排完整+输入展开+下展候选 ②AstrBot 首装：日志应出现「预下载 NapCat 离线包（多源竞速，20 秒无数据自动换源）」→「上游脚本将跳过其内部下载」，百 MB 级下载不再被上游脚本断流卡死 ③若字母行与动作行间空隙观感过宽（261 预算代价）请反馈截图，可下版收紧
- VM 会话基建沉淀：emu_redmiw5_start.sh / vm_repro_session.sh / vm_expand_session.sh / vm_bandpro_session.sh（单次长调用完成全部驱动，跨调用进程必被回收）

---
Task ID: 31
Agent: Super Z (main)
Task: band-qq v2.22.0 —— 用户第三份引擎日志分析删除 + 键盘第四轮（选择栏置顶）+ NapCat 启动链四件套 + 密码复制/AstrBot 开关

Work Log:
- 日志分析：git pull 取用户第三份 engine-2026-10-04.log（4 会话 3273 行）→ 会话1（01:29）DNS 失败=v2.19 前旧脚本残留属预期；会话2（08:11）实证 v2.19/2.20 全链生效（resolv.conf 重写+getent 自检+6 源竞速 TUNA 胜出 4s 拉 30.9MB+LinuxQQ v9 签名回退）；会话3（10:25）NapCat 上游脚本内部下载断流（v2.20 时代，v2.21 预下载已修）；会话4（12:36）v2.21 全链生效：NapCat 离线包预下载完成+上游跳过内部下载+libnapcat_launcher.so 编译成功+AstrBot v4.28.2 WebUI 成功启动（Initial password 在日志中实证）——唯一断点=「手动探测：本机 NapCat 未就绪（3001/3000 均未监听）」；分析后删除该文件（commit 19c9ffd）
- 根因定案：astrbot-startup.sh v2.21 注释"环境管理的 NapCat 步骤只做安装，不做登录启动…后续从主页账号卡片手动启动"——但该手动启动代码从未实现（全仓库无 start_napcat/launcher 调用）；且脚本写入的 onebot11.json httpServers/websocketServers 均为空数组，即使 NapCat 启动 App 也连不上
- 键盘第四轮（用户反馈"键盘选择栏怎么在下面了，你虚拟机没看到吗"）：rect 动作行（语言/候选选择栏/删除）与字母区换序——动作行回键盘顶部（对齐 circle/pill 分支），高度链 60+261=321 不变/KB_H_RECT 349 不变/下展层 absolute 覆盖不变
- VM 验证历程（坑库大丰收）：①前两轮假象=velasim"纯净镜像" vela_data.bin 烤着旧应用与旧草稿+pm install 同 versionCode 静默失败+残留 bandpro qemu（03:15 起）一直应答 gRPC（redmiw5 冷启动实际全败）②真因链：磁盘 93%（674M）→ Vela 模拟器拒启（Not enough disk space，需 >1.5G）；清 /tmp 1.1G+velasim/dl 555M+android-sync 构建产物 1.3G 后冷启动成功③干净 bandpro 336×480 清装实测：选择栏在顶、输入出字（a's'd→阿啊呵腌嗄；z'x→这些/坐下/执行 整词候选）、字母键全可点④取样方法论：编译产物 keyboard67 首现于样式表段，判 DOM 序必须用 __opts__:{id:"…"}（v2.21 verify 的"动作行归位"断言因此误判；其 (331,393) 下展验证实为误触 B 键）⑤position:relative 不被 VM 固件支持（钉右方案箭头/del 锚到页面级被发送键盖住）→ 已回退钉右、保留换序；胶囊 flex:1 在 336 宽可溢出顶飞 del（v2.19 起真机隐患候选，待真机截图）⑥gRPC 注入对小型 text onclick 不响应（MEMORY 已知）⑦vc62→63 轮换安装规避 pm install 静默失败
- NapCat 启动链四件套（astrbot-startup.sh +124 行）：ensure_napcat_configs（onebot11.json 补 HTTP :3000/WS :3001 服务端（host 127.0.0.1、token 空=与 App 默认 EndpointConfig 一致）+AstrBot 桥 :6199 保留+旧空 server 形状 grep 自动升级；webui.json 固定 port 5099/token bandqq-napcat）+ start_napcat（stage 90 标记；Xvfb :20 720x720；nohup bash launcher.sh→napcat/napcat-console.log；幂等 pgrep）+ start_napcat_watchdog（nohup 循环 5min 巡检 pgrep qq→--step napcat-start 重启，watchdog.log 落盘）+ run_step napcat-start（只启不装）；launch_astrbot 在 uv run main.py 前 start_napcat+watchdog；bash -n 过
- AstrBot 开关（"是否启动安装astrbot选项"）：EngineManager prefs bandqq_astrbot/engine_start_astrbot（默认 true）+ isAstrbotEnabled/setAstrbotEnabled + buildLaunchScript 透传 ASTRBOT_ENABLE=0/1 + 脚本 launch_astrbot 顶部开关门（仅 NapCat 模式：stage 5→按需补装→start_napcat→watchdog→while 常驻）；stop() pkill 补 "qq --no-sandbox"/"Xvfb"；探测细化（6185/5099 也算就绪：botOk→提示等扫码；webuiOnly→提示 WebUI 扫码；区分日志文案）
- 密码复制（"自动读取2个程序的密码给复制"）：engine 模块新增 AstrBotSecrets（AstrBot WebUI 账密=倒序扫 files/logs/engine-*.log 的 Initial username/password 最新值； NapCat Token=读 rootfs/root/napcat/config/webui.json）+ AstrBotScreen「密码与登录」卡（明文展示+复制 ClipboardManager+刷新按钮+"开控制台":6185+" NapCat 扫码":5099 双 WebUiActivity 入口）+「启动 AstrBot 机器人」Switch 卡（关=仅 NapCat 模式提示下次启动生效）
- 版本 vc63/2.22.0 三处同步（manifest.json/build.gradle.kts/about.ux）；verify_2220 新建 82 项断言（修正 DOM 序取样+新增换序/NapCat 启动链/开关/密码卡断言）；node 单测 106/107 基线；APP 单测双 flavor 250/250 全绿；双 APK badging vc63/2.22.0 签名 af8819e2 同源；新坑=EngineManager 标签 return@outer 编译错（for 循环标签只能 break/continue）+gradle 构建前必须杀 qemu（daemon OOM）
- 发布：commit+push → Release v2.22.0 六资产 → download/bandqq-2.22.0/

Stage Summary:
- 产物：bandqq-{2.22.0-bundled,2.22.0-companion}.apk + bandqq-{band,bandpro,xiaomis,redmiwatch}-2.22.0.rpk
- 待用户回归：①方形分支（Watch 5=redmiwatch 包）键盘：候选选择栏应在键盘顶部、输入出字、候选进顶栏 ②AstrBot 标签页：密码与登录卡应显示 AstrBot 控制台密码+ NapCat Token（启动过引擎后）且可复制； NapCat 扫码按钮打开 :5099 ③「启动 AstrBot 机器人」开关关闭后重启引擎：日志应见「仅 NapCat 模式」且不下载 AstrBot ④引擎日志应见 [STAGE:90:启动 NapCat] 与「NapCat 已后台拉起」；QQ 扫码登录后 3001/3000 自动可连
- 已知候选隐患（下版）：胶囊 flex:1 在窄屏溢出顶飞 del/箭头（v2.19 起，VM 实证；真机待截图）；VM position:relative 不支持

---
Task ID: 32
Agent: Super Z (main)
Task: band-qq v2.23.0 —— 第四份引擎日志分析删除 + 键盘第五轮（高度链收紧铲黑区）+ AstrBot cwd 污染根修 + NapCat per-uin 配置升级 + QQ 账号提取

Work Log:
- 日志分析：git pull 取用户第四份 engine-2026-10-04.log（6 会话 3294 行）→ 12:44/15:21 两会话实锤 AstrBot 启动断点 = `uv run main.py` 报 "Failed to spawn: main.py / No such file or directory"+"--no-sync outside of a project"（launch_astrbot 先 cd AstrBot 再调 start_napcat，start_napcat 主 shell `cd "$HOME"` 污染 cwd → uv 在 /root spawn 必败；12:44 v2.21 自愈分支同根因，12:45 重启未走该分支即成功）；15:21 会话同时证明 NapCat 启动链四件套全链生效（onebot11/webui 写入+后台拉起+看门狗）；分析后删除（本轮 commit）
- 键盘第五轮（用户"键盘有点高，下面有块黑色区域"）：rect 字母区实际内容 170px（60+55+55 负 margin），scroll 261 预算死区 91px=黑区根因；高度链收紧 scroll 261→176、容器 321→236、KB_H_RECT 349→264（28+236），branch-release.js kbFinal/KB_H_CONSTS 同步；VM 像素探针实测键带止于 y=456（固件 bottom:0 锚点 469−13 缓冲），与设计值吻合，旧位黑区消灭，剩余 45px=系统手势保留区（真机显示手势条非死黑）
- AstrBot cwd 根修：start_napcat launcher 改子 shell 内 cd（nohup bash -c "cd '$HOME' && exec bash launcher.sh"）；launch_astrbot cd "$INSTALL_DIR" 移到 uv run 前（start_napcat/看门狗之后）+ main.py 缺失前置检查
- NapCat 检测根修（用户"napcat启动成功但apk没有检测到"）：NapCat 登录账号只读 onebot11_<uin>.json，v2.22 只写通用模板对已登录账号无效；ensure_napcat_configs 扫描升级全部 onebot11_*.json（旧空 server 形状→规范配置），start_napcat 配置先行+变更自动重启 NapCat 生效；webui.json 内容一致跳写（返回值 0=有变更/1=无变化）；NAPCAT_OB11_BODY 单引号字面量+__OB_WS_PORT__ 占位符 sed 展开（修 heredoc 变量在单引号体中不展开的隐患）；本地沙盒功能测试（升级/幂等/端口展开/JSON 有效）全过
- QQ 账号提取（用户"怎么没有提取账号"）：AstrBotSecrets.readQqAccounts（首选 onebot11_<uin>.json 文件名提取+mtime 排序，兜底 napcat-console.log 账号/uin/self_id/logged in 行）；「密码与登录」卡新增 QQ 账号行+复制；LaunchedEffect(state) 自动刷新密码卡
- 主页实时日志上移到状态卡之后快捷操作之前（用户上轮反馈日志被沉底）；HomeScreen 动画序号重排
- VM 验证基建大修（坑库）：①SDK 目录 cp 缺子目录（qemu/lib64/lib 未递归拷贝→"can't find the emulator executable"）→ cp -rf 全量；②libandroid-emu-agents.so + LD_LIBRARY_PATH（v2.21 已知坑复现）；③Qt xcb 无显示 → -no-window；④pm install 静默失败三重根因：同 versionCode 降级拒装（v2.22 已知）+ **固件只加载 .jsc 字节码（无 jsc 包 openFile Failed→crash 表盘）** + **nsh 无通配符**；⑤enableJsc=false 默认 → BQ_JSC=1 透传 --enable-jsc（branch-release.js 新增环境变量开关）+ @aiot-toolkit/jsc 补装；⑥adb push 静默丢文件（executeInstall: rpkfile not exist / -100，/tmp tmpfs 每次重启清空）→ push 后 ls 验证；⑦pm 只可靠支持"升级安装"（全新安装/卸载后重装大概率静默失败）→ 镜像内置旧应用+逐级升版本号策略；⑧模拟器进程跨调用存活与 adb 端口复用 → 会话首尾 pkill -9 清场
- VM 实测结论：redmiwatch 432×514（输入 a's→按时/哀伤/阿是 候选进顶栏，三排完整，候选栏置顶）+ bandpro 336×480（q'w→请问/千万/气温）双分支通过；截图+像素探针双证据归档 /tmp/vmkb/
- 版本 vc64/2.23.0 三处同步；verify_2230 新建 100 项断言（继承回归+高度链/cwd 根修/per-uin 升级/QQ 账号/自动刷新/日志上移）；node 106/107 基线；APP 单测双 flavor 250/250；双 APK badging vc64/2.23.0 签名 af8819e2 同源；构建环境重建（setup-buildenv.sh）+ gradle.properties 内存限流（Xmx1792m+workers 1+kotlin 1024m，4GB 机器防 daemon OOM）+ android-37.0 元数据手术复用
- 发布：commit+push → Release v2.23.0 六资产 → download/bandqq-2.23.0/

Stage Summary:
- 产物：bandqq-{2.23.0-bundled,2.23.0-companion}.apk + bandqq-{band,bandpro,xiaomis,redmiwatch}-2.23.0.rpk
- 待用户回归：①方形分支键盘：整体变矮、按键贴近屏底、底部只剩系统手势区；候选栏仍在顶部、输入出字 ②AstrBot 启动：日志应见「AstrBot 启动中」后正常加载 v4.28.2 并打印 Initial password（若 AstrBot 目录缺失会明确提示重启引擎自动补装）③APK 应能检测到已扫码的 NapCat（3001/3000 监听）；密码与登录卡：QQ 账号（扫码后自动出现）/AstrBot 密码/ NapCat Token 三行均可复制且随引擎状态自动刷新 ④主页打开即可见实时日志
- VM 会话脚本沉淀：vm_kb_session2.sh / vm_final_session.sh / vm_diag*.sh / build_vm_rpk.sh / build_debug_entry.sh / kb_measure.py（scripts/）

---
Task ID: 33
Agent: Super Z (main)
Task: band-qq v2.24.0 —— 第五份日志分析删除 + 发送链根修(params-only) + NapCat 状态/日志/配置三联动 + 一键填入热重连 + 撤回闭环补完

Work Log:
- 日志分析：git pull 取用户第五份 engine-2026-10-04.log（9 会话 3364 行）+bandqq-2026-10-04.log（415 行）→ 引擎侧 v2.23 全链实证已通（DNS 竞速/多源 apt/uv/LinuxQQ/NapCat/AstrBot 12:45 与 18:35 两次 WebUI ready、仅 NapCat 模式开关生效）；APK 侧 18:40 会话实锤唯一断点=send_group_msg retcode=200 "TypeError: Cannot read properties of undefined (reading 'type')"@aye napcat.mjs:74013（WS 3001 已连、get_group_list/get_friend_list ok、self_id=2308534727 换号登录成功）；旧会话 01:29/08:11 的 WS 拒连 mysv.dpdns.org:12050 为旧版遗留地址（v2.23 已切本地）；分析后删除+commit 9cdae8e
- 发送链根修（上轮工作树遗留 v2.24 改动收尾）：NapCat 4.18.28 HTTP httpApiRequest 把整个 body 当 params（action 从路径取），旧信封 {action,params} → body.message=undefined → aye() 段校验崩；修=OneBotParser.buildSendParams params-only 体（与 direct.js 直连同形状），WS 通道仍信封；sendMessage judge() 三态（HTTP 200 但 retcode!=0=业务失败，不支持的Api=换通道）；MessageBroker 发送三态（成功才入库+回推手环 action_result(send,ok)，失败回 ok=false+原因给手环 toast——修"假成功"）
- NapCat 状态联动：①脚本 wait_napcat_ports（/dev/tcp 探测 3001/3000，NapCat-only 模式前台出 stage 100 终态+每 30s 进度行，AstrBot 模式后台只打日志）②EngineManager stage≥100→Running（不再停"安装中 100%"）③新增 main sourceSet EngineHooks（astrbot-engine 仅 bundledImplementation 可见，main 不可 import）+SyncService 包装 OneBotListener（onState(true)→EngineManager.onNapcatConnected() WS onOpen 即推进状态）④bundled AstrBotScreen 首帧挂钩+状态卡新增"●/○ 手环直连"行（OneBotStateBus 驱动，区分容器在跑 vs QQ 登录后端口可连）
- NapCat 日志接入：①脚本 napcat_console_tap（tail -F napcat-console.log 剥 ANSI/截 160/[NAPCAT] 前缀回写引擎 stdout，pkill/重入/SIGPIPE 三重收尾）②EngineManager.logSink 注入点+logLine 全量转发 ③AstrBotScreen LaunchedEffect 挂 sink→LogBus→主页实时日志面板
- 一键填入热重连：根因=configure() 只换引用不重连（运行中 WS 挂旧地址）；修=OneBotClient.reconnectWith（关旧 WS→重连循环用新端点重建）+SyncService.reconnectEndpointNow 静态钩子+bundled「一键填入本机 NapCat 地址并保存」与设置页「保存」两处接入；companion 伴侣模式同路径受益
- 看门狗升级「保活+配置升级」双职责：旧逻辑只 pgrep 进程——QQ 扫码登录后新生成 onebot11_<uin>.json 默认无端口服务端→进程活着端口永不监听的慢性死角；修=每轮无条件 --step napcat-start（死→拉起/配置升级→重启生效/一致→跳过 no-op）
- 撤回闭环补完：MessageStore.recallByMessageId（全会话 message_id 反查灰显，新 API）+attemptCall delete_msg retcode=0 后 buildRecallFrame+会话帧回推（旧链路手环发起撤回只回 toast，聊天页永不灰显）
- 构建链恢复：setup-buildenv.sh 重建（JDK17/Gradle 8.13/android-37.0 元数据手术复用）+npm 走 npmmirror（默认 registry 静默失败）；单测修 6 处旧两参 callback 编译错+2 处 params-only 断言更新；node 106/107 基线；APP 双 flavor 250/250 全绿；双 APK badging vc65/2.24.0 签名 af8819e2 同源（bundled 80.3MB/companion 12.2MB）；四分支 rpk manifest/键盘高度链常量（176/236/264）验证过；坑复验：nohup+disown 仍被工具调用回收，setsid 同灭——gradle 必须单次长调用直跑
- 发布：MEMORY/worklog 更新 → commit+push → Release v2.24.0 六资产 → dist/v2.24.0/

Stage Summary:
- 产物：bandqq-{2.24.0-bundled,2.24.0-companion}.apk + bandqq-{band,bandpro,xiaomis,redmiwatch}-2.24.0.rpk
- 待用户回归：①手环发消息（v2.24 应真成功；失败会 toast 协议端真实原因）②NapCat 启动后状态卡：进度到 100% 显示"启动完成/运行中"+「● 手环直连」行随 QQ 扫码登录变主色 ③主页实时日志应出现 [NAPCAT] 前缀行（QQ 登录/WS 监听动态）④AstrBot 标签页「一键填入本机 NapCat 地址并保存」点击后立即重连（无需重启 App）⑤长按消息撤回成功后手环聊天页原位灰显

---
Task ID: 34
Agent: Super Z (main)
Task: GitHub Actions CI 补齐 —— 用户指出「哪里有 ci，都没有写过工作流文件」，补建工作流使构建可云端复现

Work Log:
- 根因确认：仓库从未有 .github 目录，历史 APK/rpk 全部本地构建，无 CI；README 亦无 CI 徽章
- 构建体系盘点：APK=android-sync（AGP 8.13.2/Kotlin 2.4.10/Gradle 8.13/JDK17/compileSdk 37，签名硬编码 root keystore.jks 已入库）；rpk=band-qq（node tools/branch-release.js 一次出四分支，签名 sign/release pem 已入库）——CI 全程无需 secrets
- 新增 .github/workflows/ci.yml：push main/PR/手动三触发；rpk job（node22+npm ci+npm test+branch-release 四分支+artifact）；apk job（temurin17+setup-android+SDK 37.0 目录陷阱手术[platforms;android-37.0→android-37 改名+package.xml/source.properties 补丁，复刻 setup-buildenv.sh 第 5 步]+setup-gradle 8.13+gradle test assembleBundledDebug assembleCompanionDebug+失败传测试报告）；并发组防跳车
- 新增 .github/workflows/release.yml：tag v* 触发（手动 dispatch 只出包不发版）；tag 与 versionName 一致性校验防发错版本；gradle test assembleBundledRelease assembleCompanionRelease+npm ci+四分支 rpk；softprops/action-gh-release@v2 发 6 资产（2 APK+4 rpk）+自动 release notes
- 不提交 gradlew：环境已重置（/home/z/tools 丢失），CI 用 setup-gradle 指定 8.13 等效替代，避免为 wrapper 重下 120MB 发行包
- README 加 CI workflow 徽章；gradle.properties workers.max=1 是本地低配保守值，CI 命令行 --max-workers=2 覆盖提速

Stage Summary:
- 产物：.github/workflows/{ci,release}.yml + README CI 徽章；推送后 push main 首跑自动验证（rpk 分钟级/APK 约 10 分钟）
- 待回归：①观察 CI 首跑（尤其 android-37 手术与 aiot-toolkit on node22）②发版改用 git tag vX.Y.Z 一键触发

---
Task ID: 34 (续)
Agent: Super Z (main)
Task: CI 十一轮攻坚定案——AGP 8.13.2 vs API-37.0 打包格式代差；迁移 AGP 9.0.1 + Gradle 9.1.0 根治（v2.25.1/vc67）

Work Log:
- 二跑：rpk 单测 107/107（api.js send 门控真修）；APK 挂 Failed to find target 'android-37'
- 三~五跑：setup-android@v3 license 卡死→弃用；锁版 11076708 cmdline-tools→其读的老目录 repository2-1.xml 已无 android-37.* 包（装包静默失败）；镜像预装 android-37 元数据未修（Google zip 自带笔误 api-level 37.0/Platform.Version=17）
- 本地复现链：env 重置后重建工具链→复现同错→定位 package.xml `<api-level>37.0</api-level>` 为根因之一→补丁后本地 e2e 全绿
- 六~九跑：注册表逐字节一致仍挂→挖 --info 实锤 AGP 自动装了 build-tools 35（AGP 8.13.2 默认 BT）→本地验证 DirectLoading（只读 source.properties）vs FullLoading（package.xml 注册表）双策略分流：本地全绿=BT35 已存时 Direct 接管；CI 无 BT35 → Full → 死路（Google API-37 元数据在 Full 路径必挂 + cmdline-tools legacy 包 displayName null NPE）
- 十跑：预装 BT35 仍挂→daemon JVM 指向镜像自带 temurin-17（后证与本地同为 17.0.20.1+1，排除 JDK 因素）
- 终极定案：AGP 8.13.2 sdklib 整代不认 minor 版本 API 打包，绕不过→升 AGP 9.0.1（要求 Gradle ≥9.1.0）
- AGP 9 迁移：移除 org.jetbrains.kotlin.android（内置 Kotlin，保留 plugin.compose）+ 移除 applicationVariants 旧 API（产物改名移至 CI 收集阶段）+ manifest 禁 uses-sdk 版本属性（tools:overrideLibrary 保留）+ compileSdk=37 + compileSdkMinor=0 → **直接命中原生 platforms;android-37.0，全部元数据手术作废**
- 版本 bump 2.25.1/vc67；wrapper→9.1.0；setup-buildenv.sh v3 极简配方；删 .github/sdk 内嵌注册表；MEMORY.md 构建配方更新
- 验证：本地 Gradle 9.1.0 全矩阵（250/250 单测+双 flavor debug/release）全绿于 /tmp/cisdk2 原生 SDK

Stage Summary:
- 产物：v2.25.1 CI 全绿工作流（ci/release）+ AGP9 迁移后的构建链 + api.js 门控真修 + nSecondary 残缺修复
- 待回归：CI push 全绿后打 tag v2.25.1 触发 release.yml 自动发版（六资产）

---
Task ID: 22
Agent: main (Super Z)
Task: band-qq v2.26.0 —— NapCat Worker SIGSEGV 崩溃循环终结（用户第七份日志定案：四级自愈阶梯 + NapCat 版本钉扎 4.18.28 + 看门狗主进程判定根修 + ELECTRON_RUN_AS_NODE 反检测回退链 + fd9 引擎管道）

Work Log:
- 用户"还是不不行呀"+ 第七份 engine-2026-10-05.log（436 行）分析：①v2.25.0 清缓存后全新启动仍 Worker SIGSEGV×3→主进程退出（脏缓存定案不完整）②看门狗 07:22:06 宣布 5 分钟重启，07:28:32 用户手停仍无动作③反检测 node 不可用×2
- 上游考据：NapCat issue #1626 同症状（更新后 Worker 退出码 11/139×3，官方规避 NAPCAT_DISABLE_BYPASS=1）；base.ts v4.18.28/29 逐字节相同且 enableAllBypasses 默认启用（日志"prepare write and writev hooks"即其写钩子，proot 容器环境触发段错误）；4.18.29 发布于 10-04 20:30，容器 releases/latest 静默漂移拉到新版
- astrbot-startup.sh 六处修改（+180/-23）：四级自愈阶梯（L1 清缓存→L2 NAPCAT_DISABLE_BYPASS=1 持久化标记→L3 重装钉扎版 NapCat（配置备份+自愈标记摘存）→L4 深度重置 .config/QQ）；NAPCAT_SHELL_URL 钉扎 v4.18.28（env 可覆盖）；qq_main_alive（/proc cmdline 无 --type= 才算主进程）+ 崩溃判定优先于"已在运行"跳过 + kill 模式补 /opt/QQ/qq + 巡检 300→120s + 端口监听计数清零；napcat_node_run 回退链（node→nodejs→QQ Electron ELECTRON_RUN_AS_NODE=1）+ antidetect JS 落临时文件 argv[2]||argv[1] 双兼容；fd9 捕获引擎管道（守卫防看门狗重入覆盖）+ tap 写 >&9；wait_napcat_ports 成功即 mark_healthy
- EngineManager 停止链：TERM 窗口 4s→8s + 模式补 /opt/QQ/qq；AstrBotScreen 反检测说明行同步自愈语义；版本 2.26.0/vc68
- 验证：bash -n 过；功能测试 15/15 PASS（scripts/test-v2260-napcat.sh：JS 首写/幂等/坏 JSON/自定义键/-e 兼容；heal_count 读取/容错；fd9 关闭捕获/继承不覆盖；qq_main_alive 主进程识别/仅子进程判死）；badging vc68/2.26.0
- 用户第七份日志分析完毕后删除；README 更新日志 v2.26.0 + 2.24→2.25.1 摘要桥接；MEMORY 版本线 + 历史条目

Stage Summary:
- 产物：bandqq bundled/companion 2.26.0(vc68) 双 APK
- 回归建议：①装新 APK 启动引擎——若 NapCat 仍崩，看门狗 2 分钟内自动升级自愈级别（日志可见"自愈第 N 级"），L2 起禁用 bypass 反检测钩子保启动 ②崩溃循环最多 4 级（最后一级重置 QQ 数据需重新扫码）③反检测在无 node 容器内现在真正可自动写入 ④自愈重启后 App 日志面板 NapCat 日志不断流
- 教训入库：releases/latest 是不确定性来源（基础设施下载一律钉扎）；"看门狗宣布重启"≠"重启逻辑可达"（跳过分支必须排在崩溃处理之后）；无 node 容器的 JS 需求用 QQ 自带 Electron 做 ELECTRON_RUN_AS_NODE 回退；MultiEdit 非原子性（失败重跑须 rg 计数去重）
