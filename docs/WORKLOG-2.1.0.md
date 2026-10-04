# band-qq 项目工作日志（跨会话共享）

## 项目概述
- band-qq：小米手环9 Vela 快应用（手环QQ客户端）+ Android 同步器 APK
- 链路：手环 ⇄ 蓝牙 interconnect ⇄ Android 同步器 ⇄ OneBot WS(:3001)/HTTP(:3000)
- 上游基线：https://github.com/Astroptis/band-qq-assistant（main 分支，无 stapxs 移植特性）
- 小号仓库：https://github.com/Gsjsjzhznsz/BandQQ（PAT: ghp_**REDACTED**）
- 签名 SHA-256：af8819e27a6ec8d84537ec86937cf016e780376305328abaa4eb79e8c626b004
- 构建配方：AGP 8.13.2 + Gradle 8.13 + Temurin JDK 17 + compileSdk 37（目录陷阱：android-37.0 需处理）+ aiot-toolkit 2.0.5
- 历史归档：/home/z/my-project/memory/memory_page.txt（v1.1.1 前全部工作记录）

## 用户本轮新需求（最新消息，优先级从高到低）
1. 手环端图标有白边 → 重制图标（全出血、处理 alpha）
2. 菜单还是抖动，原因是数据刷新 → 刷新路径根因修复
3. 优化性能：不影响体验的处理全放手机 APP 端（P0，手环重启死机根因）
4. APP 未使用 miuix 设计 → 参考 com.sevtinge.hyperceiler (HyperCeiler) 重构
5. 能否集成 SnowLuma 在 APP（待调研确认是什么）

## 历史遗留需求
- 手机离线缓冲：快应用关闭 → 停 interconnect，OneBot 保持在线，消息落盘，冷启动回放
- 快捷回复按钮显示原始 CQ 码 → 手机端解析剥离
- Stapxs-QQ-Lite-X 调研：数据管理 + 多账号管理
- 无障碍服务易用性改进；字库 ~20902 CJK 全量；before_time 修正；rpk 图标 manifest
- README 上游说明 + SEO

---
Task ID: 1
Agent: 主控 (Super Z)
Task: 容器第4次重置后恢复上下文

Work Log:
- 发现容器重置：/opt/jdk、/opt/android-sdk、band-qq-upstream、worklog 全部丢失
- 重新克隆 https://github.com/Astroptis/band-qq-assistant 至 /home/z/band-qq-upstream（成功）
- 用 page_reader + agent-browser 渲染 SPA 分享页，提取上一会话完整工作记录（31026 字节）归档至 memory/memory_page.txt
- 关键确认：上游 main 是 opencode 迭代版基线（无快捷回复/翻页/未读角标/彩色头像），策略=以此基线重做全部功能

Stage Summary:
- 上下文恢复完成，进入调研+代码修复阶段
