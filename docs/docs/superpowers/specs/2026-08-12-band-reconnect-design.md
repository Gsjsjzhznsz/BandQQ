# 设计：手环通道自动重连

日期：2026-08-12

## 目标

服务运行期间，只要手环已通过小米运动健康连接且手机同步器在运行，任何时候打开手环端软件都能正常收发消息。手环互联通道断连后自动周期性重连，直至恢复。

SnowLuma WS 重连保持现状（每 5 秒重试）。

## 现状分析

### 现有机制
- `OneBotClient.reconnect()`：WS 每 5 秒自动重试（已满足需求，不改动）
- `InterconnectBridge` 心跳：每 3 秒 ping，10 秒无 pong 判定离线

### 缺口
1. `InterconnectBridge.connect()` 只在服务启动与手动按钮时调用一次，失败后不重试
2. 心跳超时后仅 `onDisconnect()` 置离线，不重新建立通道
3. 初次发现节点为空时直接返回，之后手环连上蓝牙也不会自动补救

## 方案

在 `InterconnectBridge` 增加独立自动重连协程 `reconnectJob`，服务注册时启动、注销时取消。

### 重连循环

```
每 5 秒：
  if (SyncState.bandConnected) return        // 已在线，跳过
  if (connecting) return                     // 防止并发重连
  currentNode != null → auth() + openApp() + registerListener()  // 重新注册通道
  currentNode == null  → connect()           // 重新发现手环节点
```

### 关键点
- 复用现有流程：`auth()` 内部已链式调用 `openApp()` → `registerListener()`，无需新握手代码
- 限频防刷：`RECONNECT_INTERVAL_MS = 5000`，加 `connecting` 标志防重复触发
- 自动停止：重连循环挂在 `heartbeatScope` 下，`unregister()` 时一并取消
- 无节点兜底：初始未发现手环时周期 `connect()`，手环连上后自动恢复

## 覆盖场景
- 手环 app 被系统清理后重新打开 → 心跳超时 → 自动重连
- 手机蓝牙短暂断开再恢复 → 自动重连
- 服务启动时手环未连 → 周期尝试直到发现

## 不改动
- `OneBotClient` 重连（保持现状）
- 手环端软件（已有 onShow 主动拉取）
