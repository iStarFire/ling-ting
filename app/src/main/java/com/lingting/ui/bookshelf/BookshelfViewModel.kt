package com.lingting.ui.bookshelf

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingting.data.model.Book
import com.lingting.data.model.SOURCE_LOCAL
import com.lingting.data.model.Track
import com.lingting.data.repository.BookRepository
import com.lingting.data.repository.WebDavRepository
import com.lingting.playback.AudioPlayer
import com.lingting.playback.PlaybackInfo
import com.lingting.playback.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 书架列表项：在 Book 基础上补充集数与当前集信息。
 * 单文件书（无 tracks）按 1 集处理。
 */
data class BookItem(
    val book: Book,
    val trackCount: Int,
    /** 1-based 当前集序号 */
    val currentIndex: Int,
    val currentTrackTitle: String,
    val savedPosition: Long,
    val savedDuration: Long
)

@HiltViewModel
class BookshelfViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val webDavRepository: WebDavRepository,
    playbackState: PlaybackState,
    private val player: AudioPlayer,
    @ApplicationContext private val context: Context
) : ViewModel() {

    val books: StateFlow<List<BookItem>> = bookRepository.getAllBooks()
        .map { list -> list.map { toBookItem(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** 当前正在播放的实时信息（后台播放期间由 PlaybackState 每 500ms 刷新）。 */
    val playbackInfo: StateFlow<PlaybackInfo?> = playbackState.info

    private suspend fun toBookItem(book: Book): BookItem {
        val trackCount = bookRepository.getTrackCount(book.id)
        if (trackCount <= 0) {
            // 单文件书：无 tracks，按 1 集处理
            return BookItem(
                book = book,
                trackCount = 1,
                currentIndex = 1,
                currentTrackTitle = book.title,
                savedPosition = book.position,
                savedDuration = book.duration
            )
        }
        val index = book.currentTrackIndex.coerceIn(0, trackCount - 1)
        val currentTrack = bookRepository.getTrackByIndex(book.id, index)
        return BookItem(
            book = book,
            trackCount = trackCount,
            currentIndex = index + 1,
            currentTrackTitle = currentTrack?.title ?: book.title,
            savedPosition = currentTrack?.position ?: book.position,
            savedDuration = currentTrack?.duration ?: book.duration
        )
    }

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

    /** 修改书籍名称并保存到数据库。 */
    fun renameBook(bookId: Long, title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            bookRepository.renameBook(bookId, trimmed)
        }
    }

    /** 迷你播放条的播放/暂停切换。 */
    fun togglePlayPause() {
        if (player.playWhenReady) player.pause() else player.play()
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
            tracks = tracks,
            source = SOURCE_LOCAL
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
