package com.tingyiting.data.repository

import com.tingyiting.data.local.dao.BookDao
import com.tingyiting.data.local.dao.TrackDao
import com.tingyiting.data.local.entity.BookEntity
import com.tingyiting.data.local.entity.TrackEntity
import com.tingyiting.data.model.Book
import com.tingyiting.data.model.SOURCE_LOCAL
import com.tingyiting.data.model.SOURCE_WEBDAV
import com.tingyiting.data.model.Track
import com.tingyiting.data.repository.WebDavRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val trackDao: TrackDao,
    private val webDavRepository: WebDavRepository? = null
) {
    fun getAllBooks(): Flow<List<Book>> = bookDao.getAllBooks().map { entities ->
        entities.map { it.toBook() }
    }

    suspend fun getBookById(id: Long): Book? = bookDao.getBookById(id)?.toBook()

    suspend fun getBookByUrl(url: String): Book? = bookDao.getBookByUrl(url)?.toBook()

    suspend fun getBookByRootPath(rootPath: String): Book? =
        bookDao.getBookByRootPath(rootPath)?.toBook()

    /** 单文件书籍：按 URL 去重，已存在则直接返回其 id。 */
    suspend fun addBook(title: String, author: String, webdavUrl: String, coverUrl: String = ""): Long {
        val existing = bookDao.getBookByUrl(webdavUrl)
        if (existing != null) return existing.id
        return bookDao.insert(
            BookEntity(
                title = title,
                author = author,
                webdavUrl = webdavUrl,
                coverUrl = coverUrl,
                source = SOURCE_WEBDAV,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
    }

    /**
     * 目录导入：以 rootPath 去重。已存在则直接返回已有书 id；
     * 否则在事务中插入书与曲目（按 trackIndex 顺序）。
     */
    open suspend fun addBookWithTracks(
        title: String,
        author: String,
        rootPath: String,
        tracks: List<Track>,
        source: String = SOURCE_WEBDAV
    ): Long {
        bookDao.getBookByRootPath(rootPath)?.let { return it.id }

        val bookId = bookDao.insert(
            BookEntity(
                title = title,
                author = author,
                rootPath = rootPath,
                source = source,
                currentTrackIndex = 0,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
        val entities = tracks.mapIndexed { index, t ->
            TrackEntity(
                bookId = bookId,
                trackIndex = index,
                title = t.title,
                webdavUrl = t.webdavUrl,
                path = t.path
            )
        }
        trackDao.insertAll(entities)
        return bookId
    }

    suspend fun getTracks(bookId: Long): List<Track> =
        trackDao.getByBookId(bookId).map { it.toTrack() }

    open suspend fun getTrackCount(bookId: Long): Int = trackDao.countByBookId(bookId)

    open suspend fun getTrackByIndex(bookId: Long, index: Int): Track? =
        trackDao.getByIndex(bookId, index)?.toTrack()

    fun observeTracks(bookId: Long): Flow<List<Track>> =
        trackDao.observeByBookId(bookId).map { list -> list.map { it.toTrack() } }

    /** 保存某一集的播放进度。 */
    suspend fun saveTrackProgress(bookId: Long, index: Int, position: Long, duration: Long) {
        trackDao.updateProgress(bookId, index, position, duration)
    }

    /** 更新书籍当前所在的集序号。 */
    suspend fun updateCurrentTrack(bookId: Long, index: Int) {
        bookDao.getBookById(bookId)?.let { book ->
            bookDao.update(book.copy(currentTrackIndex = index, lastPlayedAt = System.currentTimeMillis()))
        }
    }

    open suspend fun deleteBook(id: Long) {
        bookDao.getBookById(id)?.let {
            trackDao.deleteByBookId(id)
            bookDao.delete(it)
        }
    }

    /** 修改书籍名称并保存到数据库。 */
    open suspend fun renameBook(bookId: Long, title: String) {
        bookDao.getBookById(bookId) ?: return
        bookDao.updateTitle(bookId, title)
    }

    /**
     * 重新导入（仅 WebDAV 目录书籍）：按新路径刷新曲目索引，复用同一本书。
     * 先删除旧曲目再写入新曲目，并同步根路径/名称/来源。
     */
    open suspend fun reimportWebDav(bookId: Long, path: String): Result<Long> = runCatching {
        val repo = webDavRepository ?: throw IllegalStateException("WebDAV 未配置")
        val files = repo.collectAudioFiles(path) { _, _, _ -> }.getOrElse { throw it }
        if (files.isEmpty()) throw IllegalStateException("该目录下没有音频文件")
        val dirName = path.trimEnd('/').substringAfterLast('/').ifBlank { "根目录" }
        val entities = files.mapIndexed { index, f ->
            TrackEntity(
                bookId = bookId,
                trackIndex = index,
                title = f.name.substringBeforeLast(".").ifBlank { f.name },
                webdavUrl = repo.buildFileUrl(f.path),
                path = f.path
            )
        }
        val existing = bookDao.getBookById(bookId) ?: throw IllegalStateException("书籍不存在")
        trackDao.deleteByBookId(bookId)
        trackDao.insertAll(entities)
        bookDao.update(
            existing.copy(
                rootPath = path,
                title = dirName,
                source = SOURCE_WEBDAV,
                currentTrackIndex = 0,
                position = 0,
                duration = 0,
                lastPlayedAt = System.currentTimeMillis()
            )
        )
        bookId
    }

    suspend fun updateProgress(id: Long, position: Long, duration: Long) {
        bookDao.updateProgress(id, position, duration, System.currentTimeMillis())
    }

    open suspend fun updateCover(bookId: Long, coverUrl: String) {
        bookDao.updateCover(bookId, coverUrl)
    }

    /**
     * 更新本专辑的「跳过头尾」设置。
     * [introSeconds] / [outroSeconds] 由调用方保证已 clamp 到 [0, 180]。
     * 任何非零值都会并入各自的历史集合（去重，保留最近若干个），便于不同集选不同片头片尾。
     */
    open suspend fun updateSkipSettings(
        bookId: Long,
        introEnabled: Boolean,
        introSeconds: Int,
        outroEnabled: Boolean,
        outroSeconds: Int
    ) {
        val existing = bookDao.getBookById(bookId) ?: return
        val newIntroHistory = appendHistory(existing.introSkipHistory, introSeconds)
        val newOutroHistory = appendHistory(existing.outroSkipHistory, outroSeconds)
        bookDao.updateSkipSettings(
            id = bookId,
            introEnabled = introEnabled,
            introSeconds = introSeconds,
            introHistory = newIntroHistory,
            outroEnabled = outroEnabled,
            outroSeconds = outroSeconds,
            outroHistory = newOutroHistory
        )
    }

    private fun appendHistory(current: String, seconds: Int): String {
        if (seconds <= 0) return current
        val existing = parseHistory(current).toMutableList()
        existing.remove(seconds)
        existing.add(0, seconds)
        // 截断保留最近的若干项，避免无限增长
        val capped = if (existing.size > MAX_HISTORY) existing.take(MAX_HISTORY) else existing
        return capped.joinToString(",")
    }

    private fun parseHistory(raw: String): List<Int> =
        raw.split(',').mapNotNull { it.trim().toIntOrNull() }

    private fun BookEntity.toBook() = Book(
        id = id,
        title = title,
        author = author,
        coverUrl = coverUrl,
        webdavUrl = webdavUrl,
        rootPath = rootPath,
        source = source,
        currentTrackIndex = currentTrackIndex,
        duration = duration,
        position = position,
        introSkipEnabled = introSkipEnabled,
        introSkipSeconds = introSkipSeconds,
        introSkipHistory = parseHistory(introSkipHistory),
        outroSkipEnabled = outroSkipEnabled,
        outroSkipSeconds = outroSkipSeconds,
        outroSkipHistory = parseHistory(outroSkipHistory)
    )

    private fun TrackEntity.toTrack() = Track(
        index = trackIndex,
        title = title,
        webdavUrl = webdavUrl,
        path = path,
        duration = duration,
        position = position
    )

    private companion object {
        /** 每段历史最多保留的条目数（去重后）；超过则丢弃最早项。 */
        const val MAX_HISTORY = 8
    }
}
