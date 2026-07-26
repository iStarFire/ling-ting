package com.tingyiting.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 暖色调封面底色（背景, 文字），按书名散列取色，让书架有视觉区分。 */
private val CoverPalettes = listOf(
    Color(0xFFFFDBCF) to Color(0xFF5D2B18), // 陶土
    Color(0xFFF1E2A7) to Color(0xFF4A421A), // 麦黄
    Color(0xFFD9E7CB) to Color(0xFF3A4D2C), // 抹茶
    Color(0xFFD6E3F1) to Color(0xFF2F4156), // 雾蓝
    Color(0xFFF2DBE9) to Color(0xFF54344A)  // 藕紫
)

private fun coverColors(title: String): Pair<Color, Color> =
    CoverPalettes[(title.hashCode() and 0x7FFFFFFF) % CoverPalettes.size]

/** 用书名首字生成的占位封面：无真实封面资源时的统一视觉方案。 */
@Composable
fun BookCover(
    title: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    fontSize: TextUnit = 24.sp
) {
    val (background, foreground) = remember(title) { coverColors(title) }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(background),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title.trim().firstOrNull()?.toString() ?: "听",
            color = foreground,
            fontWeight = FontWeight.Bold,
            fontSize = fontSize
        )
    }
}
