package com.lingting.ui.player

import com.lingting.data.model.Book
import com.lingting.data.model.Track
import com.lingting.data.repository.BookRepository
import com.lingting.data.repository.CoverRepository
import com.lingting.playback.FakeAudioPlayer
import com.lingting.playback.PlayState
import com.lingting.playback.PlayableItem
import com.lingting.playback.PlaybackError
import com.lingting.playback.PlaybackInfo
import com.lingting.playback.PlaybackState
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
import android.content.Context
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
        webdavUrl: String = "",
        introSkipEnabled: Boolean = false,
        introSkipSeconds: Int = 0,
        introSkipHistory: List<Int> = emptyList(),
        outroSkipEnabled: Boolean = false,
        outroSkipSeconds: Int = 0,
        outroSkipHistory: List<Int> = emptyList()
    ) = Book(
        id = id,
        title = title,
        author = "",
        coverUrl = "",
        webdavUrl = webdavUrl,
        rootPath = rootPath,
        currentTrackIndex = currentTrackIndex,
        duration = duration,
        position = position,
        introSkipEnabled = introSkipEnabled,
        introSkipSeconds = introSkipSeconds,
        introSkipHistory = introSkipHistory,
        outroSkipEnabled = outroSkipEnabled,
        outroSkipSeconds = outroSkipSeconds,
        outroSkipHistory = outroSkipHistory
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
    fun buildPlaylist_passesCoverArtworkToPlayer() = vmTest {
        val cover = "file:///data/data/com.lingting/files/covers/book_1.jpg"
        val book = book(1, "A", position = 0, duration = 0, 0, rootPath = "/book")
            .copy(coverUrl = cover)
        val t0 = track(10, "c1", "http://x/1.mp3", 0, 100_000)
        val t1 = track(11, "c2", "http://x/2.mp3", 0, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0, t1))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        // 播放列表与单文件播放均应将封面地址透传给播放器，供状态栏/锁屏媒体通知显示
        assertEquals(cover, player.items[0].artwork)
        assertEquals(cover, player.items[1].artwork)
    }

    @Test
    fun playSingleFile_passesCoverArtworkToPlayer() = vmTest {
        val cover = "file:///data/data/com.lingting/files/covers/book_1.jpg"
        val book = book(1, "A", position = 0, duration = 100_000, 0, webdavUrl = "http://x/a.mp3")
            .copy(coverUrl = cover)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        assertEquals(cover, player.items.single().artwork)
    }

    @Test
    fun blankCoverUrl_doesNotPassArtworkToPlayer() = vmTest {
        val book = book(1, "A", position = 0, duration = 100_000, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        assertNull(player.items.single().artwork)
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

    // region 封面搜刮：关键词可编辑，默认回退到书名

    @Test
    fun scrapeCover_fallsBackToTitle_whenQueryBlank() = vmTest {
        val book = book(1, "默认专辑名", position = 0, duration = 0, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        val coverRepository = FakeCoverRepository(bookRepository)

        vm = PlayerViewModel(bookRepository, player, playbackState, coverRepository)
        vm.initialize(1)
        runCurrent()

        vm.scrapeCoverFromDouban("   ") // 空白：应回退到书名
        runCurrent()

        assertEquals("默认专辑名", coverRepository.lastQuery)
        assertEquals("/covers/book_1.jpg", vm.uiState.value.coverUrl)
        assertNull(vm.uiState.value.coverError)
    }

    @Test
    fun scrapeCover_usesCustomQuery_whenProvided() = vmTest {
        val book = book(1, "默认专辑名", position = 0, duration = 0, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        val coverRepository = FakeCoverRepository(bookRepository)

        vm = PlayerViewModel(bookRepository, player, playbackState, coverRepository)
        vm.initialize(1)
        runCurrent()

        vm.scrapeCoverFromDouban("  自定义书名  ") // 应去掉首尾空格后使用
        runCurrent()

        assertEquals("自定义书名", coverRepository.lastQuery)
    }

    // endregion

    // region 睡眠定时：按时间/按集数 + 上次定时记忆

    @Test
    fun setSleepTimer_minutes_recordsChoiceAndStartsCountdown() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        vm.setSleepTimer(TimerChoice.Minutes(30))
        runCurrent()

        assertEquals(30, vm.uiState.value.sleepTimerRemaining)
        assertEquals(TimerChoice.Minutes(30), vm.uiState.value.lastTimerChoice)
    }

    @Test
    fun setSleepTimer_episodes_decrementsOnAutoTransition() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, rootPath = "/book")
        val t0 = track(10, "c1", "http://x/1.mp3", 0, 100_000)
        val t1 = track(11, "c2", "http://x/2.mp3", 0, 100_000)
        val t2 = track(12, "c3", "http://x/3.mp3", 0, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0, t1, t2))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        vm.setSleepTimer(TimerChoice.Episodes(2))
        runCurrent()
        assertEquals(2, vm.uiState.value.sleepTimerEpisodesRemaining)

        val pauseBefore = player.pauseCount

        // 第一次自然切集：剩余 1
        player.listener.onItemTransition(newIndex = 1, isAuto = true)
        runCurrent()
        assertEquals(1, vm.uiState.value.sleepTimerEpisodesRemaining)

        // 第二次自然切集：剩余 0 → 自动暂停
        player.listener.onItemTransition(newIndex = 2, isAuto = true)
        runCurrent()
        assertNull(vm.uiState.value.sleepTimerEpisodesRemaining)
        assertEquals(pauseBefore + 1, player.pauseCount)

        // 手动切集（isAuto=false）不应影响集数定时
        vm.setSleepTimer(TimerChoice.Episodes(1))
        runCurrent()
        player.listener.onItemTransition(newIndex = 0, isAuto = false)
        runCurrent()
        assertEquals(1, vm.uiState.value.sleepTimerEpisodesRemaining)
    }

    @Test
    fun toggleLastTimer_restoresWhenInactive_cancelsWhenActive() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        // 首次设置 → lastChoice 被记录
        vm.setSleepTimer(TimerChoice.Episodes(3))
        runCurrent()
        assertEquals(TimerChoice.Episodes(3), vm.uiState.value.lastTimerChoice)
        assertEquals(3, vm.uiState.value.sleepTimerEpisodesRemaining)

        // 切换：当前激活 → 取消
        vm.toggleLastTimer()
        runCurrent()
        assertNull(vm.uiState.value.sleepTimerEpisodesRemaining)
        assertEquals(TimerChoice.Episodes(3), vm.uiState.value.lastTimerChoice)

        // 再切换：未激活 → 恢复上次选择
        vm.toggleLastTimer()
        runCurrent()
        assertEquals(3, vm.uiState.value.sleepTimerEpisodesRemaining)
    }

    @Test
    fun cancelSleepTimer_clearsBothActiveFields() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        vm.setSleepTimer(TimerChoice.Episodes(2))
        runCurrent()
        assertEquals(2, vm.uiState.value.sleepTimerEpisodesRemaining)

        vm.cancelSleepTimer()
        runCurrent()
        assertNull(vm.uiState.value.sleepTimerEpisodesRemaining)
        // lastTimerChoice 保留以便后续一键恢复
        assertEquals(TimerChoice.Episodes(2), vm.uiState.value.lastTimerChoice)
    }

    // endregion

    // region 任务7：跳过头尾

    @Test
    fun applySkipSettings_persistsValuesAndUpdatesState() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        vm.applySkipSettings(introEnabled = true, introSeconds = 30, outroEnabled = true, outroSeconds = 60)
        runCurrent()

        assertEquals(true, vm.uiState.value.introSkipEnabled)
        assertEquals(30, vm.uiState.value.introSkipSeconds)
        assertEquals(true, vm.uiState.value.outroSkipEnabled)
        assertEquals(60, vm.uiState.value.outroSkipSeconds)
        verify(bookRepository).updateSkipSettings(1L, true, 30, true, 60)
    }

    @Test
    fun applySkipSettings_introBelowCurrent_seeksToIntroImmediately() = vmTest {
        val book = book(1, "A", position = 0, duration = 100_000, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        // 当前 5s，应用片头 30s：应一次性跳到 30s
        player.currentPosition = 5_000

        vm.applySkipSettings(introEnabled = true, introSeconds = 30, outroEnabled = false, outroSeconds = 0)
        runCurrent()

        assertEquals(30_000L, player.seekCalls.last().positionMs)
        assertEquals(30_000L, vm.uiState.value.currentPosition)
    }

    @Test
    fun applySkipSettings_introAtOrAboveCurrent_noSeek() = vmTest {
        val book = book(1, "A", position = 0, duration = 100_000, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        // 当前 45s，应用片头 30s：不应触发 seek
        player.currentPosition = 45_000
        val seekCountBefore = player.seekCalls.size

        vm.applySkipSettings(introEnabled = true, introSeconds = 30, outroEnabled = false, outroSeconds = 0)
        runCurrent()

        assertEquals(seekCountBefore, player.seekCalls.size)
    }

    @Test
    fun buildPlaylist_withIntroEnabled_startsAtIntroSeconds() = vmTest {
        // 起始进度 0，启用片头 30s：buildPlaylist 应直接定位到 30s
        val book = book(
            id = 1, title = "A", position = 0, duration = 0, currentTrackIndex = 0,
            rootPath = "/book",
            introSkipEnabled = true, introSkipSeconds = 30
        )
        val t0 = track(10, "c1", "http://x/1.mp3", 0, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        // 启动位置应被片头覆盖
        assertEquals(30_000L, player.currentPosition)
        assertEquals(30_000L, vm.uiState.value.currentPosition)
    }

    @Test
    fun buildPlaylist_introOverriddenBySavedPosition() = vmTest {
        // 已保存进度 60s > intro 30s：应保持 60s 不动
        val book = book(
            id = 1, title = "A", position = 0, duration = 0, currentTrackIndex = 0,
            rootPath = "/book",
            introSkipEnabled = true, introSkipSeconds = 30
        )
        val t0 = track(10, "c1", "http://x/1.mp3", 60_000, 100_000)
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(listOf(t0))

        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        assertEquals(60_000L, player.currentPosition)
    }

    @Test
    fun applySkipSettings_clampsValuesToRange() = vmTest {
        val book = book(1, "A", position = 0, duration = 0, 0, webdavUrl = "http://x/a.mp3")
        whenever(bookRepository.getBookById(1)).thenReturn(book)
        whenever(bookRepository.getTracks(1)).thenReturn(emptyList())
        vm = PlayerViewModel(bookRepository, player, playbackState)
        vm.initialize(1)
        runCurrent()

        vm.applySkipSettings(introEnabled = true, introSeconds = 999, outroEnabled = true, outroSeconds = -1)
        runCurrent()

        assertEquals(300, vm.uiState.value.introSkipSeconds)
        assertEquals(0, vm.uiState.value.outroSkipSeconds)
    }

    // endregion
}

/** 记录 scrapeFromDouban 收到的查询词，便于断言默认回退/自定义关键词逻辑。 */
private class FakeCoverRepository(
    private val bookRepository: BookRepository
) : CoverRepository(mock(Context::class.java), bookRepository) {
    var lastQuery: String? = null
        private set

    override suspend fun scrapeFromDouban(bookId: Long, title: String): Result<String> {
        lastQuery = title
        return Result.success("/covers/book_$bookId.jpg")
    }
}
