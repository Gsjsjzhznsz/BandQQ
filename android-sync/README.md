# Android 同步器（QQ 同步器）

手环 QQ 助手 的手机端同步器：通过蓝牙互联桥接手环与 SnowLuma（OneBot v11）。

## 环境要求

- Android Studio（Ladybug 或更新）+ JDK 17+
- Android SDK：`compileSdk 34`、`minSdk 26`
- Gradle 8.13（由 wrapper 自动下载）

## 导入与构建

1. 用 Android Studio 打开本目录 `android-sync/`。
2. 等待 Gradle 同步完成，连接真机（或模拟器），点击 Run。
3. 产物路径：`app/build/outputs/apk/debug/app-debug.apk`。

命令行构建：

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest   # 运行纯 JVM 单元测试
```

## 配置

打开 App 后设置：
- **SnowLuma WS 地址**：默认 `ws://127.0.0.1:3001`
- **HTTP 地址**：默认 `http://127.0.0.1:3000`
- **Access Token**：SnowLuma 若开启鉴权则填写，否则留空

点击「自动探测（局域网）」扫描本机 / 局域网发现 SnowLuma；也可用「测试 SnowLuma 连接」做真实连接测试。

点击「启动同步」启动前台服务，保持互联通道与 OneBot 长连接。

## 签名要求

- `applicationId` 必须为 `com.example.bandqq`（与手环端 manifest `package` 一致，互联硬性要求）。
- 调试包默认用 Android 调试签名；**如需安装到真手环，release 包需用 `.jks` 证书签名，且与手环 rpk 使用同一证书**（见 `../docs/signing.md` 与 `keystore.example.properties`）。
- 本机无 Android SDK：请在装有 Android Studio 的机器上执行上述构建验证。

## 单元测试

`gradlew :app:testDebugUnitTest` 会运行：
- `OneBotParserTest`：协议解析与 CQ 码降级
- `OneBotClientTest`：MockWebServer 验证 HTTP 发送
- `MessageStoreTest`：存储与会话聚合
- `MessageBrokerTest`：手环帧处理与历史/会话响应
