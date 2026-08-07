package com.lingting.ui.bookshelf

import android.content.Context
import com.lingting.data.local.dao.BookDao
import com.lingting.data.local.dao.TrackDao
import com.lingting.data.local.entity.BookEntity
import com.lingting.data.local.entity.TrackEntity
import com.lingting.data.repository.BookRepository
import com.lingting.data.repository.WebDavRepository
import com.lingting.data.store.WebDavConfig
import com.lingting.data.store.WebDavConfigStore
import com.lingting.playback.FakeAudioPlayer
import com.lingting.playback.PlaybackInfo
import com.lingting.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

@OptIn(ExperimentalCoroutinesApi::class)
class BookshelfViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private lateinit var bookDao: FakeBookDao
    private lateinit var trackDao: FakeTrackDao
    private lateinit var playbackState: FakePlaybackState
    private lateinit var player: FakeAudioPlayer
    private lateinit var viewModel: BookshelfViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        bookDao = FakeBookDao()
        trackDao = FakeTrackDao()
        playbackState = FakePlaybackState()
        player = FakeAudioPlayer()
        viewModel = BookshelfViewModel(
            bookRepository = BookRepository(bookDao, trackDao),
            webDavRepository = WebDavRepository(NoOpConfigStore),
            playbackState = playbackState,
            player = player,
            context = Mockito.mock(Context::class.java)
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun directoryBook_mapsTrackCountAndCurrentTrack() = runTest {
        bookDao.booksFlow.value = listOf(
            BookEntity(
                id = 1L,
                title = "有声剧",
                rootPath = "/dav/drama",
                currentTrackIndex = 2
            )
        )
        trackDao.counts[1L] = 600
        trackDao.tracks[1L to 2] = TrackEntity(
            bookId = 1L,
            trackIndex = 2,
            title = "第3集",
            webdavUrl = "http://x/3.mp3",
            path = "/3.mp3",
            duration = 120_000,
            position = 30_000
        )

        val items = collectBooks()

        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(600, item.trackCount)
        assertEquals(3, item.currentIndex) // 1-based
        assertEquals("第3集", item.currentTrackTitle)
        assertEquals(30_000L, item.savedPosition)
        assertEquals(120_000L, item.savedDuration)
    }

    @Test
    fun singleFileBook_mapsAsOneTrack() = runTest {
        bookDao.booksFlow.value = listOf(
            BookEntity(
                id = 2L,
                title = "单文件书",
                webdavUrl = "http://x/a.mp3",
                duration = 60_000,
                position = 10_000
            )
        )

        val items = collectBooks()

        assertEquals(1, items.size)
        val item = items[0]
        assertEquals(1, item.trackCount)
        assertEquals(1, item.currentIndex)
        assertEquals("单文件书", item.currentTrackTitle)
        assertEquals(10_000L, item.savedPosition)
        assertEquals(60_000L, item.savedDuration)
    }

    @Test
    fun directoryBook_outOfRangeIndexIsClamped() = runTest {
        bookDao.booksFlow.value = listOf(
            BookEntity(id = 3L, title = "越界书", rootPath = "/dav/x", currentTrackIndex = 99)
        )
        trackDao.counts[3L] = 5
        trackDao.tracks[3L to 4] = TrackEntity(
            bookId = 3L,
            trackIndex = 4,
            title = "第5集",
            webdavUrl = "u",
            path = "p"
        )

        val items = collectBooks()

        assertEquals(5, items[0].currentIndex)
        assertEquals("第5集", items[0].currentTrackTitle)
    }

    @Test
    fun playbackInfo_exposesPlaybackStateFlow() {
        assertNull(viewModel.playbackInfo.value)

        val info = PlaybackInfo(
            bookId = 1L,
            isPlaying = true,
            currentPosition = 5_000,
            duration = 100_000,
            currentTrackIndex = 0,
            trackTitle = "第1集",
            trackCount = 600
        )
        playbackState.flow.value = info

        assertEquals(info, viewModel.playbackInfo.value)
    }

    @Test
    fun togglePlayPause_togglesPlayerPlayback() {
        // 播放中 → 暂停
        player.playWhenReady = true
        viewModel.togglePlayPause()
        assertEquals(1, player.pauseCount)

        // 已暂停 → 继续播放
        viewModel.togglePlayPause()
        assertEquals(1, player.playCount)
    }

    @Test
    fun bookItem_preservesSource() = runTest {
        bookDao.booksFlow.value = listOf(
            BookEntity(id = 10L, title = "本地书", rootPath = "/tree", source = "local"),
            BookEntity(id = 11L, title = "网盘书", rootPath = "/dav/x", source = "webdav")
        )
        val items = collectBooks()
        assertEquals(2, items.size)
        assertEquals("local", items.first { it.book.id == 10L }.book.source)
        assertEquals("webdav", items.first { it.book.id == 11L }.book.source)
    }

    @Test
    fun renameBook_updatesTitleInRepository() = runTest {
        bookDao.booksFlow.value = listOf(BookEntity(id = 1L, title = "旧名", webdavUrl = "u"))
        collectBooks()

        viewModel.renameBook(1L, " 新名 ")
        advanceUntilIdle()

        assertEquals("新名", bookDao.booksFlow.value.first().title)
    }

    /** books 为 WhileSubscribed 的 StateFlow，需要先订阅再取值。 */
    private fun kotlinx.coroutines.test.TestScope.collectBooks(): List<BookItem> {
        backgroundScope.launch { viewModel.books.collect {} }
        advanceUntilIdle()
        return viewModel.books.value
    }

    // ---- 测试替身 ----

    private class FakePlaybackState : PlaybackState {
        val flow = MutableStateFlow<PlaybackInfo?>(null)
        override val info: StateFlow<PlaybackInfo?> = flow.asStateFlow()
        override fun setCurrentBook(bookId: Long, trackCount: Int) {}
        override fun clear() {
            flow.value = null
        }
    }

    private class FakeBookDao : BookDao {
        val booksFlow = MutableStateFlow<List<BookEntity>>(emptyList())
        override fun getAllBooks(): Flow<List<BookEntity>> = booksFlow
        override fun getMostRecentlyPlayedBook(): Flow<BookEntity?> =
            booksFlow.map { list -> list.firstOrNull { it.lastPlayedAt > 0 } }
        override suspend fun getBookById(id: Long): BookEntity? =
            booksFlow.value.firstOrNull { it.id == id }
        override suspend fun getBookByUrl(url: String): BookEntity? = null
        override suspend fun getBookByRootPath(rootPath: String): BookEntity? = null
        override suspend fun insert(book: BookEntity): Long = 0L
        override suspend fun update(book: BookEntity) {}
        override suspend fun updateTitle(id: Long, title: String) {
            booksFlow.value = booksFlow.value.map { if (it.id == id) it.copy(title = title) else it }
        }
        override suspend fun delete(book: BookEntity) {}
        override suspend fun updateProgress(id: Long, position: Long, duration: Long, timestamp: Long) {}
        override suspend fun updateCover(id: Long, coverUrl: String) {}
        override suspend fun updateSkipSettings(
            id: Long,
            introEnabled: Boolean,
            introSeconds: Int,
            introHistory: String,
            outroEnabled: Boolean,
            outroSeconds: Int,
            outroHistory: String
        ) {}
    }

    private class FakeTrackDao : TrackDao {
        val counts = mutableMapOf<Long, Int>()
        val tracks = mutableMapOf<Pair<Long, Int>, TrackEntity>()
        override suspend fun insertAll(tracks: List<TrackEntity>) {}
        override suspend fun getByBookId(bookId: Long): List<TrackEntity> = emptyList()
        override fun observeByBookId(bookId: Long): Flow<List<TrackEntity>> = emptyFlow()
        override suspend fun getByIndex(bookId: Long, index: Int): TrackEntity? =
            tracks[bookId to index]
        override suspend fun countByBookId(bookId: Long): Int = counts[bookId] ?: 0
        override suspend fun updateProgress(bookId: Long, index: Int, position: Long, duration: Long) {}
        override suspend fun deleteByBookId(bookId: Long) {}
    }

    private object NoOpConfigStore : WebDavConfigStore {
        override fun save(config: WebDavConfig) {}
        override fun load(): WebDavConfig? = null
        override fun clear() {}
    }
}
