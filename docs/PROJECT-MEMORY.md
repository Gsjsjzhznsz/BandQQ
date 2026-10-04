# PROJECT-MEMORY — BandQQ 项目记忆库（跨会话持久记忆）

> **本文件是跨会话/跨容器的唯一权威记忆**。每轮会话结束前必须增量更新并推送。
> **新会话恢复流程**：克隆 `Gsjsjzhznsz/BandQQ` → 读本文档 → 按第 9 节任务清单继续干活。
> 最后更新：2026-09-09（会话 #10：记忆固化）

---

## 1. 项目是什么

**小米手环 9 QQ 消息助手**（三端系统）：

```
小米手环9 (Vela 快应用 band-qq/) ⇄ 蓝牙 interconnect ⇄ Android 同步器 (android-sync/)
                                                                        │ OneBot v11 WS(:3001) + HTTP(:3000)
                                                            SnowLuma / NapCat / Lagrange（协议端，外部）
                                                                        │
                                                                    QQ 服务器
```

- 手环端：Vela 快应用（JS + aiot-toolkit 构建 rpk），4 页面 + 849 行自研 T9 拼音输入法
- 手机端：Kotlin + Jetpack Compose（minSdk 26 / targetSdk 34），前台服务保活
- 上游作者 Astroptis，原代码由 opencode 编写，MIT 协议

## 2. 仓库地图（谁是权威）

| 仓库 | 角色 | 说明 |
|---|---|---|
| `Astroptis/band-qq-assistant` | **上游** | 只有 pull 权限。main 曾是 v1.0.0 基线，用户后来用 opencode 继续迭代（v9/v13/v17 标记的精简线，无 stapxs 功能） |
| `Gsjsjzhznsz/BandQQ` | **权威工作仓库（小号）** | tag `v1.1.1` = v1.1.0 stapxs 完整版 + 六项质量修复。含 `keystore.jks`、`dist/`（v1.1.1 三包产物）、`legacy/`（用户快照 + v1.1.0 7z 分卷）、`scripts/`（全部构建配方） |

**⚠️ 基线裁决**：用户反馈"快捷回复按钮变格式代码、主界面跳动"针对的是 stapxs 功能版 → **一切开发基于 `Gsjsjzhznsz/BandQQ` tag v1.1.1**，不是上游 opencode 线。上游 opencode 线（158 行 index.ux）是另一条稳定但无功能的分支，仅作参考。

- GitHub PAT：用户在对话中提供（小号 Gsjsjzhznsz 推送权限），**不写入本仓库**。
- 用户小号：`Gsjsjzhznsz`；上游账号：`Astroptis`。

## 3. 版本时间线

| 版本 | 内容 | 状态 |
|---|---|---|
| v1.0.0 | 上游基线：本地历史为主，无分页拉取 | 上游 main |
| OneBot 修复轮 | compose 硬编码 private 双发、HTTP 200 不看 retcode、发走 HTTP 收走 WS 双链路割裂 → WS echo RPC 单链路 + send_result 回推 + retcode 严格校验 | 已合并进 v1.1.0 |
| v1.1.0 | Stapxs-QQ-Lite-X 移植：彩色头像会话列表/未读角标/[@我]标签+震动/5分钟时间条/快捷回复/图片缩略图(96px)/翻页/字库 6763→8241/企鹅图标/无障碍保活/登录信息 | 完整源码在 legacy/7z 内（上游 main + 42 个未提交改动） |
| v1.1.1 | 六项质量修复（见第 4 节台账），测试 手环50/50 + 安卓92/92 全绿，versionCode 3 | **当前权威 tag** |
| v1.2.0 | 性能优化 + 手机端数据处理 + miuix HyperOS3 UI + 快应用关闭停蓝牙等（见第 9 节） | **进行中** |

## 4. Bug 修复台账（根因与方案，勿重蹈覆辙）

| # | 症状 | 根因 | 修复 |
|---|---|---|---|
| 1 | 群聊发消息被识别成私聊，实发失败 | compose.ux 硬编码 `private` 先发一帧，再 correctConversationType 补发第二帧 | 全链路透传会话 type，单帧发送，删除补发 |
| 2 | 发送失败无感知 | HTTP 200 就算成功，不查 OneBot retcode/status | retcode ok/async/0/1 才算受理；WS echo RPC 优先，HTTP 降级；send_result 回推手环显示"已送达/失败 retcode" |
| 3 | 消息顺序跳乱 | OneBot time 是 Unix 秒(10位)，本地是毫秒(13位) | normalizeTime 统一毫秒（`< 100_000_000_000L` 判定） |
| 4 | 历史翻页失效 | before_time 单位错（秒 vs 毫秒）、锚点用 `<` 排除最旧一条、自发消息无锚点、私聊自发历史被 filter 丢弃 | message_seq 优先锚点（兼容 NapCat）+ time 兼容 Lagrange + 两段式拉取 + `<=` 边界 + 私聊 targetId 重写 |
| 5 | 冷启动丢消息 | 手机端单 key 持久化整库写丢（用户 XML 证据：整库只剩 1 条）；手环仅靠实时推送 | 按会话分 key 持久化 + 手环重连自动补推离线消息（message_id 幂等去重）+ 本地缓存 30→60 条/会话 |
| 6 | rpk 无图标 | 图标 RGBA 透明通道 + 尺寸不合 Vela 规范 | 108×108 RGB 无 alpha PNG + manifest icon 字段 + versionCode 升级刷缓存 |
| 7 | 会话列表抖动 | 无键控 diff 全表重建、负偏移绝对定位角标 | tid 键控 diff + 内容签名比对 + 150ms 防抖 + 定高列表项（v1.1.1 已修，用户反馈"还有点跳动"→ v1.2.0 继续深挖） |
| 8 | 字库 8241 字不够 | 原词典仅 GB2312 | Unihan kMandarin 全量重建 21197 字 / 417 音节 / 70KB（scripts/expand-dict-cjk.py） |
| 9 | addListener 重复注册 | SDK 重复注册导致一条 send_message 执行 N 次 | nodeId 级幂等锁（先 removeListener 再 add） |
| 10 | history 重复下发 | 手环 onInit+onShow 连发多次 get_history | HistoryDedup 1.5s 窗口去重 |

## 5. 构建配方（环境重建手册，容器重置后照此 1:1 复原）

### 5.1 环境组件
- **JDK 17**（Temurin，容器自带 java 21 是 JRE 无 javac 时需下载完整 JDK17）：AGP 8.13.2 要求
- **Android SDK**：`platforms;android-37.0`（新命名！）
  - **⚠️ 目录陷阱**：AGP 找 `android-37`，SDK 给 `android-37.0`。符号链接可能被禁止 → 方案 A：`ln -sfn android-37.0 platforms/android-37`；方案 B：实体复制 + 改写 `package.xml`/`source.properties` 的 ApiLevel 与目录一致
  - `gradle.properties` 加 `android.suppressUnsupportedCompileSdk=37.0`
- **Gradle 8.13**（上游缺 wrapper，需手动下载）
- **aiot-toolkit 2.0.5**（npm 装，rpk 构建签名工具）
- 构建 env 放 `/home/z/my-project/env/`（/opt 不可写）

### 5.2 签名（一致性是互联配对硬约束）
- `keystore.jks` 已在仓库根（密码 bandqq123，alias bandqq）——APK 与 rpk 同证书
- rpk 签名：从 jks 提取 `private.pem` + `certificate.pem` 放 `band-qq/sign/release/`（scripts/make-keystore.sh）
- APK SHA-256：`af8819e27a6ec8d84537ec86937cf016e780376305328abaa4eb79e8c626b004`
- **包名一致约束**：手环 manifest.package == Android applicationId == `com.example.bandqq`（v1.1.1 用的这个；com.bandqq 是 legacy 变体）

### 5.3 构建命令
```bash
# rpk（手环端）
cd band-qq && npx aiot build && npx aiot sign   # 见 scripts/build-rpk.sh
# APK（手机端）
cd android-sync && ./gradlew assembleRelease test   # 见 scripts/build-apk.sh
```

### 5.4 已知测试坑
- JUnit4 测试方法结尾表达式返回非 void → 整类拒跑（RunBlocking 尾表达式陷阱）
- MockWebServer url() 返回 `localhost` 非 `127.0.0.1`（断言勿硬编码）
- 单测 android.jar 的 org.json 是 stub → classpath 加真实 json
- MockWebServer teardown 偶发 IOException → shutdown 包 try 忽略
- 测试时间戳一律毫秒（曾有测试传秒导致锚点查询为空误判生产 bug）

## 6. 关键技术事实（血泪经验）

1. **interconnect 心跳**：手机端 3s ping / 10s 超时 / 5s 重连循环；重连不拉起手环应用（launchApp=false）
2. **帧大小上限**：互联通道单帧 ~15000 字节（buildHistoryFrame 有保护；push_message 无）
3. **手环 storage**：@system.storage 同步 IO，频繁全量 JSON.stringify 大对象会卡 → v1.2.0 要做节流持久化
4. **快应用 console.log 是同步 IO**：生产包要砍日志
5. **InputMethod 坑**：`show` 导致候选列表全量渲染变卡 → 用 `if`（原作注释）
6. **rpk 构建**：InputMethod 组件与 dic.js 会被 webpack 打进引用页（compose.js），包内无独立文件是正常的
7. **Vela 无手环模拟器**：UI 验证用 aiot-toolkit H5 预览 + Playwright 截图 + 多模态识别（容器无 KVM 跑不了安卓模拟器）
8. **D degradeContent**：手机端已做 emoji→[表情]、segment→文本降级；但字符串消息含 CQ 码未清洗（v1.2.0 待修——快捷回复"格式代码"疑似相关）
9. **compose maxlength=5** 一次输入限 5 字符（v1.2.0 待放开）
10. **settings.ux `off('state')` 无 fn 清空全部监听**，会误删 index 页 handler（v1.2.0 待修）

### Vela 5.0 适配铁律（v1.2.0 经官方模拟器实测定罪，违者 UI 全灭）
11. **justify-content: center / flex-end 按 2 倍容器宽计算**，内容被推出屏幕外 → 全部改显式 margin + text-align: center
12. **`show` 隐藏的元素仍占布局空间** → 用 `if`（条件渲染）
13. **模板 mustache 里三元/`&&`/`===`/`||` 表达式被编译器静默丢弃**（文案/类名全丢）→ 一律 script 预计算字段
14. **`{{obj}}` 渲染成 [object Object]**（快捷回复"格式代码"真凶）→ `{{obj.field}}`
15. **emoji 字符（👍❤️😂）渲染成乱码方块**（手环无 emoji 字体）→ 快捷回复全用文字
16. align-items（交叉轴）、text-align、onclick、动态 class 绑定、RGBA PNG 实测均正常
17. 布局总高必须精确 490px（192×490），溢出会推挤状态栏
18. **模拟器验证流水线**：aiot initEmulatorEnv（下 1.8GB 组件）→ 手写 VvdManager.createVvd(density 160, skin xiaomi_band, 192×490) → Xvfb :99 + DISPLAY → pty 驱动 `aiot start` 应答 Y → @miwt/adb `shell am start com.example.bandqq` → gRPC :8554 streamScreenshot 截帧（TouchEvent 是 Touch 数组！）
19. 模拟器黑屏先看是不是页面本身黑色背景（gRPC 截帧绕过熄屏）；左上圆弧/底部灰条/右缘暗条是 skin 蒙版痕迹，不是 app 的

## 7. 用户偏好与沟通习惯

- 语言：中文，直接、高密度、不废话；"卡了？"= 催进度，应立即汇报当前状态并继续干活
- 交付物必须推送到会话可见处 + send_message 发文件（只写 download/ 目录用户可能看不到，要主动发）
- 容器会被重置：**源码、环境、产物全丢是常态** → 一切依赖 GitHub 仓库 + 本记忆文件
- 用户给过 rpk/apk 但 upload 同步常失败 → 能从 GitHub 拿源码就不要等上传
- 风控自担：用户明确用小号测试逆向协议（SnowLuma），无需反复安全提醒
- 测试后用户会贴 SharedPreferences XML 实机证据，这是定位 bug 的金矿，逐字段分析

## 8. v1.1.1 遗留问题（用户实测反馈，v1.2.0 输入）

1. **手环一直重启死机**（最高优先级——疑似内存超限：全量消息 DOM、频繁全量 storage 写、日志 IO）
2. 数据处理应尽量在手机端做完再传（手环只做轻展示）
3. 手机 APP 无障碍很难使用（保活服务引导/交互差）
4. APP UI 改用 miuix（HyperOS 3 或更新风格）——注意：opencode 线已引入 miuix 0.9.3 可参考，v1.1.1 基线还是 Material3
5. 快应用关闭时手机端应停止蓝牙传输（OneBot 保持在线，数据缓存，冷启动后回推）——现状：sendToBand 只查 node 不查 bandConnected，快应用关闭后仍向已死通道推帧
6. 主界面仍有点跳动
7. 快捷回复按钮显示一堆格式代码（疑似 CQ 码/HTML 未清洗）
8. UI 适配问题多（192×490 屏幕）
9. 借鉴 Stapxs-QQ-Lite-X：数据管理 + 多账号管理
10. 多模态识别 UI：用 H5 预览 + 截图实现（无 KVM/无小米手环模拟器）

## 9. v1.2.0 任务清单（当前进行中）

- [x] T1 性能治理：store 节流持久化(400ms+flush) + 砍生产日志 + 缩略图 LRU(20) ✅
- [x] T2 停蓝牙：broker sendBand 门控 + pendingTargets 回放 + onBandConnected 推会话列表 ✅
- [x] T3 跳动：预计算字段+签名比对+定高+494→490px 修正 ✅
- [x] T4 格式代码：{{q}}→{{q.label}} + emoji→文字 + 双端 CQ 码清洗 + 180 字截断 ✅
- [x] T5 UI 审计：Vela 官方模拟器实机验证 + 定罪 justify/show/三元三大兼容性问题并修复 ✅
- [x] T6 miuix：0.9.4-rc01 拉高 compose 依赖与 AGP 8.13 冲突 → 保持 0.9.3；无障碍三步引导+状态轮询已加 ✅（后续可随 AGP 升级再试 0.9.4）
- [x] T7 多账号：ConfigManager accounts+activeIndex+自动迁移 + 设置页管理 UI ✅
- [x] T8 maxlength 5→200 + settings off 精确解绑 ✅
- [x] T9 构建：rpk 342KB(v1.2.0 vc4) + APK 11.8MB；手环 56/56 + 安卓 100/100 ✅
- [x] T10 推送 main + tag v1.2.0 + 本文件更新 ✅

## 10. 会话记录（增量追加，勿删）

- #1 研究报告（上游项目深度解读）
- #2 OneBot 协议修复（群聊误判/发送静默失败，引 Stapxs echo RPC 设计）
- #3 首次容器构建交付（rpk+APK+源码包；文件面板不可见问题 → 需 send_message 发文件）
- #4 v1.1.0 Stapxs 功能移植
- #5 用户实测反馈六项问题；容器重置；用户给 PAT
- #6 反编译重建 v1.1.1（第一版，非真实源码）
- #7 用户推 v1.1.0 源码到 GitHub；7z 分卷解压获权威源码
- #8 v1.1.1 真源码六项修复 + 推送 tag v1.1.1
- #9 用户提 v1.2.0 需求（性能优先/miuix/停蓝牙/跳动/快捷回复/多模态）；容器又重置；误克隆上游 opencode 线后发现基线裁决问题
- #10 用户给记忆网页链接；爬取 77KB 全史；固化本记忆文件
- #11 v1.2.0 完成：T1-T10 全落地；**用 aiot 官方模拟器（VVD 192×490）实测 UI**，定罪并修复 Vela 5.0 三大兼容性问题（justify-center 2x / show 占位 / 模板三元丢弃）+ 快捷回复 [object Object] + emoji 乱码；离线门控停蓝牙 + 多账号 + CQ 清洗 + 节流持久化；双端测试全绿 ← **当前会话**
