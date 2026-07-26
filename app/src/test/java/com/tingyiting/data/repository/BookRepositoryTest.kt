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

        override fun getAllBooks(): Flow<List<BookEntity>> = flowOf(emptyList())
        override suspend fun getBookById(id: Long): BookEntity? =
            if (id == existingId) BookEntity(id = id, title = "旧名", rootPath = "/old", source = SOURCE_WEBDAV)
            else null
        override suspend fun getBookByUrl(url: String): BookEntity? = null
        override suspend fun getBookByRootPath(rootPath: String): BookEntity? = null
        override suspend fun insert(book: BookEntity): Long = book.id
        override suspend fun update(book: BookEntity) { updated = book }
        override suspend fun updateTitle(id: Long, title: String) { updatedTitle = title }
        override suspend fun delete(book: BookEntity) {}
        override suspend fun updateProgress(id: Long, position: Long, duration: Long, timestamp: Long) {}
        override suspend fun updateCover(id: Long, coverUrl: String) {}
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
