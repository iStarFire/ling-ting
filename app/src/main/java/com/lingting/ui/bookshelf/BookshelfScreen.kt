package com.lingting.ui.bookshelf

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lingting.data.model.SOURCE_WEBDAV
import com.lingting.playback.PlaybackInfo
import com.lingting.ui.components.CoverArtwork
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookshelfScreen(
    onNavigateToBrowser: () -> Unit,
    onNavigateToPlayer: (Long, String?) -> Unit,
    onNavigateToAccounts: () -> Unit,
    onNavigateToWebDav: () -> Unit,
    onNavigateToReimport: (bookId: Long, path: String) -> Unit = { _, _ -> },
    viewModel: BookshelfViewModel = hiltViewModel()
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val playbackInfo by viewModel.playbackInfo.collectAsStateWithLifecycle()
    val isConfigured by viewModel.isConfigured.collectAsStateWithLifecycle()

    var showImportSheet by remember { mutableStateOf(false) }

    // 长按操作菜单（修改名称 / 重新导入 / 删除）
    var actionBook by remember { mutableStateOf<BookItem?>(null) }
    var showActionSheet by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val localPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            viewModel.importLocalFolder(uri)
                .onSuccess { bookId -> onNavigateToPlayer(bookId, null) }
                .onFailure { e -> snackbarHostState.showSnackbar(e.message ?: "导入失败") }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showImportSheet = true },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加书籍")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!isConfigured) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onNavigateToAccounts)
                        .padding(16.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "尚未配置 WebDAV",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "点击前往账号管理配置网盘",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (books.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.Headphones,
                                contentDescription = null,
                                modifier = Modifier.size(80.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "书架空空如也",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "点击右下角 + 从网盘或本地添加听书",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(books, key = { it.book.id }) { item ->
                            // 仅当前播放的卡片拿到非空 playing，其余卡片稳定为 null 不随轮询重组
                            val playing = playbackInfo?.takeIf { it.bookId == item.book.id }
                            BookCard(
                                item = item,
                                playing = playing,
                                onClick = { onNavigateToPlayer(item.book.id, item.book.coverUrl) },
                                onLongClick = {
                                    actionBook = item
                                    showActionSheet = true
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Text(
                text = "选择导入来源",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showImportSheet = false
                        if (isConfigured) onNavigateToBrowser() else onNavigateToWebDav()
                    }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = if (isConfigured) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    }
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "从 WebDAV 导入",
                        color = if (isConfigured) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        }
                    )
                    if (!isConfigured) {
                        Text(
                            text = "未配置，点击前往设置",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        showImportSheet = false
                        localPicker.launch(null)
                    }
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(16.dp))
                Text(text = "本地导入（文件夹）")
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // 长按操作菜单：修改名称 / 重新导入 / 删除
    if (showActionSheet && actionBook != null) {
        val book = actionBook!!.book
        val canReimport = book.source == SOURCE_WEBDAV
        ModalBottomSheet(
            onDismissRequest = { showActionSheet = false },
            sheetState = rememberModalBottomSheetState()
        ) {
            Text(
                text = "《${book.title}》",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
            ListItem(
                headlineContent = { Text("修改名称") },
                leadingContent = { Icon(Icons.Default.Edit, contentDescription = null) },
                modifier = Modifier.clickable {
                    renameText = book.title
                    showActionSheet = false
                    showRenameDialog = true
                }
            )
            ListItem(
                headlineContent = { Text("重新导入") },
                supportingContent = {
                    if (canReimport) Text("刷新本书的曲目索引") else Text("本地导入不支持重新导入")
                },
                leadingContent = { Icon(Icons.Default.Refresh, contentDescription = null) },
                modifier = Modifier.clickable(enabled = canReimport) {
                    showActionSheet = false
                    onNavigateToReimport(book.id, book.rootPath)
                }
            )
            ListItem(
                headlineContent = { Text("删除", color = MaterialTheme.colorScheme.error) },
                leadingContent = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier.clickable {
                    showActionSheet = false
                    showDeleteConfirm = true
                }
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showRenameDialog && actionBook != null) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("修改名称") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    label = { Text("书籍名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameBook(actionBook!!.book.id, renameText)
                        showRenameDialog = false
                    }
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) { Text("取消") }
            }
        )
    }

    if (showDeleteConfirm && actionBook != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除书籍") },
            text = { Text("确定删除《${actionBook!!.book.title}》吗？删除后将无法恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBook(actionBook!!.book.id)
                        showDeleteConfirm = false
                    }
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    item: BookItem,
    playing: PlaybackInfo?,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val isActive = playing != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .then(if (isActive) Modifier.heightIn(min = 132.dp) else Modifier)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 6.dp else 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CoverArtwork(
                title = item.book.title,
                coverUrl = item.book.coverUrl,
                modifier = Modifier.size(if (isActive) 64.dp else 56.dp),
                fallbackFontSize = if (isActive) 28.sp else 24.sp
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.book.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                item.book.author.let {
                    if (it.isNotBlank()) {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                BookCardContent(item = item, playing = playing)
                Spacer(modifier = Modifier.height(6.dp))
                SourceBadge(source = item.book.source)
            }
        }
    }
}

/** 来源角标：网盘 / 本地。 */
@Composable
private fun SourceBadge(source: String) {
    val (label, color) = when (source) {
        SOURCE_WEBDAV -> "网盘" to MaterialTheme.colorScheme.primary
        else -> "本地" to MaterialTheme.colorScheme.secondary
    }
    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.12f),
        contentColor = color
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

/**
 * 卡片底部信息区：
 * - 当前集名称（正在播放时取播放器实时标题，否则取上次保存的当前集）
 * - 自动更新的进度条
 * - 左：当前时长 / 总时长；右：当前集 / 总集数（如 3/600）
 */
@Composable
private fun BookCardContent(item: BookItem, playing: PlaybackInfo?) {
    val trackTitle = playing?.trackTitle?.takeIf { it.isNotBlank() } ?: item.currentTrackTitle
    val position = playing?.currentPosition ?: item.savedPosition
    val duration = playing?.duration?.takeIf { it > 0 } ?: item.savedDuration
    val currentIndex = playing?.let { it.currentTrackIndex + 1 } ?: item.currentIndex
    val trackCount = playing?.trackCount?.takeIf { it > 0 } ?: item.trackCount
    val progress = if (duration > 0) {
        (position.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column {
        Text(
            text = trackTitle,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (playing != null) FontWeight.Medium else FontWeight.Normal,
            color = if (playing != null) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp),
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${formatTime(position)} / ${formatTime(duration)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "$currentIndex/$trackCount",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    val totalSec = (millis / 1000).coerceAtLeast(0)
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
