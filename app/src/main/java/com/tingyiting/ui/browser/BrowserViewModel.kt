package com.tingyiting.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyiting.data.model.Book
import com.tingyiting.data.model.Track
import com.tingyiting.data.model.WebDavFile
import com.tingyiting.data.repository.BookRepository
import com.tingyiting.data.repository.WebDavRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

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
    val reimportBookId: Long? = null
)

@HiltViewModel
class BrowserViewModel @Inject constructor(
    private val webDavRepository: WebDavRepository,
    private val bookRepository: BookRepository
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

    /**
     * 批量导入一个或多个目录，每个目录生成一本有声剧。
     * 扫描过程通过 [WebDavRepository.collectAudioFiles] 的进度回调实时更新 [BrowserUiState.importProgress]，
     * 避免大量文件（如数百个）递归扫描时界面长时间无反馈而像"卡死"。
     * 全部完成后标记 [BrowserUiState.importDone]，由 UI 跳转回书架。
     * 返回导入成功的书籍 id 列表。
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

        _uiState.value = _uiState.value.copy(
            isImporting = false,
            importProgress = null,
            importProgressFraction = null,
            importDone = true
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
