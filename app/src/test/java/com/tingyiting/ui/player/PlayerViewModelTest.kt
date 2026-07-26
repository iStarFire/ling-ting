package com.tingyiting.ui.player

import com.tingyiting.data.model.Book
import com.tingyiting.data.model.Track
import com.tingyiting.data.repository.BookRepository
import com.tingyiting.playback.FakeAudioPlayer
import com.tingyiting.playback.PlayState
import com.tingyiting.playback.PlayableItem
import com.tingyiting.playback.PlaybackError
import com.tingyiting.playback.PlaybackInfo
import com.tingyiting.playback.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when` as whenever
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var player: FakeAudioPlayer
    private lateinit var bookRepository: BookRepository
    private lateinit var playbackState: PlaybackState
    private lateinit var vm: PlayerViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        player = FakeAudioPlayer()
        bookRepository = mock(BookRepository::class.java)
        playbackState = object : PlaybackState {
            override val info: StateFlow<PlaybackInfo?> = MutableStateFlow(null)
            override fun setCurrentBook(bookId: Long, trackCount: Int) {}
            override fun clear() {}
        }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // region helpers

    /** runTest 收尾会推进调度器直到空闲，必须先 release 停掉 ViewModel 的无限轮询协程。 */
    private fun vmTest(testBody: suspend TestScope.() -> Unit) =
        runTest(testDispatcher.scheduler) {
            try {
                testBody()
            } finally {
                if (::vm.isInitialized) vm.release()
            }
        }

    private fun book(
        id: Long,
        title: String,
        position: Long,
        duration: Long,
        currentTrackIndex: Int,
        rootPath: String = "",
        webdavUrl: String = ""
    ) = Book(
        id = id,
        title = title,
        author = "",
        coverUrl = "",
        webdavUrl = webdavUrl,
        rootPath = rootPath,
        currentTrackIndex = currentTrackIndex,
        duration = duration,
        position = position
    )

    private fun track(
        index: Int,
        title: String,
        webdavUrl: String,
        position: Long,
        duration: Long
    ) = Track(index, title, webdavUrl, "/$title", duration, position)

    // endregion

    // region 任务1：快进快退边界

    @Test
    fun seekBy_clampsToBounds() = vmTest {
        val book = book(1, "A", position = 5_000, duration = 100_000, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        // 低于 0：钳制到 0
        vm.seekBy(-10_000)
        assertEquals(0, vm.uiState.value.currentPosition)
        assertEquals(FakeAudioPlayer.SeekCall(null, 0), player.seekCalls.last())

        // 正常区间
        vm.seekBy(95_000)
        assertEquals(95_000, vm.uiState.value.currentPosition)
        assertEquals(FakeAudioPlayer.SeekCall(null, 95_000), player.seekCalls.last())

        // 超过总时长：钳制到总时长
        vm.seekBy(15_000)
        assertEquals(100_000, vm.uiState.value.currentPosition)
        assertEquals(FakeAudioPlayer.SeekCall(null, 100_000), player.seekCalls.last())
    }

    // endregion

    // region 任务2：缓冲不触发整页 loading

    @Test
    fun buffering_doesNotSetInitialLoading() = vmTest {
        val book = book(1, "A", position = 0, duration = 100_000, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        assertFalse(vm.uiState.value.isInitialLoading, "加载完成后整页 loading 应关闭")

        player.listener.onPlayStateChanged(PlayState.BUFFERING)
        assertTrue(vm.uiState.value.isBuffering, "进入缓冲")
        assertFalse(vm.uiState.value.isInitialLoading, "缓冲不应重新触发整页 loading")

        player.listener.onPlayStateChanged(PlayState.READY)
        assertFalse(vm.uiState.value.isBuffering)
        assertFalse(vm.uiState.value.isInitialLoading)
    }

    // endregion

    // region 任务2：拖动进度只在结束时 seek

    @Test
    fun seek_doesNotSeekDuringPolling_onlyOnExplicitSeek() = vmTest {
        val book = book(1, "A", position = 5_000, duration = 100_000, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        // 初始化通过 setItem 携带起始位置，不产生 seek
        assertEquals(5_000, player.setItemCalls.single().second)
        assertTrue(player.seekCalls.isEmpty())

        // 推进若干轮位置轮询：轮询只读取位置，从不 seek
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(player.seekCalls.isEmpty())

        // 拖动结束时才真正 seek（UI 在 onValueChangeFinished 才调用 seekTo）
        vm.seekTo(60_000)
        assertEquals(60_000, vm.uiState.value.currentPosition)
        assertEquals(listOf(FakeAudioPlayer.SeekCall(null, 60_000)), player.seekCalls)
    }

    // endregion

    // region 任务1：切集前保存旧 track 进度（手动 + 自动）

    @Test
    fun nextTrack_savesOldTrackProgressBeforeSwitch() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, rootPath = "/book")
        val t0 = track(10, "c1", "http://x/1.mp3", 50_000, 100_000)
        val t1 = track(11, "c2", "http://x/2.mp3", 0, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0, t1))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()
        // buildPlaylist 已从 t0 的保存进度 50_000 起播
        assertEquals(50_000, player.currentPosition)

        vm.nextTrack() // 手动切集：先保存旧集进度
        runCurrent()

        verify(bookRepository).saveTrackProgress(1, 0, 50_000, 100_000)
        assertEquals(1, player.seekToNextCount)
    }

    @Test
    fun autoTransition_marksOldTrackCompleted() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, rootPath = "/book")
        val t0 = track(10, "c1", "http://x/1.mp3", 50_000, 100_000)
        val t1 = track(11, "c2", "http://x/2.mp3", 0, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0, t1))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        player.currentItemIndex = 1
        player.listener.onItemTransition(newIndex = 1, isAuto = true)
        runCurrent()

        // 旧集（index 0）被写为完成
        verify(bookRepository).saveTrackProgress(1, 0, 100_000, 100_000)
        assertEquals(1, vm.uiState.value.currentTrackIndex)
    }

    // endregion

    // region 任务3：95% 完成判定

    @Test
    fun isTrackCompleted_threshold95Percent() {
        val below = track(1, "c", "u", 94_999, 100_000)
        assertFalse(isTrackCompleted(below))
        assertEquals(94, trackProgressPercent(below))

        val atThreshold = track(1, "c", "u", 95_000, 100_000)
        assertTrue(isTrackCompleted(atThreshold))
        assertEquals(95, trackProgressPercent(atThreshold))

        val ended = track(1, "c", "u", 100_000, 100_000)
        assertTrue(isTrackCompleted(ended))
        assertEquals(100, trackProgressPercent(ended))
    }

    // endregion

    // region 任务3：IDLE / ENDED / error 恢复

    @Test
    fun togglePlayPause_idle_preparesAndPlays() = vmTest {
        val book = book(1, "A", position = 0, duration = 100_000, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        player.playState = PlayState.IDLE
        val prepareBefore = player.prepareCount
        val playBefore = player.playCount
        vm.togglePlayPause()

        assertEquals(prepareBefore + 1, player.prepareCount)
        assertEquals(playBefore + 1, player.playCount)
    }

    @Test
    fun togglePlayPause_ended_seeksToStartAndPlays() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, rootPath = "/book")
        val t0 = track(10, "c1", "http://x/1.mp3", 0, 100_000)
        val t1 = track(11, "c2", "http://x/2.mp3", 0, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0, t1))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        player.playState = PlayState.ENDED
        val playBefore = player.playCount
        vm.togglePlayPause()

        assertEquals(FakeAudioPlayer.SeekCall(0, 0), player.seekCalls.last())
        assertEquals(playBefore + 1, player.playCount)
    }

    @Test
    fun togglePlayPause_onError_rebuildsMediaSourceAndRetries() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, rootPath = "/book")
        val t0 = track(10, "c1", "http://x/1.mp3", 30_000, 100_000)
        val t1 = track(11, "c2", "http://x/2.mp3", 0, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0, t1))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        player.listener.onPlaybackError(
            PlaybackError(codeName = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED", causeType = "IOException")
        )
        assertEquals("播放失败，请检查网络后重试", vm.uiState.value.playbackError)

        val prepareBefore = player.prepareCount
        val playBefore = player.playCount
        vm.togglePlayPause() // 错误分支：重建当前媒体源，从保存位置重试

        assertEquals(0 to PlayableItem("http://x/1.mp3", "c1"), player.replacedItems.single())
        assertEquals(FakeAudioPlayer.SeekCall(0, 30_000), player.seekCalls.last())
        assertEquals(prepareBefore + 1, player.prepareCount)
        assertEquals(playBefore + 1, player.playCount)
        assertNull(vm.uiState.value.playbackError)
    }

    // endregion

    // region 任务3：日志脱敏（不泄露媒体 URL）

    @Test
    fun onPlaybackError_logsDesensitized_withoutLeaking() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, rootPath = "/book")
        val t0 = track(10, "c1", "http://x/secret.mp3?token=TOPSECRET", 0, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        // 错误信息经窄接口已脱敏（仅错误码/HTTP 状态/异常类型），处理过程不应崩溃
        player.listener.onPlaybackError(
            PlaybackError(codeName = "ERROR_CODE_IO_BAD_HTTP_STATUS", httpStatus = 403, causeType = "IOException")
        )
        assertEquals("播放失败，请检查网络后重试", vm.uiState.value.playbackError)
    }

    // endregion
}
