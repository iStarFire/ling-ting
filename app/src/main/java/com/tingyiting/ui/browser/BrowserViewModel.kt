package com.tingyiting.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyiting.data.model.Book
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
    val pathHistory: List<String> = listOf("/")
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
                        error = null,
                        pathHistory = _uiState.value.pathHistory + path
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
}
