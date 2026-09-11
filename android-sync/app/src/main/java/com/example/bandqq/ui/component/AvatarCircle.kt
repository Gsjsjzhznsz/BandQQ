package com.example.bandqq.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.example.bandqq.sync.MessageStore
import top.yukonga.miuix.kmp.basic.Text

/**
 * 头像圆（v2.7.0）：与手环端同一套预计算字段 —— 色相由 targetId 稳定散列，
 * 首字符取名称首字。手机端联系人/聊天记录列表与手环端视觉统一。
 * 固定尺寸 Box + contentAlignment 居中，保证任何字体度量下都不偏移（修复图标偏下观感）。
 */
@Composable
fun AvatarCircle(
    name: String,
    id: String,
    size: Dp,
    fontSize: TextUnit = 16.sp,
    modifier: Modifier = Modifier,
) {
    val hue = MessageStore.Display.hueOf(id)
    val bg = Color.hsl(hue = hue.toFloat(), saturation = 0.52f, lightness = 0.40f)
    Box(
        modifier = modifier
            .size(size)
            .background(bg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = MessageStore.Display.avatarChar(name, id),
            color = Color.White,
            fontSize = fontSize,
            fontWeight = FontWeight.Medium,
        )
    }
}
