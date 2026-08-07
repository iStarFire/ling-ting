package com.lingting.ui.browser

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lingting.data.model.WebDavFile
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    onNavigateToBookshelf: () -> Unit,
    onNavigateToPlayer: (Long, String?) -> Unit,
    reimportBookId: Long? = null,
    reimportPath: String? = null,
    viewModel: BrowserViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 进入重新导入模式：定位到既有书籍路径
    LaunchedEffect(reimportBookId) {
        if (reimportBookId != null) {
            viewModel.startReimport(reimportBookId, reimportPath ?: "")
        }
    }

    // 批量导入成功后跳回书架
    LaunchedEffect(state.importDone) {
        if (state.importDone) {
            viewModel.clearImportResult()
            onNavigateToBookshelf()
        }
    }

    // 导入失败时提示
    LaunchedEffect(state.importError) {
        state.importError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearImportResult()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // 只显示当前文件夹名，完整路径噪音大
                    Text(
                        text = when {
                            state.isReimportMode -> "重新导入"
                            state.selectedPaths.isNotEmpty() -> "已选择 ${state.selectedPaths.size} 个目录"
                            else -> state.currentPath.trimEnd('/').substringAfterLast('/').ifEmpty { "网盘文件" }
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (state.selectedPaths.isNotEmpty()) {
                        IconButton(onClick = viewModel::clearSelection) {
                            Icon(Icons.Default.Close, contentDescription = "取消选择")
                        }
                    } else {
                        IconButton(onClick = {
                            if (!viewModel.goBack()) {
                                onNavigateToBookshelf()
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                        }
                    }
                },
                actions = {
                    if (state.isReimportMode) {
                        // 重新导入模式：以当前目录刷新既有书籍
                        Button(
                            onClick = { viewModel.reimportCurrentDirectory() },
                            enabled = !state.isImporting
                        ) {
                            Text("重新导入")
                        }
                    } else if (state.selectedPaths.isEmpty()) {
                        // 非选择模式：仅提供"回到根目录"
                        if (state.currentPath != "/") {
                            IconButton(onClick = { viewModel.navigateToRoot() }) {
                                Icon(Icons.Default.Home, contentDescription = "根目录")
                            }
                        }
                    } else {
                        // 选择模式：右侧出现"导入"按钮（选中目录数量）
                        Button(
                            onClick = { scope.launch { viewModel.importDirectories(state.selectedPaths.toList()) } },
                            enabled = !state.isImporting
                        ) {
                            Text("导入 (${state.selectedPaths.size})")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text("加载中...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                state.error != null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Default.ErrorOutline,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.error!!,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = { viewModel.loadFiles(state.currentPath) }) {
                                Text("重试")
                            }
                        }
                    }
                }
                state.files.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = Icons.Default.FolderOff,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "该目录下没有音频文件",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                else -> {
                    val selecting = state.selectedPaths.isNotEmpty()
                    LazyColumn(
                        contentPadding = PaddingValues(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        // 长按多选不易被发现，列表顶部给出一次性提示
                        if (!selecting && !state.isReimportMode && state.files.any { it.isDirectory }) {
                            item(key = "hint") {
                                Text(
                                    text = "提示：长按目录可多选批量导入",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                )
                            }
                        }
                        items(state.files, key = { it.path }) { file ->
                            FileItem(
                                file = file,
                                selected = file.isDirectory && state.selectedPaths.contains(file.path),
                                selectionActive = selecting,
                                onClick = {
                                    if (file.isDirectory) {
                                        if (selecting) {
                                            viewModel.toggleSelection(file.path)
                                        } else {
                                            viewModel.enterDirectory(file.path)
                                        }
                                    } else {
                                        // 非选择模式下，点击单个音频文件直接加入书架并播放
                                        if (!selecting) {
                                            scope.launch {
                                                val bookId = viewModel.addBookToShelf(file)
                                                onNavigateToPlayer(bookId, null)
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    if (!state.isReimportMode && file.isDirectory) viewModel.toggleSelection(file.path)
                                }
                            )
                        }
                    }
                }
            }

            // 导入中遮罩（确定进度条 + i/total 文本）
            if (state.isImporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(enabled = false, onClick = {}),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        tonalElevation = 4.dp,
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surface
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp).widthIn(max = 320.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { state.importProgressFraction ?: 0f },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = state.importProgress ?: "正在索引目录...",
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    file: WebDavFile,
    selected: Boolean,
    selectionActive: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val showCheckbox = selectionActive && file.isDirectory
    ListItem(
        headlineContent = {
            Text(
                text = file.name,
                fontWeight = if (file.isDirectory) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        supportingContent = {
            if (!file.isDirectory && file.size > 0) {
                Text(formatFileSize(file.size))
            }
        },
        leadingContent = {
            Icon(
                imageVector = if (file.isDirectory) Icons.Default.Folder else Icons.Default.AudioFile,
                contentDescription = null,
                tint = if (file.isDirectory)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        trailingContent = if (showCheckbox) {
            {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onClick() }
                )
            }
        } else null,
        modifier = Modifier.combinedClickable(
            enabled = true,
            onClick = onClick,
            onLongClick = onLongClick
        )
    )
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
        else -> "${"%.2f".format(bytes.toDouble() / (1024 * 1024 * 1024))} GB"
    }
}
