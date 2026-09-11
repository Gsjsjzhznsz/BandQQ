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
- 关于页：APP AboutScreen.kt（版本/作者一秋/QQ2308534727复制/仓库跳转/简介）+ BandQQApp showAbout 推入；RPK pages/about/about.ux + manifest 注册 + settings.ux 关于行；buildConfig=true 开启（AGP8）
- 构建：APK vc36/2.8.0 + devtools vc1/1.0.0 验签同源（af8819e2）+ RPK 2.8.0/vc34（包内验证新协议/页面齐全）；node 单测 49/49 过（protocol/store），api.test.js 与 GameProtocolDetectorTest 各 1 例存量环境失败（基线一致）
- 交付 download/ 三件产物；README/MEMORY 更新

Stage Summary:
- 产物：bandqq-sync-release-2.8.0.apk + bandqq-devtools-release-1.0.0.apk + bandqq-watch-release-2.8.0.rpk
- 回归建议：①装 DevTools→启动服务器→APP 默认地址直连→点模拟按钮消息到手环 ②手环回复→DevTools 日志+自动回推对方消息 ③手环设置页改「消息震动」→APP 设置页状态同步 ④开启自动拉起→拉起后手环短震×2 ⑤双端关于页信息完整
