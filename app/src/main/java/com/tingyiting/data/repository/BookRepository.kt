package com.tingyiting.data.repository

import com.tingyiting.data.local.dao.BookDao
import com.tingyiting.data.local.dao.TrackDao
import com.tingyiting.data.local.entity.BookEntity
import com.tingyiting.data.local.entity.TrackEntity
import com.tingyiting.data.model.Book
import com.tingyiting.data.model.Track
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
open class BookRepository @Inject constructor(
    private val bookDao: BookDao,
    private val trackDao: TrackDao
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
        tracks: List<Track>
    ): Long {
        bookDao.getBookByRootPath(rootPath)?.let { return it.id }

        val bookId = bookDao.insert(
            BookEntity(
                title = title,
                author = author,
                rootPath = rootPath,
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

    suspend fun deleteBook(id: Long) {
        bookDao.getBookById(id)?.let {
            trackDao.deleteByBookId(id)
            bookDao.delete(it)
        }
    }

    suspend fun updateProgress(id: Long, position: Long, duration: Long) {
        bookDao.updateProgress(id, position, duration, System.currentTimeMillis())
    }

    private fun BookEntity.toBook() = Book(
        id = id,
        title = title,
        author = author,
        coverUrl = coverUrl,
        webdavUrl = webdavUrl,
        rootPath = rootPath,
        currentTrackIndex = currentTrackIndex,
        duration = duration,
        position = position
    )

    private fun TrackEntity.toTrack() = Track(
        index = trackIndex,
        title = title,
        webdavUrl = webdavUrl,
        path = path,
        duration = duration,
        position = position
    )
}
