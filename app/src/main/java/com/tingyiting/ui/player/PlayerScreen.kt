package com.tingyiting.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Forward
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private const val LOADING_OVERLAY_ALPHA = 0.45f
private const val SEEK_INTERVAL_MS = 15_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    bookId: Long,
    onNavigateBack: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSleepTimerDialog by remember { mutableStateOf(false) }
    var showTrackSheet by remember { mutableStateOf(false) }
    var draggedFraction by remember { mutableStateOf<Float?>(null) }
    val trackSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val trackListState = rememberLazyListState()

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
                        showLoading = state.isInitialLoading || state.isBuffering,
                        modifier = Modifier.size(250.dp)
                    )
                }

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
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "第 ${state.currentTrackIndex + 1} / ${state.trackCount} 集",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = state.trackTitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
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
                    onSeekBack = { viewModel.seekBy(-SEEK_INTERVAL_MS) },
                    onSeekForward = { viewModel.seekBy(SEEK_INTERVAL_MS) },
                    enabled = !state.isInitialLoading && state.error == null
                )

                PlaybackControls(
                    state = state,
                    onSleepTimer = { showSleepTimerDialog = true },
                    onPrevious = viewModel::prevTrack,
                    onPlayPause = viewModel::togglePlayPause,
                    onNext = viewModel::nextTrack,
                    onShowTracks = { if (state.isPlaylist) showTrackSheet = true }
                )
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

@Composable
private fun Artwork(showLoading: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Headphones,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.onPrimaryContainer
        )
        if (showLoading) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(Color.Black.copy(alpha = LOADING_OVERLAY_ALPHA)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.White)
            }
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
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeekButton(
                forward = false,
                onClick = onSeekBack,
                enabled = enabled && displayedPosition > 0
            )
            Slider(
                value = displayedFraction,
                onValueChange = onFractionChange,
                onValueChangeFinished = onSeekFinished,
                enabled = enabled && duration > 0,
                modifier = Modifier.weight(1f)
            )
            SeekButton(
                forward = true,
                onClick = onSeekForward,
                enabled = enabled && duration > 0 && displayedPosition < duration
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 52.dp),
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
private fun SeekButton(forward: Boolean, onClick: () -> Unit, enabled: Boolean) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(52.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (forward) Icons.Default.Forward else Icons.Default.Replay,
                contentDescription = if (forward) "快进15秒" else "快退15秒",
                modifier = Modifier.size(32.dp)
            )
            Text(text = "15", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun PlaybackControls(
    state: PlayerUiState,
    onSleepTimer: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShowTracks: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        IconButton(onClick = onSleepTimer) {
            Icon(
                imageVector = if (state.sleepTimerRemaining != null) Icons.Default.Bedtime else Icons.Default.Timer,
                contentDescription = "睡眠定时器",
                tint = if (state.sleepTimerRemaining != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface
            )
        }
        if (state.isPlaylist) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onPrevious, enabled = state.currentTrackIndex > 0) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "上一集")
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        FilledIconButton(
            onClick = onPlayPause,
            enabled = !state.isInitialLoading && state.error == null,
            modifier = Modifier.size(72.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = if (state.playWhenReady) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (state.playWhenReady) "暂停" else "播放",
                modifier = Modifier.size(36.dp)
            )
        }
        if (state.isPlaylist) {
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = onNext, enabled = state.currentTrackIndex < state.trackCount - 1) {
                Icon(Icons.Default.SkipNext, contentDescription = "下一集")
            }
            Spacer(modifier = Modifier.width(8.dp))
        }
        IconButton(onClick = onShowTracks, enabled = state.isPlaylist) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "选集",
                tint = if (state.isPlaylist) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
            )
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
