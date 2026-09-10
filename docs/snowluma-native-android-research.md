# SnowLuma 移植原生 Android 可行性研究（v2.4.5）

> 结论先行：**进程内嵌不可行（维持 v2.1 调研结论）；推荐落地路径 = Termux + proot 一键脚本，让协议端跑在手机本机**。
> 本文基于 SnowLuma 1.14.15 源码（github.com/SnowLuma/SnowLuma）核实，替代凭印象的旧结论。

## 1. SnowLuma 现行架构（源码核实）

```
手机 QQ 桌面进程 (Linux/Windows NTQQ)
   ↑ ① native 注入: snowluma-linux-arm64.so（manual map，改写 QQ 进程内存）
   │     由 .node 原生 addon 的 loadModuleManual(pid, dylibPath) 完成
   │     bridge/src/injector.ts · qq-hook-client.ts · process-enumerator.ts
SnowLuma 运行时 (TypeScript, Node ≥ 22.13)
   │  ② 包嗅探 QQ 进程内的 NTQQ 协议包 → packages/protocol（NTQQ 结构化解析）
   │     packages/proto-defs（协议定义）· proton（编译期 protobuf 零开销编解码）
   │  ③ 转换为 OneBot v11 动作/事件
   ↓
packages/onebot · websocket · webui（WS/HTTP/WebUI/SDK/MCP 统一出口）
```

关键事实：
- 注入目标是**桌面版 NTQQ 进程**（Windows/Linux x64+arm64），进程枚举按桌面 QQ 进程名匹配；
  Android QQ 是完全不同的二进制，不存在可注入目标。
- 注入方式 = manual mapping（等价 ptrace 写内存 + 远程线程），**非 root Android 的 seccomp
  直接禁止 ptrace**，任何"App 内直接注入"路线在系统层就被封死。
- TypeScript 体量：protocol/core/bridge 三包合计 ~4.1 万行 TS，外加闭源 native addon
  （.node/.so 二进制，无源码）——"翻译成 Kotlin 重写协议栈"既无源码依据，也无维护可行性。

## 2. 三条路线评估

| 路线 | 说明 | 结论 |
| --- | --- | --- |
| A. App 内嵌 Node 运行时 | nodejs-mobile 停在 Node 18 < 要求的 22.13；且注入仍需 ptrace | ✗ 双重不可行 |
| B. Kotlin 原生重写协议栈 | native addon 闭源；NTQQ 协议逆向体量巨大且随 QQ 版本漂移 | ✗ 不可行 |
| C. 手机本机 Termux + proot Ubuntu + 官方 Linux arm64 发行包 + Linux QQ | SnowLuma 官方即支持 linux-arm64；无需电脑，协议端与同步器同机 | ✓ **推荐** |

路线 C 的意义：**整条链路收敛到一部手机**（Termux: SnowLuma + Linux QQ ⇄ 本机
127.0.0.1:3001/3000 ⇄ 同步器 App ⇄ 手环），延迟最低、部署最简，App 侧零改动
（默认地址本来就是 127.0.0.1）。

## 3. 路线 C 落地：一键脚本

仓库新增 `scripts/snowluma-termux.sh`（也在 App 设置页说明文案中指引）：
Termux 内执行后自动完成 proot Ubuntu → 依赖 → SnowLuma 发行包下载 → QQ 安装提示 → 启动。
脚本只调用官方发布物，不修改不再分发 SnowLuma 二进制（遵守其 EULA：非商业、
分发修改版需书面授权）。

手机端使用步骤（App 内「SnowLuma WebUI」保持不变，地址填 127.0.0.1:5099）：
1. 安装 Termux（F-Droid 版）→ `pkg install curl` → `bash <(curl 仓库脚本)`；
2. 脚本完成后 `./launcher.sh` 启动，浏览器打开 127.0.0.1:5099 扫码登录 QQ；
3. WebUI 里把 OneBot WS 反向地址填 `ws://127.0.0.1:3001`（或正向 WS，App 默认已对齐）；
4. 打开同步器 App → 启动同步 → 手环正常收发。

注意：proot 无真实 root，Linux QQ 图形界面靠 Xvfb 虚拟显示跑在后台；SnowLuma 官方
对 Termux 场景标注"不保证可用"，脚本已内置 xvfb 与字体依赖，失败时按脚本末尾提示
查看日志。省电策略可能杀 Termux 后台——App 内「后台保活向导」对 Termux 同样适用。

## 4. 对同步器 App 的反向借鉴（已落地/可落地）

- OneBot v11 事件全集以 SnowLuma 实测输出为准（docs/onebot-actions.md），
  后续接 `get_msg` 历史翻页、戳一戳等事件时按它的字段样本对齐解析器。
- 多账号思路（每账号独立会话/身份映射）：App 当前单账号，若未来支持，
  按 `self_id` 维度拆 MessageStore 分区即可，协议层无需变更。
