package com.tingyiting.ui.player

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tingyiting.data.model.CoverCrop
import com.tingyiting.ui.components.CoverArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.roundToInt

private const val LOADING_OVERLAY_ALPHA = 0.45f
private const val SEEK_INTERVAL_MS = 15_000L

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PlayerScreen(
    bookId: Long,
    onNavigateBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var showCoverSheet by remember { mutableStateOf(false) }
    var cropImageUri by remember { mutableStateOf<Uri?>(null) }
    var draggedFraction by remember { mutableStateOf<Float?>(null) }
    val trackSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val trackListState = rememberLazyListState()
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        cropImageUri = uri
    }

    LaunchedEffect(bookId) {
        if (bookId != 0L) viewModel.initialize(bookId)
    }
    LaunchedEffect(showTrackSheet) {
        if (showTrackSheet && state.tracks.isNotEmpty()) {
            trackListState.scrollToItem(state.currentTrackIndex.coerceIn(state.tracks.indices))
        }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.saveProgress() }
    }

    BoxWithConstraints {
        val isLandscape = maxWidth > maxHeight
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("") },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (isLandscape) Arrangement.Center else Arrangement.SpaceEvenly
            ) {
                if (!isLandscape) {
                    Artwork(
                        title = state.title,
                        coverUrl = state.coverUrl,
                        showLoading = state.isInitialLoading || state.isBuffering,
                        onEdit = { showCoverSheet = true },
                        modifier = Modifier.size(260.dp)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = if (isLandscape) 0.dp else 16.dp)
                    )
                    if (state.isPlaylist) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.trackTitle,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                state.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                state.playbackError?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(onClick = viewModel::retryPlayback) { Text("重试") }
                    }
                }

                PlaybackProgress(
                    currentPosition = state.currentPosition,
                    duration = state.duration,
                    draggedFraction = draggedFraction,
                    onFractionChange = { draggedFraction = it },
                    onSeekFinished = {
                        draggedFraction?.let { fraction ->
                            viewModel.seekTo((fraction * state.duration).toLong())
                        }
                        draggedFraction = null
                    },
                    enabled = !state.isInitialLoading && state.error == null
                )

                PlaybackControls(
                    state = state,
                    onPrevious = viewModel::prevTrack,
                    onSeekBack = { viewModel.seekBy(-SEEK_INTERVAL_MS) },
                    onPlayPause = viewModel::togglePlayPause,
                    onSeekForward = { viewModel.seekBy(SEEK_INTERVAL_MS) },
                    onNext = viewModel::nextTrack
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AssistChip(
                        onClick = { showSleepTimerDialog = true },
                        label = {
                            Text(state.sleepTimerRemaining?.let { "$it 分钟后停止" } ?: "定时")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (state.sleepTimerRemaining != null) {
                                    Icons.Default.Bedtime
                                } else Icons.Default.Timer,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    )
                    if (state.isPlaylist) {
                        AssistChip(
                            onClick = { showTrackSheet = true },
                            label = { Text("选集 ${state.currentTrackIndex + 1}/${state.trackCount}") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showSleepTimerDialog) {
        SleepTimerDialog(
            currentRemaining = state.sleepTimerRemaining,
            onSet = viewModel::setSleepTimer,
            onCancel = viewModel::cancelSleepTimer,
            onDismiss = { showSleepTimerDialog = false }
        )
    }

    if (showCoverSheet) {
        ModalBottomSheet(onDismissRequest = { showCoverSheet = false }) {
            Text(
                text = "编辑封面",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
            )
            ListItem(
                headlineContent = { Text("从豆瓣自动搜刮") },
                supportingContent = {
                    Text(
                        text = if (state.isCoverUpdating) "正在搜索并保存封面..." else "按书名搜索封面并保存到本地"
                    )
                },
                leadingContent = {
                    Icon(Icons.Default.Search, contentDescription = null)
                },
                modifier = Modifier.clickable(enabled = !state.isCoverUpdating) {
                    viewModel.scrapeCoverFromDouban()
                }
            )
            ListItem(
                headlineContent = { Text("选择本地图片") },
                supportingContent = { Text("从相册或文件中选择封面") },
                leadingContent = {
                    Icon(Icons.Default.Image, contentDescription = null)
                },
                modifier = Modifier.clickable(enabled = !state.isCoverUpdating) {
                    coverPicker.launch("image/*")
                    showCoverSheet = false
                }
            )
            state.coverError?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    cropImageUri?.let { uri ->
        CoverCropSheet(
            uri = uri,
            isSaving = state.isCoverUpdating,
            onConfirm = { crop ->
                viewModel.importLocalCover(uri, crop)
                cropImageUri = null
            },
            onDismiss = { cropImageUri = null }
        )
    }

    if (showTrackSheet && state.isPlaylist) {
        ModalBottomSheet(
            onDismissRequest = { showTrackSheet = false },
            sheetState = trackSheetState
        ) {
            Column(modifier = Modifier.fillMaxHeight(0.9f)) {
                Text(
                    text = "选集（共 ${state.trackCount} 集）",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                LazyColumn(
                    state = trackListState,
                    modifier = Modifier
                        .weight(1f)
                        .padding(bottom = 24.dp)
                ) {
                    itemsIndexed(state.tracks, key = { index, _ -> index }) { index, track ->
                        val isCurrent = index == state.currentTrackIndex
                        val completed = isTrackCompleted(track)
                        val progress = trackProgressPercent(track)
                        ListItem(
                            headlineContent = {
                                Text(
                                    text = track.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = if (isCurrent) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface
                                )
                            },
                            supportingContent = {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    if (track.duration > 0) Text(formatDuration(track.duration))
                                    when {
                                        completed -> Text("已播完", color = MaterialTheme.colorScheme.primary)
                                        progress > 0 -> Text("已播 $progress%")
                                    }
                                }
                            },
                            leadingContent = {
                                if (isCurrent) {
                                    Icon(
                                        Icons.Default.Equalizer,
                                        contentDescription = "正在播放",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            trailingContent = {
                                if (completed) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "已播完",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.clickable {
                                viewModel.selectTrack(index)
                                showTrackSheet = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Artwork(
    title: String,
    coverUrl: String,
    showLoading: Boolean,
    onEdit: () -> Unit,
    modifier: Modifier = Modifier
) {
    val hasCover = coverUrl.isNotBlank()
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(28.dp))
            .combinedClickable(
                onClick = { if (!hasCover) onEdit() },
                onLongClick = { if (hasCover) onEdit() }
            ),
        contentAlignment = Alignment.Center
    ) {
        CoverArtwork(
            title = title,
            coverUrl = coverUrl,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 28.dp,
            fallbackFontSize = 96.sp
        )
        if (!hasCover) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "编辑封面",
                tint = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(14.dp)
                    .background(Color.Black.copy(alpha = 0.36f), RoundedCornerShape(16.dp))
                    .padding(6.dp)
                    .size(18.dp)
            )
        }
        if (showLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = LOADING_OVERLAY_ALPHA)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverCropSheet(
    uri: Uri,
    isSaving: Boolean,
    onConfirm: (CoverCrop) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(initialValue = null, uri) {
        value = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input)
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
                SquareCropPreview(
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
private fun SquareCropPreview(
    bitmap: Bitmap,
    onCropChanged: (CoverCrop) -> Unit
) {
    val density = LocalDensity.current
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var zoom by remember(bitmap) { mutableStateOf(1f) }
    var offsetX by remember(bitmap) { mutableStateOf(0f) }
    var offsetY by remember(bitmap) { mutableStateOf(0f) }

    val baseScale = if (boxSize.width > 0 && boxSize.height > 0) {
        max(
            boxSize.width.toFloat() / bitmap.width.toFloat(),
            boxSize.height.toFloat() / bitmap.height.toFloat()
        )
    } else 1f
    val actualScale = baseScale * zoom
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
            onCropChanged(
                CoverCrop(
                    left = cropLeft,
                    top = cropTop,
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
            .pointerInput(bitmap, boxSize) {
                detectTransformGestures { _, pan, gestureZoom, _ ->
                    zoom = (zoom * gestureZoom).coerceIn(1f, 4f)
                    offsetX = (offsetX + pan.x).coerceIn(-maxOffsetX, maxOffsetX)
                    offsetY = (offsetY + pan.y).coerceIn(-maxOffsetY, maxOffsetY)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (boxSize.width > 0 && boxSize.height > 0) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier
                    .size(
                        width = with(density) { displayWidthPx.toDp() },
                        height = with(density) { displayHeightPx.toDp() }
                    )
                    .offset {
                        IntOffset(clampedOffsetX.roundToInt(), clampedOffsetY.roundToInt())
                    }
            )
        }
    }
}

@Composable
private fun PlaybackProgress(
    currentPosition: Long,
    duration: Long,
    draggedFraction: Float?,
    onFractionChange: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    enabled: Boolean
) {
    val playerFraction = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val displayedFraction = draggedFraction ?: playerFraction
    val displayedPosition = if (draggedFraction != null) {
        (displayedFraction * duration).toLong()
    } else currentPosition

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = displayedFraction,
            onValueChange = onFractionChange,
            onValueChangeFinished = onSeekFinished,
            enabled = enabled && duration > 0,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatDuration(displayedPosition),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PlaybackControls(
    state: PlayerUiState,
    onPrevious: () -> Unit,
    onSeekBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekForward: () -> Unit,
    onNext: () -> Unit
) {
    val controlsEnabled = !state.isInitialLoading && state.error == null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        modifier = Modifier.fillMaxWidth()
    ) {
        if (state.isPlaylist) {
            IconButton(onClick = onPrevious, enabled = state.currentTrackIndex > 0) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一集",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        IconButton(
            onClick = onSeekBack,
            enabled = controlsEnabled && state.currentPosition > 0
        ) {
            Icon(
                Icons.Default.FastRewind,
                contentDescription = "快退15秒",
                modifier = Modifier.size(32.dp)
            )
        }
        FilledIconButton(
            onClick = onPlayPause,
            enabled = controlsEnabled,
            modifier = Modifier.size(80.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = if (state.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.playWhenReady) "暂停" else "播放",
                modifier = Modifier.size(40.dp)
            )
        }
        IconButton(
            onClick = onSeekForward,
            enabled = controlsEnabled && state.duration > 0 && state.currentPosition < state.duration
        ) {
            Icon(
                Icons.Default.FastForward,
                contentDescription = "快进15秒",
                modifier = Modifier.size(32.dp)
            )
        }
        if (state.isPlaylist) {
            IconButton(onClick = onNext, enabled = state.currentTrackIndex < state.trackCount - 1) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一集",
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
private fun SleepTimerDialog(
    currentRemaining: Int?,
    onSet: (Int) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("睡眠定时器") },
        text = {
            Column {
                if (currentRemaining != null) {
                    Text("剩余 $currentRemaining 分钟后停止播放")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(onClick = onCancel) { Text("取消定时") }
                } else {
                    Text("设定多久后停止播放？")
                    Spacer(modifier = Modifier.height(16.dp))
                    listOf(15, 30, 45, 60).forEach { minutes ->
                        TextButton(
                            onClick = { onSet(minutes); onDismiss() },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("$minutes 分钟") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms.coerceAtLeast(0) / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%02d:%02d".format(minutes, seconds)
}
