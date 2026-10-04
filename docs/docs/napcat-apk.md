# NapCat 集成说明

NapCatQQ 无法作为代码库内嵌进同步器 App 进程（Node + NTQQ 需要 Linux 用户态）。
本项目采用"同机进程外部署"：NapCat 跑在手机本机的 Termux + proot 容器内，同步器 App 通过
`ws://127.0.0.1:3001` 连接它。

> ⚠️ 早前推荐的 astrbot-termux APK 因内置 Termux 签名/包名不一致，会报
> `Failed to get package context for the "com.termux" package`，已弃用。
> 请改用下方标准 Termux + 官方 Installer 方案。

## 1. 获取并安装（标准方案）

### 1.1 安装 Termux（必须 F-Droid 版）
- 访问 `https://f-droid.org/packages/com.termux/` 下载安装 Termux。
- **不要**使用 Google Play 版（已停迭代，包/签名易出问题）。
- 打开后执行 `pkg update && pkg upgrade -y`。

### 1.2 安装 NapCat（官方脚本）

在 Termux 内执行：

```sh
curl -o install.sh https://ncat.wiki/binary/install_script/install.sh && bash install.sh
```

> 详细图文步骤、登录、OneBot 配置与排障见 **`docs/napcat-usage.md`**。

## 2. OneBot 服务配置

1. 打开 NapCat WebUI（`http://127.0.0.1:6099`），扫码登录 QQ。
2. 进入「网络配置」，新建 WebSocket 服务：HTTP 端口 **3000**、WebSocket 端口 **3001**。
3. （可选）开启并记录 access token。
4. 保存后确认服务「在线」。

## 3. 与同步器对接

同步器 App 内置**自动探测**：点击「自动探测 NapCat」会自动扫描本机常见端口并用
`GET /api/get_version` 验证，成功后自动回填 `ws://127.0.0.1:3001` / `http://127.0.0.1:3000` 并保存。
若开启 token，填同一 token 后点「启动同步」。

## 4. 常见问题

- 端口不通：确认 NapCat WebUI 已配置保存、QQ 已登录、OneBot 服务「在线」。
- 杀后台：将 Termux 加入电池优化白名单。
- 自动探测误判/未找到：确认服务在线后重试；NapCat 可与本机其它服务并存。
- 被封风险：NapCat 基于逆向协议，请用 QQ 小号，避免高频操作（详见 napcat-usage.md 第 7 节）。