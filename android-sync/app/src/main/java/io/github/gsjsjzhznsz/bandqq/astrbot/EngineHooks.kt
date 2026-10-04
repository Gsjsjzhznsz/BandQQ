package io.github.gsjsjzhznsz.bandqq.astrbot

/**
 * v2.24.0 flavor 解耦钩子。
 *
 * astrbot-engine 模块只挂在 bundledImplementation 上（companion 瘦包不可见），
 * 而 main sourceSet 的 SyncService 需要在 OneBot WS 真实连通时通知引擎状态机
 * （修「NapCat 启动完成还在显示启动」——状态卡滞留 92% 启动中）。
 *
 * 本钩子由 main 声明、bundled 侧在 AstrBot 标签页首帧组合时挂实现：
 *   EngineHooks.napcatConnected = { EngineManager.onNapcatConnected() }
 * companion 瘦包永远不挂（保持 null），调用点全部空安全跳过。
 */
object EngineHooks {

    /** OneBot WS onOpen 时由 SyncService 调用；bundled 挂 EngineManager.onNapcatConnected */
    @Volatile
    var napcatConnected: (() -> Unit)? = null
}
