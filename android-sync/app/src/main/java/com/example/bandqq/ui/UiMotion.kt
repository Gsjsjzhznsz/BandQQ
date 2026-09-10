package com.example.bandqq.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.roundToInt

/** 动画速度倍率（0.5 ~ 2.0，越大越快），由 MainActivity 按配置注入 */
val LocalMotionSpeed = staticCompositionLocalOf { 1f }

/** 列表级联入场的逐项间隔（ms，0 ~ 200），由 MainActivity 按配置注入 */
val LocalMotionStagger = staticCompositionLocalOf { 100 }

/**
 * 统一入场动效（速度/延迟均可在「主题与外观 → 动画」调节）：
 * - 时长 = 基础时长 / 动画速度；
 * - 逐项延迟 = index × 级联间隔 / 动画速度；
 * - 效果：延迟后淡入并上移（单次播放），用于四屏卡片/列表级联入场。
 */
fun Modifier.listItemReveal(entered: Boolean, index: Int): Modifier = composed {
    val speed = LocalMotionSpeed.current.coerceIn(0.5f, 2f)
    val stagger = LocalMotionStagger.current.coerceIn(0, 300)
    // 级联延迟只作用于前几项：列表很长（联系人可达数百条）时，
    // 不封顶会让末项等数秒才开始出现（index×间隔线性放大）
    val effectiveIndex = index.coerceAtMost(6)
    val delay = (effectiveIndex * stagger / speed).roundToInt()
    val duration = (300 / speed).roundToInt()
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(duration, delayMillis = delay),
        label = "listRevealAlpha",
    )
    val offsetY by animateFloatAsState(
        targetValue = if (entered) 0f else 20f,
        animationSpec = tween(duration, delayMillis = delay),
        label = "listRevealOffset",
    )
    graphicsLayer {
        this.alpha = alpha
        translationY = offsetY
    }
}
