# MEMORY.md — 跨会话项目记忆（AI 协作恢复上下文用）

> 本文件是 band-qq 项目的持久化记忆。AI 助手在新会话中克隆本仓库后，应先读本文件 + `docs/` 恢复全部上下文。

## 项目定位
小米手环9 Vela 快应用（手环QQ客户端）+ Android 同步器 APK。
链路：手环 ⇄ 蓝牙 interconnect(xms-wearable-lib) ⇄ 同步器App ⇄ OneBot v11(WS:3001/HTTP:3000) ⇄ SnowLuma(协议端, hook型, WebUI:5099) ⇄ QQ。
上游：https://github.com/Astroptis/band-qq-assistant ；本仓库为完整版镜像（含签名/产物/legacy）。

## 版本线
- v1.1.x：旧 lineage（stapxs 移植版，源码已失传，legacy/ 有 7z 分卷）
- v2.x：基于 Astroptis main（opencode 基线）重做。**当前 v2.8.2**（versionCode 38，RPK versionCode 35 无改动）+ DevTools 1.2.0（vc3）
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
- 真实手环固件的 emoji 字形覆盖未验证：v2.6.0 默认透传 emoji，若真机显示方框，手机端设置页「表情原生渲染」关闭即降级（无需重装）；
- aiot gRPC sendMouse 触摸注入时序敏感：App 未完全就绪时点击无效，等页面稳定后再点（vvd-ctl.js 已加重试节奏）

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
- APP：ui/AboutScreen.kt（推入页同构 CrashLogScreen：Scaffold+BlurredBar+SmallTopAppBar；图标+版本 BuildConfig.VERSION_NAME（app buildFeatures 开 buildConfig=true，AGP8 默认关）+ 作者一秋/QQ 2308534727 点击复制/仓库 Gsjsjzhznsz/BandQQ 点击开浏览器/项目简介/双按钮）；BandQQApp showAbout rememberSaveable 推入 + PredictiveBackHandler 优先级链插入
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
