<div align="center">

# 💬 BandQQ 腕间信使：低轨孤星

**小米手环 / Redmi Watch / 小米手表 上的 QQ 消息助手 · Vela 快应用 + Android 同步器双端方案**

[![Version](https://img.shields.io/badge/version-2.13.0-blue)](https://github.com/Gsjsjzhznsz/BandQQ/releases)
[![Platform](https://img.shields.io/badge/platform-Android%20%2B%20Vela-green)]()
[![Devices](https://img.shields.io/badge/devices-Band%208~11%20%7C%20Watch%20S%20%7C%20Redmi%20Watch-orange)]()
[![License](https://img.shields.io/badge/license-AGPL--3.0-red)](LICENSE)
[![Release](https://img.shields.io/badge/download-GitHub%20Releases-9cf)](https://github.com/Gsjsjzhznsz/BandQQ/releases)
[![OneBot](https://img.shields.io/badge/protocol-OneBot%20v11-0099ff)](https://github.com/botuniverse/onebot-11)
[![CI](https://img.shields.io/github/actions/workflow/status/Gsjsjzhznsz/BandQQ/ci.yml?label=CI&logo=githubactions&logoColor=white)](https://github.com/Gsjsjzhznsz/BandQQ/actions/workflows/ci.yml)

![BandQQ 宣传图](docs/promo/banner-3x2.jpg)

**手环上直接看 QQ、回 QQ、翻历史、收图。**
手环端运行 Vela 快应用（rpk），手机端运行安卓同步器（APK），经小米互联蓝牙通道实时同步，
协议端对接 OneBot v11（NapCat / Lagrange / LLOneBot / go-cqhttp 均可）。

</div>

---

## 📱 设备分支（v2.10.0 起单 main 分支 + 构建期出包）

单一源码树经 `band-qq/tools/branch-release.js` 构建期生成各系列独立适配包——
designWidth 对准物理屏宽、样式按视觉密度系数换算、**键盘全分支统一 NEORUAA/Vela_input_method 单组件按屏形适配**（v2.13.0）、圆屏安全边距，杜绝 git 多分支漂移：

| 分支包 | 适配设备 | 屏 | designWidth | 键盘（NEORUAA 单组件 · v2.13.0） |
|---|---|---|---|---|
| `bandqq-band` | 小米手环 8 / 9 / 10 / 11 | 192×490 胶囊 | 192 | pill-shaped 弧形屏布局（192 原生） |
| `bandqq-bandpro` | 手环 8Pro / 9Pro / 10Pro | 336×480 方屏 | 336 | rect 方屏布局（336 原生标尺） |
| `bandqq-redmiwatch` | Redmi Watch 5 / 5 eSIM / 6 | 432×514 方屏 | 432 | rect 方屏布局（宽自适应，零换算） |
| `bandqq-xiaomis` | Xiaomi Watch S3 / S4 / S5 | 466×466 圆屏 | 466 | circle 圆屏 QWERTY（480 标尺 ×466/480 换算） |
| `bandqq-universal` | 通用兜底 | 192 | 192 | 同 band |

四分支共用同一组件（内置 circle/rect/pill-shaped 三屏形布局，QWERTY + T9 双键盘，中/英/日三语），共享**全量拼音字库（419 音节 27398 字，频序候选）** + 上游词条分片词库。

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
- **分支适配拼音键盘（NEORUAA 单组件）**：胶囊 / 方屏 / 圆屏三屏形布局，QWERTY + T9 双键盘、中/英/日三语，全量拼音字库 27398 字（生僻字「燚 龘 齉」均可打），候选按常用度排序
- 快捷回复：9 条常用语一键发送（支持 CQ 码、二次确认防误触）
- 历史消息**分页窗口化**：点一次「加载更早」只前移一个窗口（零网络/零卡顿），DOM 恒 ≤ 渲染上限，另有「回到最新」按钮

### OneBot v11 扩展动作（v2.13.0）
- **点赞**（send_like）与**主动拍一拍**（friend_poke）：手环长按会话菜单直达
- **群签到**（send_group_sign）：群会话长按菜单（依协议端支持）
- **表情回应**（set_msg_emoji_like）：聊天页点气泡弹菜单，六大常用回应一键贴
- **撤回自己消息**（delete_msg）：发送应答自动回填 message_id，气泡菜单一键撤回
- **资料查询**（get_stranger_info / get_group_member_info）：私聊查对方、群聊查发言者
- 动作结果实时回推手环（action_result 帧 toast），DevTools 同步提供全部新动作模拟

### eSIM / 独立线路
- **eSIM 独立直连**（参考 merqury-vela）：手表经 @system.fetch 直连 OneBot HTTP API + Bearer token，互联断开自动切换，手机端连接时零手输下发配置
- **AstrBot 本地机器人双包**（v2.12.0 伴侣 / v2.13.0 内嵌）：
  - **瘦包（伴侣版）**：检测/拉起独立的 [AstrBot Bubble](https://github.com/MuFengDR/AstrBot-Bubble-Android-App)，本机 NapCat 一键快连 127.0.0.1
  - **胖包（内嵌引擎版）**：AstrBot Bubble 引擎层（proot + Ubuntu rootfs + 一体化启动脚本）随 APK 内置，点「启动引擎」即得本机 OneBot 服务，开箱即用

### 可靠性
- OneBot WS(:3001) 优先、HTTP(:3000) 自动回退，API 全部走 echo 路由（WS-only 部署也能拉名单、发消息）
- 手机端前台服务 + 无障碍保活锚点 + 后台保活向导；消息落盘 300ms 防抖合并写
- 双端设置互通（表情渲染 / 震动 / 免打扰），夜间勿扰、群聊推送范围手机端判定，手环零开销
- BandQQ DevTools（v1.4.0）：与主 APK 同风格的 miuix 调试器，内置 OneBot 模拟器全动作覆盖

## 📥 安装（GitHub Releases 分发）

到 [**Releases**](https://github.com/Gsjsjzhznsz/BandQQ/releases) 下载对应文件：

1. **手环端**：下载你设备对应的 `bandqq-<分支>-2.13.0.rpk`，经 Vela 快应用开发者模式 / 手环调试助手安装；关于页可核对版本与分支后缀
2. **手机端**（双包二选一，签名同源覆盖安装）：
   - `bandqq-2.13.0-companion.apk` — **瘦包/伴侣版**（约 12MB）：不含引擎，需另装 AstrBot Bubble 或外接协议端
   - `bandqq-2.13.0-bundled.apk` — **胖包/内嵌引擎版**（约 80MB）：内置 AstrBot + NapCat + Ubuntu 容器，设置页一键启动本地引擎
3. **协议端**：NapCat 等开启 WS :3001（推荐）/ HTTP :3000，在同步器设置页填地址与 token，「测试连接」验证
4. 手机与手环经小米运动健康保持连接，打开手环端 QQ 助手即可使用

> 没有协议端？先装 [BandQQ DevTools](https://github.com/Gsjsjzhznsz/BandQQ/releases)（独立调试 APK v1.4.0，内置 OneBot 模拟器）即可完整体验全链路。

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
band-qq/          手环端 Vela 快应用源码（aiot-toolkit 构建；tools/branch-release.js 分支出包）
  src/components/InputMethod/   NEORUAA/Vela_input_method 键盘组件（三屏形单组件）
  scripts/merge_dict_neoruaa.py 全量字库合入 NEORUAA 词库（27398 字）
android-sync/     安卓同步器源码（Kotlin + Compose + miuix）
  app/            主模块（productFlavors：companion 瘦包 / bundled 胖包）
  astrbot-engine/ 胖包内嵌 AstrBot 引擎（proot 套件 + Ubuntu rootfs + 启动脚本）
devtools/         BandQQ DevTools 调试器源码（v1.4.0 miuix 风格 OneBot 模拟器）
scripts/          构建脚本（build-rpk.sh / build-apk.sh / setup-buildenv.sh 等）
docs/             NapCat 配置、签名说明等文档
legacy/           历史归档（v1.1.0 源码包、旧变体快照、全量字库出处）
```

## 🛠️ 从源码构建

```bash
# 手环端 rpk（四分支一次全出，或单分支指定）
cd band-qq && node tools/branch-release.js          # band / bandpro / xiaomis / redmiwatch

# Android 双包（瘦包伴侣版 + 胖包内嵌引擎版）
bash scripts/setup-buildenv.sh                       # JDK17 + Gradle 8.13 + SDK 一键重建
cd android-sync && JAVA_HOME=/path/to/jdk-17 \
  gradle assembleCompanionRelease assembleBundledRelease -x lint
# 产物：app/build/outputs/apk/{companion,bundled}/release/bandqq-2.13.0-{companion,bundled}.apk

# DevTools（独立调试器）
gradle :devtools:assembleRelease
```

构建说明见 `scripts/README.md`；字库再生成：`python3 band-qq/scripts/merge_dict_neoruaa.py`（BandQQ 全量字库合入 NEORUAA cn.txt，频序拱顶保留）。

## 📦 更新日志

### v2.28.1（当前版本 · vc72）

> NapCat 更新链路修复（第十份引擎日志定案：用户在 NapCat WebUI 点「更新到 v4.18.30」，下载/解压成功但整链被吞——**四层根因**：① WebUI 自更新动了安装文件，`launcher.sh` 消失，看门狗重启链全灭（`bash: launcher.sh: No such file or directory`）② 引擎重启后 `check_napcat_ready` 失败原因被 `>/dev/null` 吞掉，仅缺 launcher 的完好安装被误判「未安装」→ `rm -rf` 整个框架（刚更新好的 4.18.30 一并删除）③ 全量重装链在上游脚本无条件 `apt-get update` 处被 SIGKILL（`7397 Killed bash napcat.sh`，引擎 exit=137）④ 版本钉扎强制执行永远对抗用户主动更新——旧设计下 WebUI 更新永远不可能成功。**修=四件**：**① WebUI 更新采纳机制**：检测到已装版本 > 基线（`package.json` 读取 + 纯 bash 三段版本比较）自动采纳为新钉扎基线（`.pinned_version` 落盘、跨会话生效、下载 URL 同步）；显式 `NAPCAT_SHELL_VERSION` 环境变量钉扎时不采纳（运维指定优先）；采纳版连崩自愈 L3 自动回退已知可用基线 4.18.28（清采纳标记+删离线包防误用）。**② launcher 原位重建**：`ensure_napcat_launcher` 补 launcher.sh（上游同款内容）/编译 libnapcat_launcher.so/napcat 目录内重启垫片（外部组件以 napcat 为 CWD 调 launcher.sh 也能工作）；`install_napcat`/`start_napcat` 修复优先——框架（napcat.mjs）+ LinuxQQ 完好时只补缺失件，绝不整删安装；就绪检查失败原因逐项进日志（可观测）。**③ apt-get update 快速通道**：`ensure_napcat_build_deps` 依赖齐备零 apt 操作；`patch_napcat_installer` 新增 python3 幂等补丁把上游 `install_dependency` 的无条件 `apt-get update` 换成依赖守卫（齐备跳过，缺失先直接装不刷源、失败才回退刷新源）——被杀点根治。**④ 消息真实性**：重装消息打印真实检测版本（旧消息把钉扎版号当检测版本显示，误导排查）。

- 验证：bash -n 过；新增功能测试 68/68 PASS（scripts/test-v2281-napcat-update.sh：ver_gt 六例/get_napcat_version 三态/采纳机制+幂等/显式钉扎不采纳/15 分钟退避/跨会话 URL 同步/launcher 原位重建+幂等+无框架拒绝/install_napcat 修复优先哨兵不完好框架缺失双路径/快速通道零 apt+缺依赖直接装/真实上游脚本补丁+幂等+语法/L3 回退静态断言）；版本 APP 2.28.1（vc72）
- 其他：仓库根用户上传的第十份 engine-2026-10-05.log（3900 行，07:21~23:29 五会话）分析完毕后删除（结论即 ①②③④）

### v2.28.0 → v2.27.0（vc70~vc71，摘要）

> v2.27.0（第八份日志定案）：版本钉扎强制执行（napcat.mjs 找不到钉扎字面量即立即重装，双入口+15 分钟退避）；反检测写入 python3 化（rootfs 自带 3.12，node 链降兜底限 25s）；wait_napcat_ports 立即自愈（主进程退出同步调 napcat-start）；看门狗硬化（内层 timeout 480 + [NAPCAT-WD] 摘要进 fd9）。v2.28.0（10-05 双日志封号全链定案）：反检测分级复检（旧 .bypass_disabled 一次性迁移 nohook 形态，绝不自动重试实证必崩的全开形态）+ 分级 bypass 自愈阶梯（L2 先只禁 hook 崩溃源，仍崩才全禁）+ 运行时真相进 UI（AntiDetectState 判据 标记文件>控制台>配置块）+ 环境伪装（/proc/version、osrelease、hostname 绑定遮蔽 + machine-id/hostname 固定）+ 发送风控 SendGuard 三道闸（登录预热 45s/限速 2.5s+0~1.5s 抖动/突发 12 条冷却 60s，拦截原因直达手环 toast）。

### v2.26.0（vc68）

> NapCat 崩溃循环终结版（第七份引擎日志驱动，三根修一加强）：**① Worker SIGSEGV 崩溃循环 → 四级自愈阶梯 + NapCat 版本钉扎**（用户 10-05 第七份日志实锤：v2.25.0 清缓存后的全新启动仍 `[UtilityProcess] Worker退出码11(SIGSEGV)×3 → 主进程退出 → :3001/:3000 永不监听`；上游考据实锤 NapCat issue #1626 同症状（更新后 Worker 退出码 11/139 ×3，多容器宿主全复现），官方规避 = `NAPCAT_DISABLE_BYPASS=1`（bypass 反检测原生钩子在容器/proot 环境段错误，源码考证 base.ts `enableAllBypasses` 默认启用 + 4.18.29 于 10-04 20:30 发布、容器走 releases/latest 静默漂移拉到新版）。修=启动前新增四级自愈阶梯：每次检测到崩溃特征（.need_clean 标记/控制台尾部"主进程退出"）计数 +1，端口真正监听即清零——L1 清缓存残锁原样重启（v2.25.0 行为）→ L2 `NAPCAT_DISABLE_BYPASS=1` 启动（上游官方规避；标记 .bypass_disabled 持久化，后续启动沿用避免每次冷启先崩一轮）→ L3 重装钉扎版 NapCat（配置目录备份恢复，自愈标记文件随目录摘存）→ L4 深度重置 QQ 数据目录（登录态损坏最后手段，需重新扫码）；同时 NapCat 下载源从 `releases/latest` 钉扎为 v4.18.28（本设备实测可用版本，`NAPCAT_SHELL_URL`/`NAPCAT_SHELL_VERSION` 环境变量可覆盖升级）。**② 看门狗"宣布重启却永不发生"根修**（第 7 份日志实锤：07:22:06 宣布"5 分钟内自动清理并重启"，直到 07:28:32 用户手停都无动作——主进程退出后残留子进程（renderer/gpu，cmdline 带 --type=）仍命中 `pgrep 'qq --no-sandbox'`，start_napcat 误判"已在运行，跳过重复启动"。修=①新增 qq_main_alive：仅"无 --type= 的主进程"算存活（/proc/PID/cmdline 逐个甄别）②崩溃特征判定优先于"已在运行"跳过（先杀残进程清现场再拉起）③napcat_kill_stale 补 '/opt/QQ/qq' 模式（连子进程一起清）④巡检 300s→120s、文案同步"约 2 分钟"⑤端口真正监听→自愈计数清零+重新起步。**③ 反检测自动开启"node 不可用"根修**（第 7 份日志实锤 v2.25.0 的 node 合并在 proot 永远失败（容器无独立 node）→ WebUI 手动兜底从未真正可用。修=新增 napcat_node_run 运行时回退链：node → nodejs → **QQ 自带 Electron `ELECTRON_RUN_AS_NODE=1` 退化纯 Node 运行时**（QQ deb 必在、无 GUI 依赖、proot 可用）；JS 落临时文件调用，argv[2]||argv[1] 双模式兼容；bypass_disabled 在位时跳过写入防"假已开"。**④ 停止链与日志管道加强**（TERM 优雅窗口 4s→8s（NT 大库落盘需要）+ 停止/杀残模式补 '/opt/QQ/qq'；napcat-console tap 改写 fd9（主脚本启动时捕获引擎管道，看门狗自愈重启后 [NAPCAT] 日志继续全程可见，不再断流进 watchdog.log）；App 反检测说明行同步自愈语义）。

- 验证：astrbot-startup.sh bash -n 过；新增功能测试 15/15 PASS（反检测 JS 首写/幂等/坏 JSON 重建/自定义键保留/-e 模式兼容、自愈计数读取/容错、fd9 关闭捕获/继承不覆盖、qq_main_alive 主进程识别/子进程排除）；bundled/companion 双 APK badging vc68/2.26.0 签名同源
- 版本：APP 2.26.0（vc68）；手环 RPK 无改动
- 其他：仓库根用户上传的第七份 engine-2026-10-05.log（436 行）分析完毕后删除（结论即 ①②③）

### v2.24.0 → v2.25.1（vc65~vc67，摘要）

> v2.24.0：发送链根修（NapCat 4.18.28 HTTP params-only 体适配 + judge() 三态判定 + 手环 toast）+ NapCat 状态/日志/配置三联动 + 一键填入热重连 + 撤回闭环；v2.25.0：二次启动崩溃首修（TERM 优雅停止 + 缓存级自愈清理 + 看门狗崩溃自愈 + 反检测自动全开首版 + 仅 NapCat 模式停 6199 刷屏 + LinuxQQ 断点续传）；v2.25.1：CI 十一轮攻坚定案 + AGP 9.0.1/Gradle 9.1.0 迁移（对 APK 功能无影响）。

### v2.23.0（vc64）

> 四线更新（第四份引擎日志 + VM 双分支实测驱动）：**① 键盘第五轮：高度链收紧，底部黑色死区铲除**（用户实测"方形手表键盘有点高，下面怎么有块黑色区域"——rect 字母区实际内容仅 170px（60+55+55 负 margin 收紧），scroll 261 预算多出 91px 死区（容器无背景露出页面黑底），键盘整体观感偏高且底部一块黑。修=高度链整体收紧：scroll 261→176（170 内容+6px 兜底）、rect 容器 321→236、KB_H_RECT 349→264（28 拼音行+236），dock/preview 由 branch-release.js 同步写入 264。**VM 双分支实测通过：redmiwatch 432×514 与 bandpro 336×480——动作行（候选栏）在键盘顶部、三排字母完整无裁剪、按键整体下移 78px 贴近屏底、拼音 a's→按时/哀伤/阿是 与 q'w→请问/千万/气温 候选正常进顶栏**；VM 像素探针实测键带止于 y=456（=固件 bottom:0 锚点 469 − 13px 缓冲，与设计值吻合），旧版同位黑区 91px 消灭，剩余底部 45px 为系统手势保留区（系统级，真机显示手势指示条非死黑）。**② AstrBot 启动失败 cwd 污染根修**（用户实测"apk上astrbot启动失败"+第四份日志定案：15:21 会话 start_napcat 后 `uv run main.py` 报 `Failed to spawn: main.py — No such file or directory` + `--no-sync outside of a project`——launch_astrbot 先 cd AstrBot 再调 start_napcat，而 start_napcat 主 shell `cd "$HOME"` 把工作目录污染成 /root，uv 在错误目录 spawn 必败（12:44 v2.21 会话自愈分支同样 cd 走过工作目录，重启后恢复即成功=同根因）。修=①start_napcat 的 launcher 改子 shell 内 cd（`nohup bash -c "cd '$HOME' && exec bash launcher.sh"`，主 shell 工作目录不再被污染）②launch_astrbot 的 `cd "$INSTALL_DIR"` 移到 uv run 之前（start_napcat/看门狗之后）+ main.py 存在性前置检查，任何分支污染后启动前强制归位）。**③ NapCat "启动成功但 APK 检测不到" 根修：按账号配置升级**（用户实测 NapCat 已启动已扫码但 App 连不上——NapCat 登录账号后只读按账号配置 `onebot11_<uin>.json`，v2.22 只写通用模板 `onebot11.json` 对已登录账号完全无效。修=ensure_napcat_configs 扫描 `$HOME/napcat/config/onebot11_*.json` 全部账号配置，旧空 server 形状自动升级为规范配置（HTTP :3000 / WS :3001 + AstrBot 桥 :6199）；start_napcat 配置升级先行，若 NapCat 已运行且配置有变更自动重启生效；webui.json 内容一致跳写（调用方可感知配置变化））。**④ QQ 账号提取 + 密码卡自动刷新 + 主页日志上移**（用户"怎么没有提取账号"——AstrBotSecrets 新增 QQ 账号读取：首选容器内 `onebot11_<uin>.json` 文件名提取（扫码登录后自动出现），兜底扫 napcat-console.log 登录行（账号/uin/self_id/logged in）；「密码与登录」卡新增 QQ 账号行+一键复制；卡片随引擎状态变化自动刷新（LaunchedEffect）不再依赖手动点刷新；主页「实时日志」上移到状态卡之后快捷操作之前，打开主页首屏即可见日志）。

- 验证：四分支 rpk 解包断言 verify_2230 100/100 PASS（继承回归 + 高度链 264/176/236/cwd 根修/per-uin 升级/QQ 账号/日志上移断言）；手环端 node 单测 107 测 106 过（存量基线 1 不变）；APP 单测双 flavor 250/250 全绿；bundled/companion 双 APK badging vc64/2.23.0 签名 af8819e2 同源；VM 双分支键盘实测（截图+像素探针双证据）
- 版本：APP/手环 2.23.0（vc64）单轨延续
- 其他：仓库根用户上传的第四份 engine-2026-10-04.log（3294 行）分析完毕后删除（结论即 ②③；日志同时确认 NapCat 启动链四件套全链生效、AstrBot v4.28.2 在 12:45 会话成功启动 WebUI :6185）

### v2.22.0（vc63）

> 三线更新（第三份引擎日志 + VM 实测驱动）：**① 键盘第四轮：候选选择栏置顶**（用户实测反馈"键盘选择栏怎么在下面了"——v2.19 重构时把 rect 动作行（语言/候选选择栏/删除）放在了字母区下方，候选栏远离输入预览不符合方屏机型使用习惯；修=动作行与字母区换序，选择栏回到键盘顶部对齐 circle/pill 分支，高度链 60+261=321 不变、KB_H_RECT 349 不变、下展候选层 absolute 覆盖不变。VM 实测（bandpro 336×480 净启+清装）：选择栏在顶、输入出字、整词候选（z'x→这些/坐下/执行）进顶栏；验证脚本取样修正：编译产物中 keyboard67 首次出现于样式表段而非渲染树，须用 `__opts__:{id:"…"}` 取样判断 DOM 序，v2.21 曾因同因误判"动作行归位"）。**② NapCat 启动链（装而不启根治）**（用户反馈"napcat没有启动"+第三份日志定案：v2.21 全链生效后唯一断点 = NapCat 只装不启——脚本与 APK 均无启动代码，:3001/:3000 永不监听；且旧 onebot11.json 两个 server 数组为空，即使启动 App 也连不上。修=四件套：`ensure_napcat_configs`（onebot11.json 补 HTTP :3000 / WS :3001 服务端（127.0.0.1 兑定）+ 旧空 server 形状自动升级 + webui.json 固定端口 5099/Token）；`start_napcat`（Xvfb :20 + launcher.sh 后台拉起，控制台落盘 napcat-console.log）；看门狗（5 分钟巡检进程消失自动重启）；AstrBot 启动前并行拉起，引擎停止链补杀 qq/Xvfb 进程；探测细化：6185/5099 也算就绪信号，QQ 未扫码时给出扫码指引）。**③ "自动读取 2 个程序的密码给复制" + "是否启动安装 AstrBot" 开关**（AstrBot 标签页新增「密码与登录」卡：自动从引擎日志读 AstrBot WebUI 初始账密、从容器 webui.json 读 NapCat WebUI Token，一键复制；新增「开控制台」「 NapCat 扫码」双 WebUI 入口（:6185/:5099）；新增「启动 AstrBot 机器人」Switch：关闭后引擎仅装/启 NapCat 不下载 AstrBot（省电省存储），ASTRBOT_ENABLE 环境变量透传进容器，脚本仅 NapCat 模式看门狗循环常驻）。另：用户上传第三份 engine-2026-10-04.log 分析后删除（结论即 ②；日志同时确认 DNS 竞速/LinuxQQ 签名/NapCat 预下载/AstrBot WebUI 全链真机生效，AstrBot v4.28.2 成功启动）。

- 验证：四分支 rpk 解包断言 verify_2220 82/82 PASS（继承回归 + 换序取样修正/NapCat 启动链/开关/密码卡断言）；手环端 node 单测 107 测 106 过（存量基线 1 不变）；APP 单测双 flavor 250/250 全绿；bundled/companion 双 APK badging vc63/2.22.0 签名 af8819e2 同源；VM bandpro 净启清装实测（选择栏置顶+输入出字+候选进顶栏）
- 版本：APP/手环 2.22.0（vc63）单轨延续

### v2.21.0（vc62）

> 引擎日志（会话4）+ 模拟器实测双驱动的两根修：**① 方形分支键盘「只显示一半 / 输入不展开」VM 实测根治**（第三轮，本轮真复现——redmiw5 官方模拟器 432×514 首次完整复现用户所见故障：第三排字母 Z-X-C-V-B-N 被拦腰截断、动作行叠进字母区、下展候选层被裁。解包取证定案：v2.19 测得的「固件幻影 83~85px 上方偏移」实为 #keyboard67 样式表残留 `position:absolute; top:82px`（62 圆屏时代定位遗产）——幻影是自家 CSS 不是固件行为；且 rect 容器 height:274 装不下子元素 261(scroll 预算)+60(动作行)=321，固件把动作行上提 47px 叠进第三排。修=容器 274→321 高度链闭合（28 拼音行+321=KB_H_RECT 349=dock 同值）+ 铲除 keyboard67 absolute 定位；滚动区 261 预算保留（真固件若另有内容注入偏移，多出的空间兜底不裁行）。**VM 实测通过：redmiwatch 432×514 与 bandpro 336×480 两方形分支——三排字母完整、动作行归位无叠压、拼音输入出字、下展候选层完整含收起箭头**）；**② NapCat 离线包预下载**（日志实证 sudo 垫片/STAGE 可视化/DNS 重写/镜像竞速全部生效后，唯一剩余断点=napcat.sh 上游脚本内部自带的压缩包下载——它自测代理选中 ghfast.top，4.5 分钟爬至 1.3% 后 curl(18) Transferred a partial file → exit=1，不受 v2.20 gh_fetch 管控。修=新增 ensure_napcat_zip：gh_fetch 多源竞速+断流自杀预取 NapCat.Shell.zip 至脚本同目录，上游脚本检测到本地包即跳过其内部下载（官方支持路径）；unzip -t 自验，失败不阻断保留上游兜底；中断重试不从头装）。

- 验证：四分支 rpk 解包断言 verify_2210 86/86 PASS（继承 73 项回归 + 新增键盘高度链/absolute 铲除/预下载断言 13 项）；手环端 node 单测 107 测 106 过（存量基线 1 不变）；VM 实测截图归档（redmiwatch/bandpro 两档案修复前后对比）
- 版本：APP/手环 2.21.0（vc62）单轨延续
- 其他：仓库根用户上传的第二份 engine-2026-10-04.log（v2.20 实战日志）分析完毕后删除（结论即 ②；日志同时确认 v2.20.0 的 sudo 垫片/STAGE 可视化/竞速缓存在真机全部生效）

### v2.20.0（vc61）

> 引擎日志驱动的三线更新（分析用户上传的 engine-2026-10-04.log 定案）：**① NapCat 安装链「sudo 不存在」根修**（日志会话 2 实证：DNS 竞速/清华镜像/uv/LinuxQQ 签名回退全部走通后，NapCat 上游安装脚本因容器内无 sudo 拒绝执行——"sudo不存在, 请手动安装" → exit=1。这是 v2.18.1 为压缩必装清单去掉 sudo 的副作用（proot 恒 root 本不需要提权，但上游脚本硬性 `command -v sudo` 检查）；修=新增 ensure_sudo_shim 透传垫片 `/usr/local/bin/sudo: exec "$@"`，零联网零体积覆盖上游全部 sudo 用法）；**② GitHub 下载 0 字节挂死根治**（日志会话 3 实证：代理竞速胜出者 ghfast.top 在 napcat.sh 正式下载时 TCP 已连但 0 字节挂死 35 秒以上——--connect-timeout 只管连接阶段管不到响应阶段，curl 无低速自杀参数，用户只能手动停止引擎且触发 "read interrupted by close() on another thread" 噪音；修=新增 gh_fetch 统一下载入口：每次尝试带 `--speed-time 20 --speed-limit 512`（20 秒均速不足 512B/s 即断）+ `--max-time 900` 总超时，失败自动遍历「竞速胜出者 → 其余竞速存活代理 → 直连」候选序列，全灭自动重竞速再试一轮；uv 下载、napcat.sh、AstrBot 的 git ls-remote+clone 全部接入候选重试；LinuxQQ 官方 CDN 下载加断流自杀（签名回退链保持）；network_test 竞速结果同会话缓存不再重复探测）；**③ 启动可视化 + 控制台入口**（用户反馈：普通用户看不懂 log，不知道流程到哪了。AstrBot 标签页新增「启动进度」卡：8 阶段大白话清单（准备容器/基础命令/uv 工具/下载 LinuxQQ/安装 NapCat/下载 AstrBot/Python 依赖/启动服务）+ 进度条 + 每阶段预估耗时，由启动脚本新增 stage() 输出的 `[STAGE:百分比:描述]` 结构化标记经 EngineManager 解析实时驱动（标记行同时人可读进日志）；安装中断不再只报 exit=1，改为提示「点启动引擎重试（已下载组件复用，不会从头装）」；主动停止按预期路径处理，read interrupted 不再误报"启动异常"；顺带修复 L_* 本地化变量从未定义导致的 "Napcat ，..." 残缺文案。**新增「打开控制台」按钮**（泡泡版 AstrBot Bubble 同款）：系统 WebView 打开 AstrBot 控制台 `http://127.0.0.1:6185`（复用 WebUiActivity，引擎运行中可用））。

- 验证：bash -n 语法过；gh_build_candidates 候选序列单测过（胜出者优先→存活代理→直连去重）；四分支 rpk 解包断言 verify_2200 73/73 PASS（继承 v2.19.0 键盘/幻影预算回归 68 项 + 新增 sudo 垫片/gh_fetch/断流自杀/阶段标记/L_* 断言 5 项）；手环端 node 单测 107 测 106 过（存量基线 1 不变）；APP 单测双 flavor 全绿；bundled/companion 双 APK badging vc61/2.20.0
- 版本：APP/手环 2.20.0（vc61）单轨延续
- 其他：仓库根用户上传的 engine-2026-10-04.log 分析完毕后删除（结论即上述 ①②；日志同时确认 v2.19.0 的 DNS 根修+多源竞速已在真机完全生效）

### v2.19.0（vc60）

> 三线修复+增强：**① 方形分支键盘「只显示一半」VM 实测根治**（redmiw5 官方模拟器 432×514（与真机 Watch 5 同规格）复现取证：固件底部有 ~49px 手势保留区，页面可用高 ≈465 而 device.getInfo 的 screenHeight 仍报 514——v2.18/2.18.1 的 top=屏高−键盘高 构建期常量把键盘整体推进保留区（Z 行被视口拦腰截断）；且该固件对 `<scroll>` 内容注入「上方静态流高度」的幻影纵向偏移（实测 83~85px），v2.17 起字母滚动区改显式高后预算不足即末行被裁——这正是历代"半屏键盘"反复复发的共同机制。修法：**kb-dock 改 bottom:0 锚定**（引擎按可用底解析，零视口常量，天然适配全部机型/镜像）+ rect 字母区纯流式重构（动作行移到字母区下方、T9 死代码删除、旧进度条删除）+ 字母滚动区显式高 261=幻影 85+行 180+余量 6（KB_H_RECT 283→349）+ 预览区改 bottom 锚定（自动贴合键盘顶）+ 预览文字单行紧凑化。**VM 实测截图全过**：bandpro 336×480 / redmiwatch 432×514 / band9 192×490 键盘全部完整、拼音组合（b'g→不过/报告/表哥）+ 候选词横滚正常）；**② AstrBot 容器 DNS 根修+多源竞速**（用户 10-04 日志依旧 Temporary failure resolving 'ports.ubuntu.com'——rootfs 资产取证实锤：发行包 /etc/resolv.conf 是普通文件且烙着构建机 systemd-resolved 毒桩 `nameserver 127.0.0.53`（Azure VM 残留），v2.18.1「已有 nameserver 即跳过」的幂等检查恰好被毒桩骗过、修复从未生效。修法：脚本侧 ensure_container_dns **无条件重写**公共解析器+getent 解析自检（与 apt 同一条 glibc 解析路径）；EngineManager 侧生成宿主 resolv.conf 并 `proot -b` 绑定进容器（Termux proot-distro 同款，DNS 优先取系统当前网络 DNS、公共解析器兜底）双保险。**多源竞速+多任务并行**：ubuntu-ports 源 6 镜像（清华/USTC/阿里/腾讯/华为/官方）bash /dev/tcp 并行探测 ≤10s 首个打通者胜出，update 失败自动遍历其余可达源重试；GitHub 12 代理从串行逐个测（最坏 240s）改全并发竞速（≤15s）+直连兜底；napcat.sh 下载走竞速代理（原直连 raw.githubusercontent.com 被墙必死）；uv sync 失败自动换 Python 构建镜像重试；apt 通信硬化（Retries 3/超时 15s/ForceIPv4）提前落盘全链受益）；**③ APK 实时日志面板位置回移**（高度正常后「开服务后标签行插入把日志盒推到原塌陷位」：过滤标签并入标题行横向滚动，任何时刻不再新增行，日志盒 y 坐标恒定不跳）。

- 验证：四分支 rpk 解包断言 verify_2190 68/68 PASS（新增 kb-dock bottom:0/幻影预算滚动高 261/动作行位序/T9 死代码删除/预览 bottom 锚定/调试零残留断言）；手环端 node 单测 107 测 106 过（存量基线 1 不变）；APP 单测 125+125 双 flavor 全绿；VM 实测 bandpro/redmiwatch/band9 三档案键盘截图全过；bundled/companion 双 APK badging vc60/2.19.0，胖包资产含 DNS 竞速版启动脚本
- 版本：APP/手环 2.19.0（vc60）单轨延续

### v2.18.1（vc59）

> 五项修复：**① 键盘半屏真机根修**（v2.18 已把宿主 kb-dock 改顶部锚定，但键盘组件根容器 .page 仍 bottom:0 —— 真机固件上该 bottom 解析到页面文档底而非 dock 底（镜像引擎按最近定位祖先解析所以 VM 取证通过、真机依旧半屏），1px nudge 重排修不了定位错，与「输入也不展开」全部症状吻合；改 top:0 与 dock 同源锚定，dock 高==键盘高构建期同值写入，正常固件两锚等价、异常固件 top 锚已被 v2.18 取证证明全固件可靠）；**② 撤回假成功回帧修正**（用户实测证伪 v2.18 的「get_msg 查无=已撤回」推断：NapCat recallMsg NT 超时（撤回未生效）时 get_msg 同样可能 1200，导致手环显示“撤回成功”而群里消息还在；现在 get_msg 探测只区分「消息仍在/状态无法确认」两种终败文案，任何路径不再回假成功，重试链保持）；**③ AstrBot 自动补装 DNS 根修**（用户 10-03 日志：自动补装首步 apt-get update 即 Temporary failure resolving 'ports.ubuntu.com'——proot 不提供网络栈配置、发行版 rootfs 缺 /etc/resolv.conf，glibc 解析必败；启动/装步前写入公共解析器（阿里/腾讯/谷歌）+ update 失败自动切清华 ubuntu-ports 镜像重试一次 + 必装清单去 sudo（proot 恒为 root，包名清单越长源不可达时死得越早））；**④ 引擎日志落盘**（用户反馈引擎日志无本地 log 文件：EngineManager 全量输出异步写入 Android/data/<pkg>/files/logs/engine-*.log（与主日志同目录，导出 zip 自动收录，8MB 滚动 7 天回收），AstrBot 标签页日志块同步改定高滚动+显示落盘路径）；**⑤ APK 通讯日志面板定高加固**（开启服务后日志区高度塌陷：日志盒彻底去 weight 化改固定 300dp，不随过滤标签行增减/父容器测量路径变化）。

- 验证：四分支 rpk 解包断言 verify_2181 132/132 PASS（新增 .page 不再 bottom 锚定/top:0 在包）；手环端 node 单测 107 测 106 过（存量基线 1 不变）；APP 单测 125+125 双 flavor 全绿；bundled/companion 双 APK 编译 badging vc59/2.18.1，胖包含 DNS 修复版启动脚本
- 版本：APP/手环 2.18.1（vc59）单轨延续

### v2.18.0（vc58）

> 三线修复：**① AstrBot 引擎「环境不完整」根治**（升级用户设备上 v2.17 自愈脚本从未部署——历史实现只在首次安装时写脚本，isInstalled=true 时安装流程整体跳过，容器里跑的还是旧版预检脚本，报 missing curl/git/uv + MANUAL_ENV 后退出；现每次启动前用 APK 资产强制刷新容器内三脚本/配置（内容一致跳写、保留重装标记），且 start() 前置 installIfNeeded（未装引擎直接启动必败的老问题一并修掉））；**② 撤回/v11 动作重试+验证+明细**（用户 10-03 日志实证撤回链路 BandQQ 侧已全通、失败点在协议端 NapCat recallMsg NT 事件超时 retcode=1200（社区已知瞬态型问题）——v11 动作统一通道改为失败自动重试 3 次（1.2s/2.5s 间隔），delete_msg 1200 时先经 get_msg 验证（消息已不存在按撤回成功回帧，覆盖"已生效但反馈丢失"场景），最终失败透传协议端 retcode 到手环 toast；另：HTTP 拒连自适应降级（连续 3 次拒连 → 10 分钟 WS-only，换配置自动恢复，砍掉每动作两路空转））；**③ 键盘 dock 顶部锚定 + 挂载自愈**（Vela VM 四分支实测取证：band9 镜像页面高度解析异常（内容高≈665>视口 490），absolute bottom:0 把键盘整体推出屏外≈175px——第三排字母腰斩、进度条不可见，即用户口径"键盘只显示一半，输入不展开"；页面顶部锚定在全部固件/镜像上精确可靠，kb-dock 改 top=视口高−键盘物理高（band 157/bandpro 197/xiaomis 154/redmiwatch 231，构建期分支写入）+ 键盘弹出后 120ms/650ms 两拍 1px 收缩还原强制重排（历史"输入一下就恢复"的自愈无需用户输入））。

- 验证：四分支 rpk 解包断言 verify_2180 124/124 PASS（新增 dockTop 四分支值/不再 bottom 锚定/nudgeRelayout 在包）；手环端 node 单测 107 测 106 过（存量基线 1 不变）；bundled/companion 双 APK 编译 badging vc58/2.18.0
- 版本：APP/手环 2.18.0（vc58）单轨延续

<details>
<summary><b>v2.15.0 ~ v2.17.0</b> — 引擎三连修 / message_id 撤回链 / 渲染器 v2.17（点击展开）</summary>

- **v2.15.0**：busybox error=13 根修（targetSdk 34→28，Termux/UserLAnd 同款 W^X 豁免方案）+ 键盘高度链全显式化（根/懒建/黑底三层 kbHpx）+ 演示模式补齐新特性形态
- **v2.16.0**：rootfs 前缀拍平（pd 发行包 ubuntu-noble-aarch64/ 顶层前缀致 bin/bash 校验必败 → 两段式解压+逐项上移）+ message_id 数字形态（NapCat MessageUnique 按 number 索引，字符串 key 静默失败）+ 直连六类 v11 动作 + 键盘宿主去 flex 化（Vela VM 实测驱动，xiaomis 取证通过）
- **v2.17.0**：引擎启动自愈（预检失败自动补装 curl/git→uv→NapCat→AstrBot 全幂等；本轮发现升级用户设备未部署，v2.18 修）+ proot fd 绑定告警摘除 + message_id 撤回链四处断点全接 + 键盘内层字母键 scroll 显式高 + 渲染器 v2.17（QQ红包/转发摘要/文件大小/表格压平）+ 演示模式同步补齐
</details>

### v2.14.0（vc54）

> 四件事：**胖包 AstrBot 独立标签页**（bundled flavor 底栏新增第 4 页「AstrBot」——引擎状态/启动/停止/NapCat 探测/引擎日志（80 行）/一键写入并保存本机直连地址全在标签页完成，设置页仅留指引卡；瘦包 4 页不变，Tab 集按 flavor sourceSet 编译期二选一）+ **键盘两处非手环机型修复**（①空输入时键盘只显示一半、敲一键才恢复——根容器 height:auto 在部分固件首帧测量坍缩，改 JS 侧显式总高（circle 321 / rect 283 / pill 333，rect·pill 恒预留 28px 拼音行，内容高度不再随输入变化），②输入预览行同步受此保护；xiaomis 的 466/480 换算常量同步入构建脚本）+ **智能自动渲染器**（AstrBot/机器人/分享消息首次在手环可读——json 卡片提取 meta.prompt/音乐·新闻·小程序标题、xml 卡片取 title/brief、markdown 降纯文本、合并转发/表情包/GIF/文件名/位置/分享/戳一戳/骰子等 20+ 段型，CQ 字符串同规则，800 字符护栏；手机端 OneBotParser 与手环端兜底 protocol.js 同步升级）+ **libbusybox.so 缺失修复**（EngineManager bin 组装双通道：nativeLibraryDir 缺文件时从 APK 内 lib/arm64-v8a/ 直取，报错附带设备 ABI 与路径诊断）。

- 验证：手环端 node 单测 90 测 89 过（新增渲染器 10 项全绿，api.test.js 1 例存量环境失败基线一致）；bundled/companion 双 APK 编译 + badging vc54/2.14.0；四分支 rpk 解包断言全 PASS（显式高度常量/恒预留拼音行/渲染器标记）
- 版本：APP/手环 2.14.0（vc54）单轨延续

<details>
<summary><b>v2.13.0</b> — 键盘全分支换装 NEORUAA + AstrBot 胖/瘦双 APK + OneBot v11 扩展 + DevTools miuix 重写 + AGPL（点击展开）</summary>

> 四件事：**键盘全分支换装 NEORUAA/Vela_input_method**（v2.12.0 的 Revise fork 三快照退役，改用上游单组件内置 circle/rect/pill-shaped 三屏形布局，构建期只做 screentype 写入 + xiaomis 圆屏 466/480 换算；词库升级为 NEORUAA 分片按需加载体系，BandQQ 全量 27398 字合入 cn.txt 频序拱顶保留）+ **AstrBot 胖/瘦双 APK**（胖包 bundled 版把 AstrBot Bubble 的引擎层完整内嵌——proot 套件 + 64MB Ubuntu rootfs + 一体化启动脚本，设置页安装/启动/停止/日志/端口探测全流程卡片；瘦包 companion 版保留 v2.12.0 伴侣模式，双包同 applicationId 二选一安装）+ **OneBot v11 特性扩展**（点赞 send_like / 主动拍一拍 friend_poke·group_poke / 群签到 send_group_sign / 表情回应 set_msg_emoji_like / 撤回自己消息 delete_msg / 资料 get_stranger_info·get_group_member_info，手环长按菜单与聊天页气泡菜单直达，发送应答自动回填 message_id，DevTools 同步模拟）+ **DevTools 1.4.0 miuix 重写**（与主 APK 同一套 HyperOS 设计语言，主题跟随系统深浅色）+ **许可证 MIT → AGPL-3.0**。

- 验证：双 APK（companion 12MB / bundled 80MB）+ devtools 编译全过，badging vc53/2.13.0 与 vc7/1.4.0，EngineManager/EngineService 多 dex 标记 FOUND，rootfs/proot 资产入包确认；手机端单测 104 测 104 过，手环端 79 过（存量基线一致）
- 版本：APP/手环 2.13.0（vc53）单轨延续，DevTools 1.4.0（vc7）
</details>

<details>
<summary><b>v2.12.0</b> — 键盘分支原生化 + 全量拼音字库 + AstrBot 本地伴侣 + README 项目化（点击展开）</summary>

> 三件事：**键盘分支原生化**（用户报告「以前给其他分支单独适配的键盘不见了」——v2.10.0 分支包里非手环机型仍被塞着胶囊键盘只做数值缩放；现按分支换装三套原生布局：bandpro/redmiwatch 方屏版、xiaomis 圆屏 QWERTY 版，方屏修上游两处笔误 onscroll 拼写/percent 绑定，方屏符号键扩充 ～！？【】「」、·）+ **全量拼音字库**（legacy 变体 20924 字全量池回归，与现役频序字典合并为 **419 音节 27398 字**，常用字频序拱顶不变、生僻字可打）+ **AstrBot 本地伴侣**（集成 MuFengDR/AstrBot-Bubble-Android-App：设置页新增 AstrBot 卡片，检测/一键拉起 com.astrbot.astrbot_bubble、探测本机 NapCat :3001/:3000 就绪状态、一键填入 127.0.0.1 本机地址；不做二进制合并——对方是 Flutter + 64MB Ubuntu rootfs 独立应用，伴侣模式才是性能与体积最优解）+ **README 项目化改版**。

- 验证：四分支 rpk 解包 68 项断言全 PASS（新增键盘布局类残留检查/按键宽换算/滚动总宽词边界正则/全量字库罕见字标记）；node 单测 80 测 79 过（存量基线一致）；APK badging vc52/2.12.0 + AstrBotBridge 多 dex 标记 FOUND
- 版本：RPK 四分支+通用 2.12.0（vc52，versionName 带分支后缀）+ 同步器 2.12.0（vc52，签名同源 af8819e2）
</details>

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
| [**NEORUAA/Vela_input_method**](https://github.com/NEORUAA/Vela_input_method) | 手环端拼音键盘的直接来源（v2.13.0 起全分支统一换装其单组件：circle/rect/pill-shaped 三屏形布局，QWERTY+T9，中/英/日三语），BandQQ 分支构建期写入屏形并做圆屏尺寸换算，另合入全量字库 27398 字 |
| [**AetherZeng1145/Vela-Input-Method-Revise**](https://github.com/AetherZeng1145/Vela-Input-Method-Revise) | 上述键盘的活跃 fork，v2.12.0 及之前版本键盘分支化的基座，其方屏适配经验反哺了换装换算规则 |
| [**MuFengDR/AstrBot-Bubble-Android-App**](https://github.com/MuFengDR/AstrBot-Bubble-Android-App) | AstrBot 本地机器人引擎层来源：胖包（bundled）内嵌其 proot 套件 + Ubuntu rootfs + 一体化启动脚本；瘦包（companion）检测/拉起独立 App 一键快连 |
| [**AstrBot**](https://github.com/AstrBotDevs/AstrBot) | 多平台聊天机器人框架，经 AstrBot Bubble 接入后可为 QQ 号提供大模型自动回复 |
| [**Astroptis/band-qq-assistant**](https://github.com/Astroptis/band-qq-assistant) | 本项目上游，v2.x 以上游 main 为底重写性能架构并回归 stapxs 特性 |
| [**stapxs/stapxs-qq-lite**](https://github.com/ImStapxs/stapxs-qq-lite)（含 Lite-X） | 气泡布局 / 彩色首字头像等视觉范式的参考 |
| [**CoraTech-Wear/merqury-vela**](https://github.com/CoraTech-Wear/merqury-vela) | eSIM 机型独立直连线路的实现参考（@system.fetch 直连 OneBot HTTP） |
| [**miuix**](https://github.com/miuix-kotlin-multiplatform/miuix) | Android 端 HyperOS 风格 Compose UI 框架（主 APK 与 DevTools 共用） |

> 感谢小米 Vela 团队的快应用生态与官方 VVD 虚拟机——本项目的界面验收全部由其 gRPC 实拍完成。

## 📄 许可证

本项目自 v2.13.0 起以 **[GNU Affero General Public License v3.0（AGPL-3.0-only）](LICENSE)** 发布——
主体代码此前为 MIT，切换为 AGPL 以确保衍生项目（含网络服务化部署）同样开源。
版权行：`Copyright (C) 2026 Astroptis / Gsjsjzhznsz contributors`。

第三方组件以其自身许可证为准（均与 AGPL-3.0 兼容或以独立作品边界引用）：

| 组件 | 许可证 |
|---|---|
| NEORUAA/Vela_input_method（键盘组件与词库） | BSD-3-Clause（代码）/ Apache-2.0（词库数据），词库按其 README 说明使用 |
| MuFengDR/AstrBot-Bubble-Android-App（引擎层：proot 套件/rootfs/启动脚本） | BSD-3-Clause（基于 nightmare-space 系项目），胖包内嵌保留其版权声明 |
| miuix | MIT |
| AstrBot（运行时按需容器内安装） | AGPL-3.0，与本许可证兼容 |

> 关于页显示的版本号与许可证信息请以 [Releases](https://github.com/Gsjsjzhznsz/BandQQ/releases) 最新发布说明为准。

---

> 关键词：小米手环9 / Mi Band 9 / 小米手环10 / 小米手环11 / 小米手环QQ / 小米手环9 Pro / Redmi Watch / Xiaomi Watch S / Vela 快应用 / 快应用 rpk / OneBot v11 / NapCat / Lagrange / LLOneBot / go-cqhttp / QQ 消息同步 / 手环回复QQ / 手环看QQ / 蓝牙消息助手 / eSIM 手表 / AstrBot / wearable QQ / smartband chat
