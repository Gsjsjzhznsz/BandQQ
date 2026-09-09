package com.example.bandqq.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer

/**
 * 列表项依次入场动效：按 index 延迟后淡入并上移（单次播放）。
 * 用于联系人/历史会话/设置项列表，形成级联入场效果。
 */
fun Modifier.listItemReveal(entered: Boolean, index: Int, delayMs: Long = 60L): Modifier = composed {
    val delay = (index * delayMs).toInt()
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(300, delayMillis = delay),
        label = "listRevealAlpha",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (entered) 0f else 20f,
        animationSpec = tween(300, delayMillis = delay),
        label = "listRevealOffset",
    )
    graphicsLayer {
        this.alpha = alpha
        translationY = offsetY
    }
}
