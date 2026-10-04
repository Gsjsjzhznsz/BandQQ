# NapCat 集成与部署文档实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 编写 NapCat APK 集成说明、签名打包说明、项目 README，让用户在手机上跑通 NapCat（Termux+proot），并完成手环端 + Android 同步器 + NapCat 三端联调。

**Architecture:** 本计划不写业务代码，产出 3 个文档：`docs/napcat-apk.md`（NapCat APK 获取与集成）、`docs/signing.md`（jks→p12→pem 签名提取与 rpk 打包）、`README.md`（项目总览与端到端验证流程）。

**Tech Stack:** Markdown 文档；引用社区方案 [astrbot-termux](https://github.com/linger-su/astrbot-termux)（基于 Termux 封装 NapCatQQ 的 APK，无需 root、无需 PC）。

## Global Constraints

- NapCat 部署在手机端 Termux + proot 容器内，同步器通过 `ws://127.0.0.1:3001` 连接。
- 手环 rpk 必须用与 Android 同步器 App 相同的证书签名（互联前提）。
- 文档使用简体中文，命令与代码块保留原文。
- 内容需与设计文档（docs/superpowers/specs/2026-08-03-band-qq-design.md）保持一致。

---

### Task 1: docs/napcat-apk.md NapCat APK 集成说明

**Files:**
- Create: `docs/napcat-apk.md`

- [ ] **Step 1: 撰写文档**

```markdown
# NapCat APK 集成说明

NapCatQQ 无法作为代码库内嵌进同步器 App 进程（Node + NTQQ 需要 Linux 用户态）。
本项目采用"一体化 APK 组合"：NapCat 跑在手机本机的 Termux + proot 容器内，同步器 App 通过
`ws://127.0.0.1:3001` 连接它。

## 1. 获取 NapCat APK

社区已有开箱即用方案（基于 Termux 封装，无需 root、无需电脑）：

- 项目：https://github.com/linger-su/astrbot-termux
- Releases 下载最新版 APK，安装后按提示等待首次初始化（约 5-15 分钟，需 WiFi）。
- 初始化完成后，NapCat WebUI 地址：http://localhost:6099 （扫码登录 QQ）

> 也可自行用 NapCat-Installer 脚本在 Termux 内安装：https://github.com/NapNeko/NapCat-Installer

## 2. OneBot 服务配置

1. 打开 NapCat WebUI（http://localhost:6099），扫码登录 QQ。
2. 进入"网络配置"，新建 WebSocket 服务，端口设为 **3001**。
3. 记录 access token（可选，建议开启）。
4. 保存后确认 OneBot 服务显示"在线"。

## 3. 与同步器对接

1. 在同步器 App 中确认 NapCat WS 地址为 `ws://127.0.0.1:3001`，HTTP 地址为 `http://127.0.0.1:3000`。
2. 如开启 token，将同一 token 填入同步器设置页。
3. 点击"探测 NapCat"确认在线。

## 4. 常见问题

- 端口不通：确认 NapCat WebUI 已配置并保存，且 QQ 已扫码登录。
- 杀后台：将 NapCat APK 加入电池优化白名单。
- 被封风险：NapCat 基于逆向协议，请使用 QQ 小号，避免高频操作。
```

- [ ] **Step 2: 提交**

```bash
git add docs/napcat-apk.md
git commit -m "docs: NapCat APK 集成说明"
```

---

### Task 2: docs/signing.md 签名提取与打包

**Files:**
- Create: `docs/signing.md`

- [ ] **Step 1: 撰写文档**

```markdown
# 签名与包名要求

互联（interconnect）通信的硬性前提：**手环快应用 manifest.json 的 package 与 Android 同步器
App 的 applicationId 完全一致，且 rpk 用与手机 App 相同的证书签名。**

本项目统一使用包名：`com.example.bandqq`。

## 1. 从 jks 提取签名

```bash
# 1. jks → p12
keytool -importkeystore -srckeystore keystore.jks -destkeystore keystore.p12 \
  -srcstoretype jks -deststoretype pkcs12

# 2. p12 → pem
openssl pkcs12 -nodes -in keystore.p12 -out keystore.pem

# 3. 从 keystore.pem 分离：
#    -----BEGIN PRIVATE KEY----- 内容 → sign/debug/private.pem
#    -----BEGIN CERTIFICATE----- 内容 → sign/debug/certificate.pem
#    （release 版同理放到 sign/release）
```

## 2. 手环 rpk 打包签名

- 在 AIoT-IDE 的"签名配置"中，将私钥与证书指向从第 1 步分离出的
  `sign/debug/private.pem` 与 `sign/debug/certificate.pem`。
- 打包出的 rpk 才能与同步器 App 正常互联。

## 3. 注意事项

- 调试通信时必须用配套证书打包手机 App 和手环 rpk，否则通信被拒绝。
- release 版 rpk 请始终使用同一证书。
- 真机重装建议先卸载旧包再安装，保证彻底替换。
```

- [ ] **Step 2: 提交**

```bash
git add docs/signing.md
git commit -m "docs: 签名提取与打包说明"
```

---

### Task 3: 项目 README 与端到端验证

**Files:**
- Create: `README.md`

- [ ] **Step 1: 撰写 README.md**

```markdown
# 小米手环9 QQ 消息收发

手环端 Vela 快应用 + Android 同步器 + NapCatQQ 的三端 QQ 消息小程序。
全部可运行在一部 Android 手机 + 小米手环 9 上（NapCat 跑在手机 Termux 容器内）。

## 架构

```
小米手环9 (Vela 快应用) ⇄ Bluetooth interconnect ⇄ 手机
                                               │
                                    同步器 App（自研，互联+OneBot 客户端）
                                      │  OneBot WS (127.0.0.1:3001)
                                NapCat APK（Termux+proot+NapCat）
                                      │
                                     QQ 服务器
```

## 目录

| 目录 | 说明 |
|---|---|
| `band-qq/` | 手环端 Vela 快应用（AIoT-IDE 打开） |
| `android-sync/` | Android 同步器（Android Studio 打开） |
| `docs/` | 设计文档、NapCat 集成、签名说明 |

## 快速开始

1. **NapCat**：安装 NapCat APK（见 `docs/napcat-apk.md`），扫码登录 QQ，WebUI 配置 OneBot WS 端口 3001。
2. **同步器**：Android Studio 打开 `android-sync/`，改 applicationId/签名与你手环一致，安装到手机。
3. **手环端**：AIoT-IDE 打开 `band-qq/`，按 `docs/signing.md` 配签名打包 rpk，安装到手环。
4. **联调**：手机开同步器 → 手环打开应用 → 另一 QQ 号发消息 → 手环收到 → 手环发快捷回复 → 对方收到。

## 设计文档

完整设计见 `docs/superpowers/specs/2026-08-03-band-qq-design.md`，实现计划见 `docs/superpowers/plans/`。
```

- [ ] **Step 2: 提交**

```bash
git add README.md
git commit -m "docs: 项目 README 与快速开始"
```
