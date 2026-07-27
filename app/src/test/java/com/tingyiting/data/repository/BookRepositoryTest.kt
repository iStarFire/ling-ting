package com.tingyiting.data.repository

import com.tingyiting.data.local.dao.BookDao
import com.tingyiting.data.local.dao.TrackDao
import com.tingyiting.data.local.entity.BookEntity
import com.tingyiting.data.local.entity.TrackEntity
import com.tingyiting.data.model.SOURCE_WEBDAV
import com.tingyiting.data.model.WebDavFile
import com.tingyiting.data.store.WebDavConfig
import com.tingyiting.data.store.WebDavConfigStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookRepositoryTest {

    @Test
    fun reimportWebDav_refreshesTracksUnderGivenPath() = runTest {
        val bookId = 7L
        val bookDao = FakeBookDao(bookId)
        val trackDao = FakeTrackDao()
        val webDavRepo = FakeWebDavRepository(
            "/dav/drama" to listOf(WebDavFile(name = "a.mp3", path = "/dav/drama/a.mp3", isDirectory = false))
        )
        val repo = BookRepository(bookDao, trackDao, webDavRepo)

        val result = repo.reimportWebDav(bookId, "/dav/drama")

        assertTrue(result.isSuccess)
        assertEquals(bookId, result.getOrNull())
        // 删除旧曲目后写入新曲目（webdavUrl 经 buildFileUrl 处理）
        assertEquals(1, trackDao.inserted.size)
        assertEquals("http://fake/dav/drama/a.mp3", trackDao.inserted[0].webdavUrl)
        assertEquals("/dav/drama/a.mp3", trackDao.inserted[0].path)
        // 根路径与来源被更新
        assertEquals("/dav/drama", bookDao.updated?.rootPath)
        assertEquals(SOURCE_WEBDAV, bookDao.updated?.source)
        // collectAudioFiles 收到了正确路径
        assertEquals("/dav/drama", webDavRepo.lastPath)
    }

    @Test
    fun reimportWebDav_emptyDirReturnsFailure() = runTest {
        val bookId = 7L
        val bookDao = FakeBookDao(bookId)
        val trackDao = FakeTrackDao()
        val webDavRepo = FakeWebDavRepository() // 任何路径都返回空
        val repo = BookRepository(bookDao, trackDao, webDavRepo)

        val result = repo.reimportWebDav(bookId, "/dav/empty")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("没有音频") == true)
    }

    @Test
    fun renameBook_updatesTitle() = runTest {
        val bookId = 7L
        val bookDao = FakeBookDao(bookId)
        val repo = BookRepository(bookDao, FakeTrackDao())

        repo.renameBook(bookId, "新名字")
        assertEquals("新名字", bookDao.updatedTitle)
    }

    @Test
    fun updateSkipSettings_persistsAndDedupsHistory() = runTest {
        val bookId = 7L
        val bookDao = FakeBookDao(bookId)
        val repo = BookRepository(bookDao, FakeTrackDao())

        // 首次保存：仅 out History 列中出现该值
        repo.updateSkipSettings(bookId, introEnabled = true, introSeconds = 30, outroEnabled = false, outroSeconds = 0)
        assertEquals(true, bookDao.updatedSkip?.introEnabled)
        assertEquals(30, bookDao.updatedSkip?.introSeconds)
        assertEquals("30", bookDao.updatedSkip?.introHistory)
        assertEquals("", bookDao.updatedSkip?.outroHistory) // outroSeconds=0 不入历史

        // 多次设置后应去重，且最新插入排到首位
        repo.updateSkipSettings(bookId, true, 60, false, 0)
        repo.updateSkipSettings(bookId, true, 30, false, 0) // 重复 30 应去重
        assertEquals(true, bookDao.updatedSkip?.introEnabled)
        assertEquals("30,60", bookDao.updatedSkip?.introHistory)
    }

    @Test
    fun getLastPlayedBook_emitsMostRecentPlayedBook() = runTest {
        val bookDao = FakeBookDao(7L)
        val repo = BookRepository(bookDao, FakeTrackDao())

        // 仅设置 lastPlayedAt 应能正确转换成 Book
        bookDao.updateProgress(7L, position = 5_000, duration = 60_000, timestamp = System.currentTimeMillis())
        val first = repo.getLastPlayedBook().firstOrNull()
        assertEquals("旧名", first?.title) // 来自 FakeBookDao 的初始 storedEntity
        assertEquals(5_000L, first?.position)
    }

    @Test
    fun getLastPlayedBook_returnsNullWhenNeverPlayed() = runTest {
        val bookDao = FakeBookDao(7L)
        val repo = BookRepository(bookDao, FakeTrackDao())
        // 默认 storedEntity.lastPlayedAt = 0，不应返回这本书
        val result = repo.getLastPlayedBook().firstOrNull()
        assertEquals(null, result)
    }

    // ---- 测试替身 ----

    private object NoOpConfigStore : WebDavConfigStore {
        override fun save(config: WebDavConfig) {}
        override fun load(): WebDavConfig? = null
        override fun clear() {}
    }

    private class FakeWebDavRepository(vararg entries: Pair<String, List<WebDavFile>>) :
        WebDavRepository(NoOpConfigStore) {
        private val map = entries.toMap()
        var lastPath: String? = null

        override suspend fun collectAudioFiles(
            rootPath: String,
            onProgress: (scannedDirs: Int, totalDirs: Int, audioCount: Int) -> Unit
        ): Result<List<WebDavFile>> {
            lastPath = rootPath
            return Result.success(map[rootPath] ?: emptyList())
        }

        override fun buildFileUrl(filePath: String): String = "http://fake$filePath"
    }

    private class FakeBookDao(private val existingId: Long) : BookDao {
        var updated: BookEntity? = null
        var updatedTitle: String? = null
        var updatedSkip: SkipUpdate? = null
        data class SkipUpdate(
            val introEnabled: Boolean,
            val introSeconds: Int,
            val introHistory: String,
            val outroEnabled: Boolean,
            val outroSeconds: Int,
            val outroHistory: String
        )

        // 模拟持久层：update 后 getBookById 应返回新值，才能让 BookRepository 的
        // "读-合并-写" 跨多次调用累积历史，否则测试无法验证去重/合并/截断
        private var storedEntity: BookEntity = BookEntity(
            id = existingId, title = "旧名", rootPath = "/old", source = SOURCE_WEBDAV
        )

        override fun getAllBooks(): Flow<List<BookEntity>> = flowOf(emptyList())
        override fun getMostRecentlyPlayedBook(): Flow<BookEntity?> =
            kotlinx.coroutines.flow.flowOf(storedEntity.takeIf { it.lastPlayedAt > 0 })
        override suspend fun getBookById(id: Long): BookEntity? =
            if (id == existingId) storedEntity else null
        override suspend fun getBookByUrl(url: String): BookEntity? = null
        override suspend fun getBookByRootPath(rootPath: String): BookEntity? = null
        override suspend fun insert(book: BookEntity): Long = book.id
        override suspend fun update(book: BookEntity) {
            updated = book
            if (book.id == existingId) storedEntity = book
        }
        override suspend fun updateTitle(id: Long, title: String) {
            updatedTitle = title
            storedEntity = storedEntity.copy(title = title)
        }
        override suspend fun delete(book: BookEntity) {}
        override suspend fun updateProgress(id: Long, position: Long, duration: Long, timestamp: Long) {
            storedEntity = storedEntity.copy(position = position, duration = duration, lastPlayedAt = timestamp)
        }
        override suspend fun updateCover(id: Long, coverUrl: String) {
            storedEntity = storedEntity.copy(coverUrl = coverUrl)
        }
        override suspend fun updateSkipSettings(
            id: Long,
            introEnabled: Boolean,
            introSeconds: Int,
            introHistory: String,
            outroEnabled: Boolean,
            outroSeconds: Int,
            outroHistory: String
        ) {
            updatedSkip = SkipUpdate(introEnabled, introSeconds, introHistory, outroEnabled, outroSeconds, outroHistory)
            storedEntity = storedEntity.copy(
                introSkipEnabled = introEnabled,
                introSkipSeconds = introSeconds,
                introSkipHistory = introHistory,
                outroSkipEnabled = outroEnabled,
                outroSkipSeconds = outroSeconds,
                outroSkipHistory = outroHistory
            )
        }
    }

    private class FakeTrackDao : TrackDao {
        val inserted = mutableListOf<TrackEntity>()

        override suspend fun insertAll(tracks: List<TrackEntity>) { inserted.addAll(tracks) }
        override suspend fun getByBookId(bookId: Long): List<TrackEntity> = emptyList()
        override fun observeByBookId(bookId: Long): Flow<List<TrackEntity>> = emptyFlow()
        override suspend fun getByIndex(bookId: Long, index: Int): TrackEntity? = null
        override suspend fun countByBookId(bookId: Long): Int = 0
        override suspend fun updateProgress(bookId: Long, index: Int, position: Long, duration: Long) {}
        override suspend fun deleteByBookId(bookId: Long) {}
    }
}
