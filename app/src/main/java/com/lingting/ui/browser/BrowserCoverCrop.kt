package com.lingting.ui.browser

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lingting.data.model.CoverCrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 选图后调用方传入 Uri 以弹出裁剪 sheet；用户确认时回调 [CoverCrop]。
 *
 * 该文件是从 PlayerScreen 拷贝过来的私有复刻（避免跨模块大改动），后续可抽取到
 * ui/components/CoverCrop.kt 共享。改动裁剪控件时需同步两边。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserCoverCropSheet(
    uri: Uri,
    isSaving: Boolean,
    onConfirm: (CoverCrop) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val (srcW, srcH) = resolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(input, null, opts)
                opts.outWidth to opts.outHeight
            } ?: (0 to 0)
            val targetMax = 1024
            val sample = computeCropInSampleSize(srcW, srcH, targetMax)
            resolver.openInputStream(uri)?.use { input ->
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                BitmapFactory.decodeStream(input, null, opts)
            }
        }
    }
    var crop by remember(uri) { mutableStateOf<CoverCrop?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "裁剪封面",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
            )
            if (bitmap == null) {
                Box(
                    modifier = Modifier
                        .size(280.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else {
                BrowserSquareCropPreview(
                    bitmap = bitmap!!,
                    onCropChanged = { crop = it }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 18.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss, enabled = !isSaving) {
                    Text("取消")
                }
                TextButton(
                    onClick = { crop?.let(onConfirm) },
                    enabled = crop != null && !isSaving
                ) {
                    Text(if (isSaving) "保存中..." else "保存")
                }
            }
        }
    }
}

@Composable
private fun BrowserSquareCropPreview(
    bitmap: Bitmap,
    onCropChanged: (CoverCrop) -> Unit
) {
    val density = LocalDensity.current
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(bitmap) { mutableFloatStateOf(1f) }
    var offsetX by remember(bitmap) { mutableFloatStateOf(0f) }
    var offsetY by remember(bitmap) { mutableFloatStateOf(0f) }

    val fitScale = if (boxSize.width > 0 && boxSize.height > 0) {
        max(
            boxSize.width.toFloat() / bitmap.width.toFloat(),
            boxSize.height.toFloat() / bitmap.height.toFloat()
        )
    } else 1f
    val actualScale = fitScale * zoom
    val displayWidthPx = bitmap.width * actualScale
    val displayHeightPx = bitmap.height * actualScale
    val maxOffsetX = max(0f, (displayWidthPx - boxSize.width) / 2f)
    val maxOffsetY = max(0f, (displayHeightPx - boxSize.height) / 2f)
    val clampedOffsetX = offsetX.coerceIn(-maxOffsetX, maxOffsetX)
    val clampedOffsetY = offsetY.coerceIn(-maxOffsetY, maxOffsetY)

    LaunchedEffect(bitmap, boxSize, zoom, clampedOffsetX, clampedOffsetY) {
        if (boxSize.width > 0 && boxSize.height > 0) {
            val imageLeft = boxSize.width / 2f - displayWidthPx / 2f + clampedOffsetX
            val imageTop = boxSize.height / 2f - displayHeightPx / 2f + clampedOffsetY
            val cropLeft = ((0f - imageLeft) / actualScale).roundToInt()
            val cropTop = ((0f - imageTop) / actualScale).roundToInt()
            val cropSize = (boxSize.width / actualScale).roundToInt()
                .coerceAtMost(bitmap.width)
                .coerceAtMost(bitmap.height)
            val safeLeft = cropLeft.coerceIn(0, max(0, bitmap.width - cropSize))
            val safeTop = cropTop.coerceIn(0, max(0, bitmap.height - cropSize))
            onCropChanged(
                CoverCrop(
                    left = safeLeft,
                    top = safeTop,
                    size = cropSize
                )
            )
        }
    }

    Box(
        modifier = Modifier
            .size(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .onSizeChanged { boxSize = it }
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    val w = boxSize.width.toFloat()
                    val h = boxSize.height.toFloat()
                    if (w <= 0f || h <= 0f) return@detectTransformGestures
                    val currentFit = max(w / bitmap.width, h / bitmap.height)
                    val newZoom = (zoom * gestureZoom).coerceIn(CROP_FIT_MIN, CROP_ZOOM_MAX)
                    val newActual = currentFit * newZoom
                    val newDisplayW = bitmap.width * newActual
                    val newDisplayH = bitmap.height * newActual
                    val newMaxX = max(0f, (newDisplayW - w) / 2f)
                    val newMaxY = max(0f, (newDisplayH - h) / 2f)
                    val curClampedX = offsetX.coerceIn(-newMaxX, newMaxX)
                    val curClampedY = offsetY.coerceIn(-newMaxY, newMaxY)
                    val candidateX = curClampedX + pan.x
                    val candidateY = curClampedY + pan.y
                    zoom = newZoom
                    offsetX = candidateX.coerceIn(-newMaxX, newMaxX)
                    offsetY = candidateY.coerceIn(-newMaxY, newMaxY)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (boxSize.width > 0 && boxSize.height > 0) {
            val aspectRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
            val (imageWp, imageHp) = if (aspectRatio >= 1f) {
                displayWidthPx to (displayWidthPx / aspectRatio)
            } else {
                (displayHeightPx * aspectRatio) to displayHeightPx
            }
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(
                        width = with(density) { imageWp.toDp() },
                        height = with(density) { imageHp.toDp() }
                    )
                    .offset {
                        IntOffset(clampedOffsetX.roundToInt(), clampedOffsetY.roundToInt())
                    }
            )
        }
    }
}

private const val CROP_FIT_MIN = 0.5f
private const val CROP_ZOOM_MAX = 4f

private fun computeCropInSampleSize(srcW: Int, srcH: Int, targetMax: Int): Int {
    if (srcW <= 0 || srcH <= 0) return 1
    val longest = maxOf(srcW, srcH)
    var sample = 1
    while (longest / (sample * 2) >= targetMax) {
        sample *= 2
    }
    return sample
}
