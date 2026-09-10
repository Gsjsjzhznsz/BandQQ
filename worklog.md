
---
Task ID: 3
Agent: main (Super Z)
Task: v2.2.0 —— 手环图标深色背景 + MIUIx 液态玻璃/悬浮栏（用户反馈第二轮）

Work Log:
- 读取用户上传的 Gsjsjzhznsz/BandQQ MEMORY.md，确认真正基线是本仓库 v2.1.0（versionCode 21，含 stapxs 特性重做/性能 v2/SnowLuma 调研定论），此前在 Astroptis 旧基线上的工作按此对齐
- keystore.jks 与 rpk pem 均已由用户回传仓库，签名指纹 af8819e2... 验证一致（v2.2.0 APK 实测 V2 signer SHA-256 匹配，覆盖安装兼容）
- 手环图标：icon_preview.png（用户上传的白底企鹅 logo）flood-fill 换深底 #0D1015 → src/common/icon.png 108×108 RGB；rpk 解包验证四角纯深色无 alpha
- Android：深色自适应图标（mipmap-anydpi-v26 + xxxhdpi 前景 PNG + radial 深色渐变背景 vector）+ manifest icon 声明；BandQQApp 重构为液态玻璃悬浮栏（miuix-blur drawBackdrop+blur+colorControls+BloomStroke，Android<13 回退 FloatingNavigationBar）；4 Screen 底部 padding 96dp 穿透
- 新依赖 miuix-blur-android:0.9.3；manifest uses-sdk tools:overrideLibrary（application 标签上无效，第一次尝试失败学到的）
- 容器会话中途再次重置 → scripts/setup-buildenv.sh 按记忆配方一键重建成功（配方可靠性得到验证）
- 版本线：Android + 手环 manifest 均升至 2.2.0 / versionCode 22；产物 bandqq-watch-2.2.0.rpk + bandqq-sync-release-2.2.0.apk 交付 download/

---
Task ID: 4
Agent: main (Super Z)
Task: v2.3.0 —— 渲染问题修复 + KernelSU 同款主题设置（用户反馈：渲染异常/与 KernelSU 不一致）

Work Log:
- 稀疏克隆 tiann/KernelSU 调研：确认其 manager 用同一套 miuix 0.9.3（ui/icons/navigation3/preference/blur 全家桶），主题实现 = miuix ThemeController + ColorSchemeMode 六模式 + LaunchedEffect 联动状态栏图标；其液态玻璃底栏 = 自写 944 行 liquid 组件（vibrancy/lens/innerShadow 均非 miuix API，此前误判为 miuix 超前 API）
- 渲染差异根因定位（两个）：① 旧 BandQQTheme 裸调 MiuixTheme(content) 走 colors 默认值重载 → 永远浅色、不跟随系统深色、状态栏图标颜色错误；② 液态玻璃 backdrop 采集层未垫底色（背景画在登记层外）→ 模糊采样透明像素 → 底栏发黑/花屏
- Theme.kt 重写：ThemeMode 六模式枚举 + ThemeController(colorSchemeMode, isDark) + Monet 模式（Android 13+ 读系统色板，12+ 系统 Md3 角色，<12 回退 miuix 蓝种子色）+ WindowInsetsControllerCompat 同步状态栏/导航栏图标明暗
- BandQQApp 修复：backdrop 改 rememberLayerBackdrop { drawRect(background); drawContent() }（对齐 KernelSU rememberBlurBackdrop）；玻璃栏配方整体换为 KernelSU 同款 textureBlur(blurRadius=25f, surface 87% blend)，弃用自试的 drawBackdrop+colorControls+BloomStroke
- 新增「主题与外观」设置区块（miuix-preference ArrowPreference + SwitchPreference + WindowDialog 单选 RadioButton 列表），build.gradle.kts 补回丢失的 miuix-preference-android:0.9.3 依赖（v2.x 重写时遗漏）
- MainActivity enableEdgeToEdge（SmallTopAppBar 自带窗口 inset 内边距已验证）+ DataStore Flow 直驱全局主题即时生效；values/values-night 窗口背景防深色冷启动闪白；manifest 重复 xmlns:tools 修复
- 构建 v2.3.0（versionCode 23）成功，aapt2/apksigner 验证：SHA-256 af8819e2... 与历史版本同源，覆盖安装兼容

Stage Summary:
- 产物：download/bandqq-sync-release-2.3.0.apk（~12MB）
- 关键知识入库 MEMORY.md：KernelSU 主题配方（ThemeController）、backdrop 垫底色铁律、textureBlur 配方、miuix-blur 所有效果均门控 isRuntimeShaderSupported（13+）
