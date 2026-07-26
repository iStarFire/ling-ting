package com.tingyiting.ui.browser

import com.tingyiting.data.local.dao.BookDao
import com.tingyiting.data.local.dao.TrackDao
import com.tingyiting.data.local.entity.BookEntity
import com.tingyiting.data.local.entity.TrackEntity
import com.tingyiting.data.model.Track
import com.tingyiting.data.model.WebDavFile
import com.tingyiting.data.repository.BookRepository
import com.tingyiting.data.repository.WebDavRepository
import com.tingyiting.data.store.WebDavConfig
import com.tingyiting.data.store.WebDavConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowserViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var webDavRepo: FakeWebDavRepository
    private lateinit var bookRepo: FakeBookRepository
    private lateinit var viewModel: BrowserViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        webDavRepo = FakeWebDavRepository()
        bookRepo = FakeBookRepository()
        viewModel = BrowserViewModel(webDavRepo, bookRepo)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun toggleSelection_addsAndRemovesPath() {
        assertTrue(viewModel.uiState.value.selectedPaths.isEmpty())

        viewModel.toggleSelection("/a")
        assertTrue(viewModel.uiState.value.selectedPaths.contains("/a"))
        assertEquals(1, viewModel.uiState.value.selectedPaths.size)

        viewModel.toggleSelection("/a")
        assertFalse(viewModel.uiState.value.selectedPaths.contains("/a"))
    }

    @Test
    fun toggleSelection_supportsMultipleDirs() {
        viewModel.toggleSelection("/a")
        viewModel.toggleSelection("/b")
        viewModel.toggleSelection("/c")

        assertEquals(setOf("/a", "/b", "/c"), viewModel.uiState.value.selectedPaths)

        viewModel.clearSelection()
        assertTrue(viewModel.uiState.value.selectedPaths.isEmpty())
    }

    @Test
    fun importDirectories_createsOneBookPerDirAndReportsProgress() = runTest {
        webDavRepo.filesByDir["/d1"] = listOf(audio("1.mp3"), audio("2.mp3"), audio("3.mp3"))
        webDavRepo.filesByDir["/d2"] = listOf(audio("4.mp3"))

        val ids = viewModel.importDirectories(listOf("/d1", "/d2"))
        advanceUntilIdle()

        assertEquals(2, ids.size)
        // 每个目录生成一本，曲目数量与目录下音频数一致
        assertEquals(3, bookRepo.addedTracks["/d1"]?.size)
        assertEquals(1, bookRepo.addedTracks["/d2"]?.size)
        // 进度回调在每个目录扫描时被调用
        assertTrue(webDavRepo.progressCalls >= 2)
        // 完成后停止导入态并标记完成，跳回书架
        assertFalse(viewModel.uiState.value.isImporting)
        assertTrue(viewModel.uiState.value.importDone)
        assertNull(viewModel.uiState.value.importError)
    }

    @Test
    fun importDirectories_emptyDirIsSkipped() = runTest {
        webDavRepo.filesByDir["/empty"] = emptyList()

        val ids = viewModel.importDirectories(listOf("/empty"))
        advanceUntilIdle()

        assertEquals(0, ids.size)
        assertTrue(bookRepo.addedTracks.isEmpty())
        assertTrue(viewModel.uiState.value.importDone)
        assertFalse(viewModel.uiState.value.isImporting)
    }

    @Test
    fun importDirectories_emptySelectionDoesNothing() = runTest {
        val ids = viewModel.importDirectories(emptyList())
        advanceUntilIdle()
        assertEquals(0, ids.size)
        assertFalse(viewModel.uiState.value.importDone)
    }

    @Test
    fun importDirectories_reportsDeterminateProgress() = runTest {
        webDavRepo.filesByDir["/d1"] = listOf(audio("1.mp3"), audio("2.mp3"))
        viewModel.importDirectories(listOf("/d1"))
        advanceUntilIdle()

        val p = webDavRepo.lastProgress
        assertNotNull(p)
        // (已扫描目录数, 已知总目录数, 已发现音频数)
        assertEquals(1, p!!.first)
        assertEquals(1, p.second)
        assertEquals(2, p.third)
        // 完成后进度条分数被清空
        assertNull(viewModel.uiState.value.importProgressFraction)
    }

    @Test
    fun reimportCurrentDirectory_entersModeAndRefreshes() = runTest {
        viewModel.startReimport(1L, "/dav/drama")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.isReimportMode)
        assertEquals(1L, viewModel.uiState.value.reimportBookId)

        viewModel.reimportCurrentDirectory()
        advanceUntilIdle()

        assertEquals(1, bookRepo.reimportCalls)
        assertTrue(viewModel.uiState.value.importDone)
        assertFalse(viewModel.uiState.value.isImporting)
        assertNull(viewModel.uiState.value.importError)
    }

    private fun audio(name: String) = WebDavFile(name = name, path = "/x/$name", isDirectory = false)

    // ---- 测试替身 ----

    private object NoOpConfigStore : WebDavConfigStore {
        override fun save(config: WebDavConfig) {}
        override fun load(): WebDavConfig? = null
        override fun clear() {}
    }

    private class FakeWebDavRepository : WebDavRepository(NoOpConfigStore) {
        val filesByDir = mutableMapOf<String, List<WebDavFile>>()
        var progressCalls = 0
        var lastProgress: Triple<Int, Int, Int>? = null

        override suspend fun collectAudioFiles(
            rootPath: String,
            onProgress: (scannedDirs: Int, totalDirs: Int, audioCount: Int) -> Unit
        ): Result<List<WebDavFile>> {
            val files = filesByDir[rootPath] ?: emptyList()
            onProgress(1, 1, files.size)
            progressCalls++
            lastProgress = Triple(1, 1, files.size)
            return Result.success(files)
        }

        override fun buildFileUrl(filePath: String): String = "http://fake$filePath"
    }

    private class FakeBookRepository : BookRepository(stubBookDao, stubTrackDao) {
        val addedTracks = mutableMapOf<String, List<Track>>()
        var reimportCalls = 0
        private var nextId = 1L

        override suspend fun addBookWithTracks(
            title: String,
            author: String,
            rootPath: String,
            tracks: List<Track>,
            source: String
        ): Long {
            addedTracks[rootPath] = tracks
            return nextId++
        }

        override suspend fun reimportWebDav(bookId: Long, path: String): Result<Long> {
            reimportCalls++
            return Result.success(bookId)
        }
    }

    private companion object {
        val stubBookDao = object : BookDao {
            override fun getAllBooks(): Flow<List<BookEntity>> = flowOf(emptyList())
            override suspend fun getBookById(id: Long): BookEntity? = null
            override suspend fun getBookByUrl(url: String): BookEntity? = null
            override suspend fun getBookByRootPath(rootPath: String): BookEntity? = null
            override suspend fun insert(book: BookEntity): Long = 0L
            override suspend fun update(book: BookEntity) {}
            override suspend fun delete(book: BookEntity) {}
            override suspend fun updateTitle(id: Long, title: String) {}
            override suspend fun updateProgress(id: Long, position: Long, duration: Long, timestamp: Long) {}
            override suspend fun updateCover(id: Long, coverUrl: String) {}
        }

        val stubTrackDao = object : TrackDao {
            override suspend fun insertAll(tracks: List<TrackEntity>) {}
            override suspend fun getByBookId(bookId: Long): List<TrackEntity> = emptyList()
            override fun observeByBookId(bookId: Long): Flow<List<TrackEntity>> = emptyFlow()
            override suspend fun getByIndex(bookId: Long, index: Int): TrackEntity? = null
            override suspend fun countByBookId(bookId: Long): Int = 0
            override suspend fun updateProgress(bookId: Long, index: Int, position: Long, duration: Long) {}
            override suspend fun deleteByBookId(bookId: Long) {}
        }
    }
}
