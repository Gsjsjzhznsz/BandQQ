# 设计:去除 NapCat,专一 SnowLuma + 分离 WS/HTTP token

- 日期: 2026-08-05
- 状态: 已确认

## 背景与目标

当前 App 支持 NapCat 与 SnowLuma 双协议端(上一计划「SnowLuma 支持 + 局域网」),用户实际只使用 SnowLuma。
本计划:

1. **完全移除 NapCat**——代码、UI、枚举、DataStore 键、测试用例、文档一并清除。
2. **SnowLuma 拆独立 token**——WS 地址与 HTTP 地址各自维护独立的 Access Token,分别用于对应连接(http token 管 HTTP 发消息/拉联系人、WS token 管实时收消息)。
3. **HTTP 端口独立**——SnowLuma 的 HTTP API 与 WS 服务端为独立端口,App 的 WS/HTTP 地址填不同端口。
4. 配置形态降为**单端点单组配置**,去掉协议端侧切 RadioGroup 与 activeType。

## 数据设计

### EndpointConfig(新,4 字段)
```kotlin
data class EndpointConfig(
    val wsUrl: String,
    val wsToken: String,
    val httpUrl: String,
    val httpToken: String
)
```

### AppConfig(新,单端点)
```kotlin
data class AppConfig(
    val endpoint: EndpointConfig =
        EndpointConfig("ws://127.0.0.1:3001", "", "http://127.0.0.1:3000", "")
)
```

### 删除项
- `ProtocolType` 枚举(NAPCAT / SNOWLUMA)
- `AppConfig.activeType` 及 `getActiveEndpoint()`
- `AppConfig.napcat` / `AppConfig.snowluma` 嵌套字段

### DataStore 键映射(ConfigManager)
| 新键 | 说明 | 旧键(删除) |
| --- | --- | --- |
| `ws_url` | WS 地址 | `snowluma_ws_url` |
| `ws_token` | WS Access Token | `snowluma_token` |
| `http_url` | HTTP 地址 | `snowluma_http_url` |
| `http_token` | HTTP Access Token | — |
| — | — | `token`(旧 NapCat), `active_type` |

旧键全部废弃,不再读写;新键缺失时用默认值兜底(旧数据不迁移,用户重新配置)。

## 协议探测( GameProtocolDetector)

`detect(...)` 去掉 `type` 参数,只服务 SnowLuma:

```kotlin
suspend fun detect(preferred: String? = null, hosts: List<String> = detectHosts(), ports: IntArray = defaultPorts()): EndpointConfig?
```
- `defaultPorts()`:SnowLuma 常见端口集合(含 WS 3001 与 HTTP 独立端口,如 3000/8080 等)。
- 探测含两类候选:
  - **WS 候选**:`ws://host:3001`(及检测到的 HTTP 端口经 `http→ws` 推导)→ WebSocket 握手探测。
  - **HTTP 候选**:`http://host:<常见HTTP端口>` → `GET /api/get_version` 返回 JSON 判定。
- `toConfig` 填 wsUrl / httpUrl,**token 留空**——由 UI 用户在保存/回填时提供。
- 保留「HTTP 无响应时尝试换成 ws 握手」的 SnowLuma 逻辑。

## 连接行为( OneBotClient)

- `connectOnce()`:WS 握手携带 `config.wsToken`(若非空)。
- `sendMessage()` 与 `requestApi()`:HTTP 请求携带 `config.httpToken`(若非空)。

## App UI

### activity_main.xml
删除:
- `protocolGroup`、`napcatRadio`、`snowlumaRadio`
- NapCat 配置组(wsInput/httpInput/tokenInput)
- SnowLuma 组(snowWsInput/snowHttpInput/snowTokenInput)
- `testNapBtn` / `testSnowBtn`

新增/保留:
- 单组「SnowLuma 配置」字段:
  - `wsInput`(WS 地址)、`wsTokenInput`(WS token)
  - `httpInput`(HTTP 地址)、`httpTokenInput`(HTTP token)
- `saveBtn`、`probeBtn`(自动探测 局域网)、`testBtn`(测试连接)
- 其余(statusText/startBtn/checkBandBtn/stopBtn/chatHistoryBtn/contactManagerBtn/clearHistoryBtn/logView)保留不动。

### MainActivity.kt
- `loadConfig()`:回填 4 字段。
- `saveBtn`:`AppConfig(endpoint = EndpointConfig(ws,wsToken,http,httpToken))` 保存。
- `probeNapCat()` → `probe(lan)`:`GameProtocolDetector.detect()` 局域网探测,成功后回填 ws/http 地址与端口;**token 保留用户已输入值**(不覆盖)。
- `testConnection()`:`detect(preferred = ...)` 本机快速测试。
- `refreshStatus()`:归一显示,无 activeType 分支。

## 手环端( band-qq)

### settings.ux
- 行标签「NapCat」→「协议端」;变量 `napcatText` → `protocolText`。
- 仍读 `store.getConnectState().protocol` 显示在线/离线。

## 测试

- `AppConfigTest`:重写为单端点 4 字段默认值断言 + token 存取(删除 NapCat/activeType 用例)。
- `GameProtocolDetectorTest`:删除 NapCat 用例,适配新签名与端口集。
- `MessageBrokerTest` / store.test.js / api.test.js:保持(protocol 字段语义不变)。
- 手环 `npm test`、Android `testDebugUnitTest` 全绿。

## 交付

- 重建 `app-debug.apk` / `app-release.apk` / `bandqq.release.rpk`。
- 更新 `INSTALL.txt` 为单协议端(SnowLuma)+ 独立 token 说明,重新归档桌面 v7 交付 zip。