
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
