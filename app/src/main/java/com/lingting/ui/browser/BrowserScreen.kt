package com.lingting.ui.browser

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lingting.data.model.CoverCrop
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

    // 导入编辑弹窗：选封面后弹裁剪 sheet，所需 Uri 在此 state
    var pendingCropUri by remember { mutableStateOf<Uri?>(null) }
    // 本地选图启动器（与 CoverCropSheet 接驳）
    val localCoverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pendingCropUri = uri
    }

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
                    // 完整路径显示（不再是只最后一段）；过长时按字符数截断并保留前缀+省略号+末尾
                    Text(
                        text = when {
                            state.isReimportMode -> "重新导入"
                            state.selectedPaths.isNotEmpty() -> "已选择 ${state.selectedPaths.size} 个目录"
                            else -> formatPathForTitle(state.currentPath)
                        },
                        style = MaterialTheme.typography.titleSmall,
                        fontSize = 14.sp,
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
                        Button(
                            onClick = { viewModel.reimportCurrentDirectory() },
                            enabled = !state.isImporting
                        ) {
                            Text("重新导入")
                        }
                    } else if (state.selectedPaths.isEmpty()) {
                        // 非选择模式：根目录 home + 直接导入当前目录快捷按钮
                        if (state.currentPath != "/") {
                            IconButton(onClick = { viewModel.navigateToRoot() }) {
                                Icon(Icons.Default.Home, contentDescription = "回到根目录")
                            }
                            IconButton(
                                onClick = { viewModel.importCurrentDirectory() },
                                enabled = !state.isImporting
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "导入当前目录")
                            }
                        }
                    } else {
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

            // 导入完成后弹窗：修改专辑名 + 设置封面
            state.pendingImport?.let { pending ->
                ImportEditSheet(
                    pending = pending,
                    coverUri = state.pendingImportCoverUri,
                    isCoverUpdating = state.isCoverUpdating,
                    onAlbumTitleChange = viewModel::setPendingAlbumTitle,
                    onPickFromLocal = {
                        localCoverPicker.launch("image/*")
                    },
                    onComplete = viewModel::completeImport,
                    onDismiss = viewModel::dismissImportEdit
                )
            }

            // 选完本地图片后让用户裁剪（与 CoverCropSheet 的 CropSheet 接驳）
            pendingCropUri?.let { uri ->
                BrowserCoverCropSheet(
                    uri = uri,
                    isSaving = state.isCoverUpdating,
                    onConfirm = { crop ->
                        viewModel.applyPendingLocalCover(uri, crop)
                        pendingCropUri = null
                    },
                    onDismiss = { pendingCropUri = null }
                )
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

/**
 * 把完整路径格式化为 TopBar 标题：根目录显示 /，普通路径保留完整分段，
 * 过长时取首段 + 省略 + 末段，避免噪音掩盖可读信息。
 */
private fun formatPathForTitle(path: String): String {
    val trimmed = path.trimEnd('/').ifEmpty { "/" }
    if (trimmed == "/") return "/"
    val max = 36
    return if (trimmed.length <= max) trimmed
    else {
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        if (parts.size >= 2) {
            val head = parts.first()
            val tail = parts.last()
            "/$head/…/$tail"
        } else {
            "…${trimmed.takeLast(max - 1)}"
        }
    }
}

/**
 * 导入完成后弹出的编辑 sheet：
 *   1. 修改专辑名称（默认使用导入目录的 dirname，可改）
 *   2. 设置封面（从本地相册挑选 → 自动进入裁剪；搜刮为辅助入口）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportEditSheet(
    pending: PendingImport,
    coverUri: String?,
    isCoverUpdating: Boolean,
    onAlbumTitleChange: (String) -> Unit,
    onPickFromLocal: () -> Unit,
    onComplete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "导入成功 · 共 ${pending.bookIds.size} 本",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "① 专辑名称（默认 ${pending.defaultAlbumTitle}）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = pending.albumTitle,
                onValueChange = onAlbumTitleChange,
                label = { Text("专辑名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Text(
                text = "② 封面（选本地图片，自动进入裁剪）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (coverUri.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 缩略图从本地 Uri 异步解码
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    val bitmap by produceState<Bitmap?>(initialValue = null, coverUri) {
                        value = runCatching {
                            ctx.contentResolver.openInputStream(Uri.parse(coverUri))?.use { input ->
                                android.graphics.BitmapFactory.decodeStream(input)
                            }
                        }.getOrNull()
                    }
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap!!.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                TextButton(onClick = onPickFromLocal, enabled = !isCoverUpdating) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("选择本地图片")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDismiss, enabled = !isCoverUpdating) {
                    Text("跳过")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = onComplete, enabled = !isCoverUpdating) {
                    Text("完成")
                }
            }
        }
    }
}
