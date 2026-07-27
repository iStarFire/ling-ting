package com.tingyiting.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.collection.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 进程级封面 bitmap 内存缓存。
 * 避免每次 AnimatedVisibility 重新组合时 produceState 从 null 开始，
 * 导致短暂显示文字占位 BookCover 再切换为真图的闪烁。
 */
private val coverBitmapCache = object : LruCache<String, ImageBitmap>(maxSize = 8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

@Composable
fun CoverArtwork(
    title: String,
    coverUrl: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    fallbackFontSize: TextUnit = 24.sp
) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(
        initialValue = coverBitmapCache.get(coverUrl),
        coverUrl
    ) {
        value = if (coverUrl.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(Uri.parse(coverUrl))?.use { input ->
                        BitmapFactory.decodeStream(input)?.asImageBitmap()
                    }
                }.getOrNull()?.also { bitmap ->
                    coverBitmapCache.put(coverUrl, bitmap)
                } ?: coverBitmapCache.get(coverUrl)
            }
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Crossfade(targetState = image, label = "cover-crossfade") { currentImage ->
            if (currentImage != null) {
                Image(
                    bitmap = currentImage,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                BookCover(
                    title = title,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = cornerRadius,
                    fontSize = fallbackFontSize
                )
            }
        }
    }
}