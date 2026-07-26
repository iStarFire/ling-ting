package com.tingyiting.ui.bookshelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyiting.data.model.Book
import com.tingyiting.data.model.Track
import com.tingyiting.data.repository.BookRepository
import com.tingyiting.data.repository.WebDavRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val webDavRepository: WebDavRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val books: StateFlow<List<Book>> = bookRepository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isConfigured: StateFlow<Boolean> = webDavRepository.configFlow
        .map { it != null }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            webDavRepository.isConfigured()
        )

    fun deleteBook(bookId: Long) {
        viewModelScope.launch {
            bookRepository.deleteBook(bookId)
        }
    }

    /**
     * 导入本地文件夹：通过系统文件夹选择器(OpenDocumentTree)拿到 treeUri，
     * 持久化读取权限后递归收集音频，作为同一本有声书（多集）入库。
     */
    suspend fun importLocalFolder(treeUri: Uri): Result<Long> = runCatching {
        // 持久化 URI 权限，保证应用重启后仍可访问（部分提供方可能不支持，忽略异常）
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }

        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("无法访问所选文件夹")
        val files = mutableListOf<DocumentFile>()
        collectAudioFiles(tree, 6, files)
        if (files.isEmpty()) throw IllegalStateException("该文件夹下没有音频文件")

        val tracks = files.sortedBy { it.name ?: "" }.map { f ->
            val uri = f.uri.toString()
            Track(
                index = 0,
                title = f.name ?: "未命名音频",
                webdavUrl = uri,
                path = uri
            )
        }
        val title = tree.name ?: treeUri.lastPathSegment ?: "本地音频"
        bookRepository.addBookWithTracks(
            title = title,
            author = "",
            rootPath = treeUri.toString(),
            tracks = tracks
        )
    }

    private fun collectAudioFiles(dir: DocumentFile, depth: Int, acc: MutableList<DocumentFile>) {
        if (depth <= 0 || acc.size >= MAX_LOCAL_TRACKS) return
        for (f in dir.listFiles()) {
            if (f.isDirectory) {
                collectAudioFiles(f, depth - 1, acc)
            } else if (f.isFile && isLocalAudio(f.name)) {
                acc.add(f)
            }
            if (acc.size >= MAX_LOCAL_TRACKS) break
        }
    }

    private fun isLocalAudio(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return LOCAL_AUDIO_EXTENSIONS.contains(ext)
    }

    companion object {
        private const val MAX_LOCAL_TRACKS = 2000
        private val LOCAL_AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "wma", "opus", "m4b"
        )
    }
}
