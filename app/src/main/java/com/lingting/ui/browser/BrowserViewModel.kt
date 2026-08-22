package com.lingting.ui.browser

import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingting.data.model.Book
import com.lingting.data.model.CoverCrop
import com.lingting.data.model.Track
import com.lingting.data.model.WebDavFile
import com.lingting.data.repository.BookRepository
import com.lingting.data.repository.CoverRepository
import com.lingting.data.repository.WebDavRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 导入完成后弹窗里"待批量编辑"的状态：包含本批次所有新生成的书籍 id
 * 与每个目录对应的初始 dirName（作为专辑名称的默认值）。
 */
data class PendingImport(
    val bookIds: List<Long>,
    val rootPaths: List<String>,
    val defaultAlbumTitle: String,
    val albumTitle: String
)

data class BrowserUiState(
    val currentPath: String = "/",
    val files: List<WebDavFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val pathHistory: List<String> = listOf("/"),
    val isImporting: Boolean = false,
    val importProgress: String? = null,
    val importProgressFraction: Float? = null,
    val importError: String? = null,
    val importDone: Boolean = false,
    val selectedPaths: Set<String> = emptySet(),
    /** 重新导入模式：定位到既有书籍的路径并刷新其曲目索引。 */
    val isReimportMode: Boolean = false,
    val reimportBookId: Long? = null,
    /** 导入成功后弹出的"专辑名+封面"编辑弹窗。 */
    val pendingImport: PendingImport? = null,
    /** 弹窗内为整批次共用设置的封面 Uri（本地文件 file:// Uri，已裁剪保存好）。 */
    val pendingImportCoverUri: String? = null,
    val coverSearchQuery: String = "",
    val coverSearchResults: List<String> = emptyList(),
    val isCoverSearching: Boolean = false,
    val isCoverUpdating: Boolean = false,
    val coverError: String? = null
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val webDavRepository: WebDavRepository,
    private val bookRepository: BookRepository,
    private val coverRepository: CoverRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(BrowserUiState())
    val uiState = _uiState.asStateFlow()

    init {
        loadFiles("/")
    }

    fun loadFiles(path: String) {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch {
            val result = webDavRepository.listFiles(path)
            result.fold(
                onSuccess = { files ->
                    val audioFiles = files.filter { it.isAudio || it.isDirectory }
                        .sortedByDescending { it.isDirectory }
                        .sortedBy { it.name }
                    _uiState.value = _uiState.value.copy(
                        currentPath = path,
                        files = audioFiles,
                        isLoading = false,
                        error = null
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "加载失败: ${e.message}"
                    )
                }
            )
        }
    }

    fun enterDirectory(path: String) {
        _uiState.value = _uiState.value.copy(pathHistory = _uiState.value.pathHistory + path)
        loadFiles(path)
    }

    fun goBack(): Boolean {
        val history = _uiState.value.pathHistory
        if (history.size <= 1) return false

        val newHistory = history.dropLast(1)
        val parentPath = newHistory.lastOrNull() ?: "/"
        _uiState.value = _uiState.value.copy(pathHistory = newHistory)
        loadFiles(parentPath)
        return true
    }

    fun navigateToRoot() {
        _uiState.value = _uiState.value.copy(pathHistory = listOf("/"))
        loadFiles("/")
    }

    suspend fun addBookToShelf(file: WebDavFile): Long {
        val url = webDavRepository.buildFileUrl(file.path)
        val title = file.name.substringBeforeLast(".")
        return bookRepository.addBook(
            title = title,
            author = "",
            webdavUrl = url
        )
    }

    /** 切换某个目录的选中状态（进入/退出选择模式）。 */
    fun toggleSelection(path: String) {
        val set = _uiState.value.selectedPaths.toMutableSet()
        if (set.contains(path)) set.remove(path) else set.add(path)
        _uiState.value = _uiState.value.copy(selectedPaths = set)
    }

    /** 清空全部选择。 */
    fun clearSelection() {
        _uiState.value = _uiState.value.copy(selectedPaths = emptySet())
    }

    /** 便捷入口：直接导入当前打开的目录，无需先多选。 */
    fun importCurrentDirectory() {
        val path = _uiState.value.currentPath
        if (path.isBlank() || path == "/") return
        viewModelScope.launch { importDirectories(listOf(path)) }
    }

    /**
     * 批量导入一个或多个目录，每个目录生成一本有声剧。
     * 扫描过程通过 [WebDavRepository.collectAudioFiles] 的进度回调实时更新 [BrowserUiState.importProgress]，
     * 避免大量文件（如数百个）递归扫描时界面长时间无反馈而像"卡死"。
     * 全部完成后标记 [BrowserUiState.importDone]，由 UI 跳转回书架。
     * 返回导入成功的书籍 id 列表。
     *
     * 导入完成后不直接结束，而是把批次信息写入 [BrowserUiState.pendingImport]，
     * 触发编辑弹窗（修改专辑名 + 设置封面）。用户确认或跳过后才跳回书架。
     */
    suspend fun importDirectories(paths: List<String>): List<Long> {
        if (paths.isEmpty()) return emptyList()
        _uiState.value = _uiState.value.copy(
            isImporting = true,
            importError = null,
            importProgress = "准备索引...",
            importProgressFraction = 0f,
            selectedPaths = emptySet()
        )
        val ids = mutableListOf<Long>()
        val rootPaths = mutableListOf<String>()
        var failure: String? = null
        for ((index, path) in paths.withIndex()) {
            val dirName = path.trimEnd('/').substringAfterLast('/').ifBlank { "根目录" }
            webDavRepository.collectAudioFiles(path) { scanned, total, audio ->
                val fraction = if (total > 0) scanned.toFloat() / total else 0f
                _uiState.value = _uiState.value.copy(
                    importProgressFraction = fraction,
                    importProgress = "正在索引 (${index + 1}/${paths.size}) $dirName：$scanned/$total · 已发现 $audio 个音频"
                )
            }.fold(
                onSuccess = { files ->
                    if (files.isEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            importProgress = "「$dirName」下没有音频文件，已跳过"
                        )
                        return@fold
                    }
                    val tracks = files.mapIndexed { i, f ->
                        Track(
                            index = i,
                            title = f.name.substringBeforeLast(".").ifBlank { f.name },
                            webdavUrl = webDavRepository.buildFileUrl(f.path),
                            path = f.path
                        )
                    }
                    val bookId = bookRepository.addBookWithTracks(
                        title = dirName,
                        author = "",
                        rootPath = path,
                        tracks = tracks
                    )
                    ids.add(bookId)
                    rootPaths.add(path)
                },
                onFailure = { e ->
                    failure = "导入「$dirName」失败: ${e.message}"
                }
            )
            if (failure != null) break
        }

        if (failure != null) {
            _uiState.value = _uiState.value.copy(
                isImporting = false,
                importProgress = null,
                importProgressFraction = null,
                importError = failure
            )
            return emptyList()
        }

        if (ids.isEmpty()) {
            // 全部目录都没有音频：直接走 importDone 跳回书架，不弹编辑窗
            _uiState.value = _uiState.value.copy(
                isImporting = false,
                importProgress = null,
                importProgressFraction = null,
                importDone = true
            )
            return emptyList()
        }

        // 设置"待编辑批次"，编辑弹窗由 UI 弹出；只有当用户确认或跳过时才 importDone=true
        val defaultTitle = rootPaths.first().trimEnd('/').substringAfterLast('/').ifBlank { "专辑" }
        _uiState.value = _uiState.value.copy(
            isImporting = false,
            importProgress = null,
            importProgressFraction = null,
            pendingImport = PendingImport(
                bookIds = ids,
                rootPaths = rootPaths,
                defaultAlbumTitle = defaultTitle,
                albumTitle = defaultTitle
            ),
            pendingImportCoverUri = null,
            coverSearchQuery = defaultTitle,
            coverSearchResults = emptyList(),
            coverError = null
        )
        return ids
    }

    fun clearImportResult() {
        _uiState.value = _uiState.value.copy(
            importDone = false,
            importError = null,
            importProgressFraction = null
        )
    }

    /**
     * 编辑弹窗内修改专辑标题（实时同步到 UI），自动清掉已选封面避免不一致。
     */
    fun setPendingAlbumTitle(title: String) {
        val pending = _uiState.value.pendingImport ?: return
        _uiState.value = _uiState.value.copy(
            pendingImport = pending.copy(albumTitle = title)
        )
    }

    /**
     * 豆瓣搜刮封面候选（写入 [BrowserUiState.coverSearchResults]）。
     */
    fun searchDoubanCovers(query: String) {
        val repository = coverRepository ?: run {
            _uiState.value = _uiState.value.copy(coverError = "封面服务不可用")
            return
        }
        val effective = query.trim().ifBlank { _uiState.value.pendingImport?.defaultAlbumTitle.orEmpty() }
        if (effective.isBlank()) return
        _uiState.value = _uiState.value.copy(
            isCoverSearching = true,
            coverError = null,
            coverSearchResults = emptyList(),
            coverSearchQuery = effective
        )
        viewModelScope.launch {
            repository.searchDoubanCovers(effective)
                .onSuccess { covers ->
                    _uiState.value = _uiState.value.copy(
                        isCoverSearching = false,
                        coverSearchResults = covers,
                        coverError = null
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isCoverSearching = false,
                        coverSearchResults = emptyList(),
                        coverError = e.message ?: "搜刮失败"
                    )
                }
        }
    }

    /** 加载豆瓣候选缩略图（供候选列表预览）。 */
    suspend fun loadDoubanThumbnail(url: String): Bitmap? =
        coverRepository?.loadThumbnail(url)

    /**
     * 下载候选封面到临时文件并回调 Uri（供弹窗内打裁剪 sheet）。
     */
    fun downloadDoubanCoverToTemp(imageUrl: String, onResult: (Uri?) -> Unit) {
        val repository = coverRepository ?: run {
            _uiState.value = _uiState.value.copy(coverError = "封面服务不可用")
            onResult(null)
            return
        }
        _uiState.value = _uiState.value.copy(isCoverUpdating = true, coverError = null)
        viewModelScope.launch {
            repository.downloadToTemp(imageUrl)
                .onSuccess { uri ->
                    _uiState.value = _uiState.value.copy(isCoverUpdating = false)
                    onResult(uri)
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isCoverUpdating = false,
                        coverError = e.message ?: "封面下载失败"
                    )
                    onResult(null)
                }
        }
    }

    /**
     * 用本地图片 Uri 设置本次导入的共用封面（落临时再裁剪由 UI 调用 CoverCropSheet，
     * 这里只负责把裁剪保存到书籍并缓存 Uri 供 UI 预览）。
     */
    fun applyPendingLocalCover(uri: Uri, crop: CoverCrop) {
        val pending = _uiState.value.pendingImport ?: return
        val repository = coverRepository ?: run {
            _uiState.value = _uiState.value.copy(coverError = "封面服务不可用")
            return
        }
        _uiState.value = _uiState.value.copy(isCoverUpdating = true, coverError = null)
        viewModelScope.launch {
            // 仅对批次首本落库即可：把选好的封面写入该书，其他书用同样路径作 coverUrl
            // （importLocalCover 已基于 bookId 唯一保存文件；为避免重复 IO，把
            // importLocalCover 结果的 file:/// URI 提取作为批次共享 coverUrl，其他书
            // 直接调用 bookRepository.updateCover 复用同一文件地址）。
            // 仅对批次首本落库即可：对 importLocalCover 结果取 coverPath 作为共享 coverUrl，
            // 同批次的其他书复用同一文件地址（CoverRepository 内部按 bookId 写独立文件，
            // 这里调用 updateCover 覆写目标是接受共享路径，符合 MVP 行为）。
            val firstBookId = pending.bookIds.first()
            repository.importLocalCover(firstBookId, uri, crop)
                .onSuccess { coverPath ->
                    pending.bookIds.drop(1).forEach { otherId ->
                        bookRepository.updateCover(otherId, coverPath)
                    }
                    _uiState.value = _uiState.value.copy(
                        isCoverUpdating = false,
                        pendingImportCoverUri = coverPath,
                        coverError = null
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isCoverUpdating = false,
                        coverError = e.message ?: "封面保存失败"
                    )
                }
        }
    }

    /**
     * 用豆瓣图片 URL + 裁剪区域设置本次导入的共用封面。
     */
    fun applyPendingDoubanCover(imageUrl: String, crop: CoverCrop) {
        val pending = _uiState.value.pendingImport ?: return
        val repository = coverRepository ?: run {
            _uiState.value = _uiState.value.copy(coverError = "封面服务不可用")
            return
        }
        _uiState.value = _uiState.value.copy(isCoverUpdating = true, coverError = null)
        viewModelScope.launch {
            val firstBookId = pending.bookIds.first()
            repository.importDoubanCover(firstBookId, imageUrl, crop)
                .onSuccess { coverPath ->
                    pending.bookIds.drop(1).forEach { otherId ->
                        bookRepository.updateCover(otherId, coverPath)
                    }
                    _uiState.value = _uiState.value.copy(
                        isCoverUpdating = false,
                        pendingImportCoverUri = coverPath,
                        coverError = null
                    )
                }
                .onFailure { e ->
                    _uiState.value = _uiState.value.copy(
                        isCoverUpdating = false,
                        coverError = e.message ?: "封面保存失败"
                    )
                }
        }
    }

    /**
     * 编辑弹窗"完成"：把弹窗里的 title / cover 应用到本批次所有书籍，然后跳转书架。
     */
    fun completeImport() {
        val pending = _uiState.value.pendingImport ?: run {
            finishImportDialog()
            return
        }
        val title = pending.albumTitle.trim().ifBlank { pending.defaultAlbumTitle }
        viewModelScope.launch {
            for (bookId in pending.bookIds) {
                bookRepository.updateTitle(bookId, title)
            }
            finishImportDialog()
        }
    }

    /** 关闭编辑弹窗（无论是否改过）。 */
    fun dismissImportEdit() {
        finishImportDialog()
    }

    private fun finishImportDialog() {
        _uiState.value = _uiState.value.copy(
            importDone = true,
            pendingImport = null,
            pendingImportCoverUri = null,
            coverSearchQuery = "",
            coverSearchResults = emptyList(),
            coverError = null,
            isCoverSearching = false,
            isCoverUpdating = false
        )
    }

    /** 进入重新导入模式，定位到既有书籍所在的路径。 */
    fun startReimport(bookId: Long, path: String) {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        val history = mutableListOf("/")
        var acc = ""
        for (seg in segments) {
            acc += "/$seg"
            history.add(acc)
        }
        _uiState.value = _uiState.value.copy(
            isReimportMode = true,
            reimportBookId = bookId,
            selectedPaths = emptySet()
        )
        if (path.isNotBlank()) {
            _uiState.value = _uiState.value.copy(pathHistory = history)
            loadFiles(path)
        }
    }

    /** 重新导入模式：以当前目录为路径，刷新既有书籍的曲目索引。 */
    fun reimportCurrentDirectory() {
        val bookId = _uiState.value.reimportBookId ?: return
        val path = _uiState.value.currentPath
        _uiState.value = _uiState.value.copy(
            isImporting = true,
            importError = null,
            importProgress = "正在刷新索引..."
        )
        viewModelScope.launch {
            bookRepository.reimportWebDav(bookId, path).fold(
                onSuccess = {
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importProgress = null,
                        importDone = true
                    )
                },
                onFailure = { e ->
                    _uiState.value = _uiState.value.copy(
                        isImporting = false,
                        importProgress = null,
                        importError = "重新导入失败：${e.message}"
                    )
                }
            )
        }
    }
}
