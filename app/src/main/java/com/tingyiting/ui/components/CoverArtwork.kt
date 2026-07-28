package com.tingyiting.ui.components

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.collection.LruCache
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
 * 同一进程生命周期内有效；进程被杀后清空。
 */
private val coverBitmapCache = object : LruCache<String, ImageBitmap>(maxSize = 8 * 1024 * 1024) {
    override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
}

/**
 * 封面展示组件。
 *
 * 封面图存于应用私有目录，[coverUrl] 为 file:// 或 content:// 本地路径。
 *
 * 加载策略：首帧同步查内存缓存（map 查寻，不阻塞主线程）；
 * 命中 → 直接展示；未命中 → 异步从磁盘加载，用 [Crossfade] 平滑过渡。
 * 避免同步 I/O 在主线程阻塞导致首次进入播放页卡顿。
 */
@Composable
fun CoverArtwork(
    title: String,
    coverUrl: String,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 12.dp,
    fallbackFontSize: TextUnit = 24.sp
) {
    val context = LocalContext.current
    // 首帧：仅查内存缓存（无 I/O，不阻塞主线程）
    val imageState = remember(coverUrl) { mutableStateOf(coverBitmapCache.get(coverUrl)) }

    // 缓存未命中 → 后台从磁盘加载
    LaunchedEffect(coverUrl) {
        if (imageState.value != null || coverUrl.isBlank()) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(coverUrl))?.use { input ->
                    BitmapFactory.decodeStream(input)?.asImageBitmap()
                }
            }.getOrNull()?.also { coverBitmapCache.put(coverUrl, it) }
        }
        imageState.value = bitmap
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Crossfade(targetState = imageState.value, label = "cover-crossfade") { currentImage ->
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