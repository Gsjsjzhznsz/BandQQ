# 手环端 QQ 助手（Vela 快应用）

小米手环 9 上的 QQ 消息收发小程序，通过蓝牙 interconnect 通道与手机端同步器 App 通信。

## 导入与编译

1. 安装小米 AIoT-IDE（小米官方 Vela JS 应用开发工具，基于 VS Code，官网 `iot.mi.com/vela`），导入本目录 `band-qq/`。
2. 在 AIoT-IDE 中打开 `manifest.json`，确认：
   - `package` 为 `com.example.bandqq`（必须与同步器 App 的 `applicationId` 一致）
   - `config.designWidth` 为 192
3. 连接小米手环 9，点击「运行到设备」，AIoT-IDE 会自动构建并签名安装 rpk。

> 若手环为首次安装，需先在「小米运动健康」中绑定手环并开启快应用调试（开发者选项）。

## 真机调试步骤

1. 手机安装并启动同步器 App（见项目根 `README.md`），确认 NapCat 在线、互联已连接。
2. 手环端运行本快应用，首页显示「已连接 · QQ助手」即代表互联通道就绪。
3. 使用另一 QQ 号发消息，验证手环收到并展示；从手环快捷回复，验证对方收到。

## 单测

依赖 Node.js（>=18），在本目录运行：

```bash
node --test test/*.test.js
```

预期：protocol / store / api 三个测试文件全部 PASS。

## 与同步器的配对要求

- 包名一致：手环 `manifest.package` == 同步器 `applicationId` == `com.example.bandqq`
- 签名一致：rpk 需用与同步器 APK 相同的 `.jks` 证书签名（见 `docs/signing.md`）
- 连接由系统自动建立（interconnect），小米运动健康仅负责蓝牙配对与连接维护
