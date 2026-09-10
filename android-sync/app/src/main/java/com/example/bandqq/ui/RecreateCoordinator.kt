package com.example.bandqq.ui

/**
 * 推入页跨 recreate 恢复协调器（v2.4.7）：
 * 预测性返回开关等设置项需要 activity.recreate() 才能让系统开关生效，
 * 而推入态故意用 remember（非 rememberSaveable，v2.4.5 防重建首帧高危窗口），
 * 重建后本会直接回到主页 —— 表现为「点开关被踢回上级菜单」。
 * 修复：recreate 前记录当前推入页，重建后 BandQQApp 消费该标记自动重新推入，
 * 用户视角是「刷新后仍停留在设置页」，不再有被退出的错觉。
 */
object RecreateCoordinator {
    /** 待恢复的推入页："theme" / "keepalive" / "crashlog"，null 表示无 */
    var reopenScreen: String? = null

    /** 同进程标记：仅用于区分 recreate 与冷启动（冷启动必须为 null，不恢复推入态） */
    var armed: Boolean = false
}
