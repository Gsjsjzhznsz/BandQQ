# MEMORY.md — 跨会话项目记忆（AI 协作恢复上下文用）

> 本文件是 band-qq 项目的持久化记忆。AI 助手在新会话中克隆本仓库后，应先读本文件 + `docs/` 恢复全部上下文。

## 项目定位
小米手环9 Vela 快应用（手环QQ客户端）+ Android 同步器 APK。
链路：手环 ⇄ 蓝牙 interconnect(xms-wearable-lib) ⇄ 同步器App ⇄ OneBot v11(WS:3001/HTTP:3000) ⇄ SnowLuma(协议端, hook型, WebUI:5099) ⇄ QQ。
上游：https://github.com/Astroptis/band-qq-assistant ；本仓库为完整版镜像（含签名/产物/legacy）。

## 版本线
- v1.1.x：旧 lineage（stapxs 移植版，源码已失传，legacy/ 有 7z 分卷）
- v2.x：基于 Astroptis main（opencode 基线）重做。**当前 v2.4.0**（versionCode 24）
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
