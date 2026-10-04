# 签名与包名要求

互联（interconnect）通信的硬性前提：**手环快应用 manifest.json 的 package 与 Android 同步器
App 的 applicationId 完全一致，且 rpk 用与手机 App 相同的证书签名。**

本项目统一使用包名：`com.example.bandqq`。

> **本仓库现状**：`scripts/rpk-pack.ps1` 已生成 `keystore.jks`（仓库根目录，已 gitignore），
> 密码 `bandqq123`、别名 `bandqq`。Android `app/build.gradle.kts` 的 release 签名已指向该证书。
> 因此手环侧只需从同一 `keystore.jks` 提取 pem 供 AIoT-IDE 使用，即可保证与 APK 证书一致。

## 1. 从 jks 提取签名

```bash
# 1. jks → p12（keystore 密码均为 bandqq123）
keytool -importkeystore -srckeystore keystore.jks -destkeystore keystore.p12 \
  -srcstoretype jks -deststoretype pkcs12 -storepass bandqq123 -srcstorepass bandqq123

# 2. p12 → pem
openssl pkcs12 -nodes -in keystore.p12 -out keystore.pem

# 3. 从 keystore.pem 分离：
#    -----BEGIN PRIVATE KEY----- 内容 → sign/debug/private.pem
#    -----BEGIN CERTIFICATE----- 内容 → sign/debug/certificate.pem
#    （release 版同理放到 sign/release）
```

## 2. 手环 rpk 打包签名

**本仓库已支持命令行一键打包签名（无需 AIoT-IDE GUI）：**

`band-qq/` 已用 `aiot-toolkit`（AIoT-IDE 官方命令行打包工具）管理工程，且源码为标准 `src/` 布局：

```bash
cd band-qq
npm install            # 安装 aiot-toolkit（首次）
npm run release        # 打包并签名，产物在 band-qq/dist/com.example.bandqq.release.1.0.0.rpk
```

或一键（自动从 keystore.jks 提取签名并打包到 dist/）：

```powershell
.\scripts\rpk-pack.ps1
```

`aiot release`（production 模式）会优先使用 `sign/release/{private,certificate}.pem`，其次 `sign/` 根目录；
`aiot build`（开发模式）用 `sign/debug/`。rpk-pack.ps1 已把同一 `keystore.jks` 的 pem 提取到两处，保证与
Android release APK 证书完全一致。

**方式 B（可选，可视化）：** 在 AIoT-IDE 中打开 `band-qq` 工程，点击「发布」。AIoT-IDE 会自动读取
`sign/debug/`（或 `sign/release/`）下的 `private.pem` 与 `certificate.pem` 进行签名。

> 提示：`aiot-toolkit` 对 Node 版本要求 ≥ 18；本地开发建议统一使用 keystore.jks 的单证书，避免目录混乱。

## 3. 注意事项

- 调试通信时必须用配套证书打包手机 App 和手环 rpk，否则通信被拒绝。
- release 版 rpk 请始终使用同一证书。
- 真机重装建议先卸载旧包再安装，保证彻底替换。
