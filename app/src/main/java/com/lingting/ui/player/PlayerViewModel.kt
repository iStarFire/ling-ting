package com.lingting.ui.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingting.data.model.CoverCrop
import com.lingting.data.model.Book
import com.lingting.data.model.Track
import com.lingting.data.repository.BookRepository
import com.lingting.data.repository.CoverRepository
import com.lingting.playback.AudioPlayer
import com.lingting.service.PlaybackService
import com.lingting.playback.PlayState
import com.lingting.playback.PlayableItem
import com.lingting.playback.PlaybackError
import com.lingting.playback.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class PlayerUiState(
    val bookId: Long = 0,
    val title: String = "",
    val isPlaying: Boolean = false,
    val playWhenReady: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isInitialLoading: Boolean = true,
    val isBuffering: Boolean = false,
    val error: String? = null,
    val playbackError: String? = null,
    val sleepTimerRemaining: Int? = null,
    /** 按集数定时剩余集数（null = 未启用）。 */
    val sleepTimerEpisodesRemaining: Int? = null,
    /** 上次用户选择的定时设置（用于抽屉顶部"上次定时"行，可一键恢复）。 */
    val lastTimerChoice: TimerChoice? = null,
    val isPlaylist: Boolean = false,
    val currentTrackIndex: Int = 0,
    val trackCount: Int = 0,
    val trackTitle: String = "",
    val tracks: List<Track> = emptyList(),
    val coverUrl: String = "",
    val isCoverUpdating: Boolean = false,
    val coverError: String? = null,
    /** 是否启用片头跳过。 */
    val introSkipEnabled: Boolean = false,
    /** 片头跳过秒数（0-180）。 */
    val introSkipSeconds: Int = 0,
    val introSkipHistory: List<Int> = emptyList(),
    val outroSkipEnabled: Boolean = false,
    val outroSkipSeconds: Int = 0,
    val outroSkipHistory: List<Int> = emptyList()
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val player: AudioPlayer,
    private val playbackState: PlaybackState,
    private val coverRepository: CoverRepository? = null,
    private val sleepTimerPrefs: SleepTimerPrefs? = null,
    @ApplicationContext private val appContext: Context? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    private var initializeJob: Job? = null
    private var progressSaveJob: Job? = null
    private var positionJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var listenerAttached = false

    /** 当前加载的书与曲目，供错误恢复时重建媒体源。 */
    private var activeBook: Book? = null
    private var activeTracks: List<Track> = emptyList()

    /** 按集数定时剩余集数；为 0 表示未启用。 */
    private var episodesRemaining: Int = 0

    fun initialize(bookId: Long) {
        android.util.Log.d("PlayerPerf", "initialize() start bookId=$bookId t=${System.currentTimeMillis()}")
        if (bookId == 0L || (initializeJob?.isActive == true && _uiState.value.bookId == bookId)) {
            android.util.Log.d("PlayerPerf", "initialize() skipped (already loading same book) t=${System.currentTimeMillis()}")
            return
        }
        initializeJob?.cancel()
        cancelSleepTimerInternal()
        val lastChoice = sleepTimerPrefs?.resolveForBook(bookId)
        _uiState.update {
            it.copy(
                bookId = bookId,
                isInitialLoading = true,
                error = null,
                lastTimerChoice = lastChoice
            )
        }

        initializeJob = viewModelScope.launch {
            try {
                android.util.Log.d("PlayerPerf", "initialize() coroutine: before getBookById t=${System.currentTimeMillis()}")
                val book = bookRepository.getBookById(bookId)
                android.util.Log.d("PlayerPerf", "initialize() coroutine: after getBookById book=${book?.title} t=${System.currentTimeMillis()}")
                if (book == null) {
                    _uiState.update { it.copy(error = "书籍未找到", isInitialLoading = false) }
                    return@launch
                }
                val tracks = bookRepository.getTracks(bookId)
                android.util.Log.d("PlayerPerf", "initialize() coroutine: after getTracks count=${tracks.size} t=${System.currentTimeMillis()}")
                activeBook = book
                activeTracks = tracks
                val isDirectoryBook = book.rootPath.isNotEmpty()
                if (isDirectoryBook && tracks.isEmpty()) {
                    _uiState.update {
                        it.copy(title = book.title, error = "该目录书没有可播放的音频", isInitialLoading = false)
                    }
                    return@launch
                }
                if (!isDirectoryBook && tracks.isEmpty() && book.webdavUrl.isEmpty()) {
                    _uiState.update {
                        it.copy(title = book.title, error = "该书没有可播放的音频地址", isInitialLoading = false)
                    }
                    return@launch
                }

                attachListener()
                val activeInfo = playbackState.info.value
                if (activeInfo?.bookId == bookId && player.itemCount > 0) {
                    bindToActivePlayer(book, tracks)
                } else if (tracks.isNotEmpty()) {
                    buildPlaylist(book, tracks)
                } else {
                    playSingleFile(book)
                }
                startJobs(book.id)
            } catch (error: Exception) {
                Log.e(TAG, "加载书籍异常 bookId=$bookId", error)
                _uiState.update {
                    it.copy(
                        error = "加载失败：${error.message ?: error.javaClass.simpleName}",
                        isInitialLoading = false
                    )
                }
            }
        }
    }

    private fun bindToActivePlayer(book: Book, tracks: List<Track>) {
        val index = player.currentItemIndex.coerceAtLeast(0)
        val isPlaylist = tracks.isNotEmpty()
        val duration = validDuration(player.duration, tracks.getOrNull(index)?.duration ?: book.duration)
        val position = player.currentPosition.coerceAtLeast(0)
        _uiState.update {
            it.copy(
                bookId = book.id,
                title = book.title,
                isPlaying = player.isPlaying,
                playWhenReady = player.playWhenReady,
                currentPosition = position,
                duration = duration,
                isInitialLoading = false,
                isBuffering = player.playState == PlayState.BUFFERING,
                isPlaylist = isPlaylist,
                currentTrackIndex = index,
                trackCount = if (isPlaylist) tracks.size else 1,
                trackTitle = tracks.getOrNull(index)?.title ?: book.title,
                tracks = updateTrackProgress(tracks, index, position, duration),
                coverUrl = book.coverUrl,
                introSkipEnabled = book.introSkipEnabled,
                introSkipSeconds = book.introSkipSeconds,
                introSkipHistory = book.introSkipHistory,
                outroSkipEnabled = book.outroSkipEnabled,
                outroSkipSeconds = book.outroSkipSeconds,
                outroSkipHistory = book.outroSkipHistory
            )
        }
    }

    private suspend fun buildPlaylist(book: Book, tracks: List<Track>) {
        val startIndex = book.currentTrackIndex.coerceIn(0, tracks.lastIndex)
        val startTrack = tracks[startIndex]
        val resume = resumePosition(startTrack)
        val startPosition = effectiveStartPosition(resume, book)

        // 625 集 PlayableItem 映射在主线程耗时 ~500ms，移到后台线程；
        // 回到主线程后调原有的 setPlaylist（利用 ExoAudioPlayer.toMediaItem 的 FileProvider 等逻辑）
        android.util.Log.d("PlayerPerf", "buildPlaylist: before background map t=${System.currentTimeMillis()}")
        val items: List<PlayableItem> = if (appContext != null) {
            withContext(Dispatchers.Default) {
                tracks.map { it.toPlayableItem(book) }
            }
        } else {
            tracks.map { it.toPlayableItem(book) }
        }
        android.util.Log.d("PlayerPerf", "buildPlaylist: after background map t=${System.currentTimeMillis()}")
        player.setPlaylist(items, startIndex, startPosition)
        android.util.Log.d("PlayerPerf", "buildPlaylist: after setPlaylist t=${System.currentTimeMillis()}")
        player.prepare()
        android.util.Log.d("PlayerPerf", "buildPlaylist: after prepare t=${System.currentTimeMillis()}")
        playAudio()
        android.util.Log.d("PlayerPerf", "buildPlaylist: after playAudio t=${System.currentTimeMillis()}")
        playbackState.setCurrentBook(book.id, tracks.size)

        _uiState.update {
            it.copy(
                bookId = book.id,
                title = book.title,
                isPlaylist = true,
                trackCount = tracks.size,
                currentTrackIndex = startIndex,
                trackTitle = startTrack.title,
                currentPosition = startPosition,
                duration = startTrack.duration,
                tracks = tracks,
                coverUrl = book.coverUrl,
                isInitialLoading = false,
                isBuffering = true,
                error = null,
                playbackError = null,
                introSkipEnabled = book.introSkipEnabled,
                introSkipSeconds = book.introSkipSeconds,
                introSkipHistory = book.introSkipHistory,
                outroSkipEnabled = book.outroSkipEnabled,
                outroSkipSeconds = book.outroSkipSeconds,
                outroSkipHistory = book.outroSkipHistory
            )
        }
    }

    private fun playSingleFile(book: Book) {
        android.util.Log.d("PlayerPerf", "playSingleFile start t=${System.currentTimeMillis()}")
        val startPosition = effectiveStartPosition(book.position.coerceAtLeast(0), book)
        player.setItem(
            PlayableItem(
                url = book.webdavUrl,
                title = book.title,
                artwork = book.coverUrl.takeIf { it.isNotBlank() }
            ),
            startPosition
        )
        player.prepare()
        playAudio()
        playbackState.setCurrentBook(book.id, 1)

        _uiState.update {
            it.copy(
                bookId = book.id,
                title = book.title,
                duration = book.duration,
                currentPosition = startPosition,
                isPlaylist = false,
                trackCount = 1,
                trackTitle = book.title,
                tracks = emptyList(),
                coverUrl = book.coverUrl,
                isInitialLoading = false,
                isBuffering = true,
                error = null,
                playbackError = null,
                introSkipEnabled = book.introSkipEnabled,
                introSkipSeconds = book.introSkipSeconds,
                introSkipHistory = book.introSkipHistory,
                outroSkipEnabled = book.outroSkipEnabled,
                outroSkipSeconds = book.outroSkipSeconds,
                outroSkipHistory = book.outroSkipHistory
            )
        }
    }

    private fun attachListener() {
        if (!listenerAttached) {
            player.addListener(playerListener)
            listenerAttached = true
        }
    }

    private val playerListener = object : AudioPlayer.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveProgress()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
            _uiState.update { it.copy(playWhenReady = playWhenReady) }
        }

        override fun onPlayStateChanged(state: PlayState) {
            when (state) {
                PlayState.READY -> {
                    val duration = validDuration(player.duration, _uiState.value.duration)
                    _uiState.update {
                        it.copy(duration = duration, isInitialLoading = false, isBuffering = false)
                    }
                }
                PlayState.BUFFERING -> _uiState.update { it.copy(isBuffering = true) }
                PlayState.ENDED -> {
                    val current = _uiState.value
                    if (current.isPlaylist) persistTrackProgress(
                        current.currentTrackIndex,
                        current.duration,
                        current.duration
                    ) else saveProgress()
                    // 末曲自然结束时同样按集数递减（无需再调用 pause）
                    if (episodesRemaining > 0) {
                        episodesRemaining--
                        _uiState.update {
                            it.copy(
                                sleepTimerEpisodesRemaining = episodesRemaining.takeIf { r -> r > 0 }
                            )
                        }
                    }
                    _uiState.update {
                        it.copy(isPlaying = false, playWhenReady = false, isBuffering = false)
                    }
                }
                PlayState.IDLE -> _uiState.update { it.copy(isBuffering = false) }
            }
        }

        override fun onItemTransition(newIndex: Int, isAuto: Boolean) {
            val oldState = _uiState.value
            val index = newIndex.coerceAtLeast(0)
            if (!oldState.isPlaylist || index == oldState.currentTrackIndex) return

            if (isAuto) {
                val oldTrack = oldState.tracks.getOrNull(oldState.currentTrackIndex)
                val duration = validDuration(oldTrack?.duration ?: 0, oldState.duration)
                persistTrackProgress(oldState.currentTrackIndex, duration, duration)
                // 按集数定时：自然切集后递减
                if (episodesRemaining > 0) {
                    episodesRemaining--
                    if (episodesRemaining == 0) {
                        _uiState.update { it.copy(sleepTimerEpisodesRemaining = null) }
                        player.pause()
                    } else {
                        _uiState.update { it.copy(sleepTimerEpisodesRemaining = episodesRemaining) }
                    }
                }
            }
            val track = oldState.tracks.getOrNull(index)
            // 自然/手动切集后，若启用片头跳过则把游标推到片头之后
            val introTargetMs = activeBook?.introMs() ?: 0L
            val basePosition = player.currentPosition.coerceAtLeast(0)
            val position = if (introTargetMs > 0 && basePosition < introTargetMs) introTargetMs else basePosition
            if (position != basePosition) {
                player.seekTo(index, position)
            }
            _uiState.update {
                it.copy(
                    currentTrackIndex = index,
                    trackTitle = track?.title.orEmpty(),
                    currentPosition = position,
                    duration = validDuration(player.duration, track?.duration ?: 0),
                    playbackError = null
                )
            }
            viewModelScope.launch { bookRepository.updateCurrentTrack(oldState.bookId, index) }
        }

        override fun onPlaybackError(error: PlaybackError) {
            // 日志脱敏：只打印错误码/HTTP 状态/异常类型，不打印媒体 URL
            Log.e(
                TAG,
                "onPlaybackError bookId=${_uiState.value.bookId} track=${player.currentItemIndex} " +
                    "code=${error.codeName} http=${error.httpStatus ?: "n/a"} " +
                    "cause=${error.causeType ?: "n/a"}"
            )
            _uiState.update {
                it.copy(isBuffering = false, playbackError = "播放失败，请检查网络后重试")
            }
        }
    }

    /**
     * 开始播放并确保后台播放服务（MediaSession / 灵动岛 / 锁屏通知）处于运行态。
     *
     * 音频焦点被其他应用抢占导致本播放器暂停后，MediaSessionService 可能已被系统回收；
     * 若恢复播放时没有重新拉起服务，媒体会话不会重建，表现为灵动岛/锁屏不展示，
     * 且进程失去前台服务保护，几分钟后被系统杀死（再次打开自动续播）。
     * 因此每次真正 play 前都重启一次服务，让 MediaSession 重新绑定到同一个 ExoPlayer 单例。
     */
    private fun playAudio() {
        android.util.Log.d("PlayerPerf", "playAudio() start t=${System.currentTimeMillis()}")
        // 仅在有可用 Context 时（非测试环境）重启后台播放服务。
        // 使用普通 startService 而非 startForegroundService，避免系统 5 秒超时 ANR：
        // Media3 会在播放实际开始时自行调用 startForeground 拉起通知并保持前台保护。
        appContext?.let { ctx ->
            val intent = Intent(ctx, PlaybackService::class.java)
            try {
                ctx.startService(intent)
            } catch (_: IllegalStateException) {
                // Android 12+ 后台启动前台服务时抛出，正常流程不会进入此分支。
            }
        }
        player.play()
    }

    fun togglePlayPause() {
        if (_uiState.value.playbackError != null) {
            _uiState.update { it.copy(playbackError = null, isBuffering = true) }
            recoverFromError()
            return
        }
        when (player.playState) {
            PlayState.IDLE -> {
                player.prepare()
                playAudio()
            }
            PlayState.ENDED -> {
                if (player.itemCount > 1) player.seekTo(player.currentItemIndex, 0) else player.seekTo(0)
                playAudio()
            }
            else -> if (player.isPlaying) player.pause() else playAudio()
        }
    }

    fun retryPlayback() {
        _uiState.update { it.copy(playbackError = null, isBuffering = true) }
        recoverFromError()
    }

    /** 发生错误时重建当前媒体源并恢复到保存进度（日志已脱敏，不泄露媒体 URL）。 */
    private fun recoverFromError() {
        val book = activeBook ?: run {
            _uiState.update { it.copy(isBuffering = false) }
            return
        }
        if (activeTracks.isNotEmpty()) {
            val index = player.currentItemIndex.coerceAtLeast(0)
            val track = activeTracks.getOrNull(index) ?: return
            player.replaceItem(
                index,
                PlayableItem(
                    url = track.webdavUrl,
                    title = track.title,
                    artwork = book.coverUrl.takeIf { it.isNotBlank() }
                )
            )
            player.seekTo(index, resumePosition(track))
        } else {
            player.setItem(
                PlayableItem(
                    url = book.webdavUrl,
                    title = book.title,
                    artwork = book.coverUrl.takeIf { it.isNotBlank() }
                ),
                book.position.coerceAtLeast(0)
            )
        }
        player.prepare()
        playAudio()
    }

    fun seekTo(position: Long) {
        val duration = _uiState.value.duration
        val target = position.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        player.seekTo(target)
        _uiState.update { it.copy(currentPosition = target) }
        saveProgress(target)
    }

    fun seekBy(offsetMs: Long) {
        seekTo(_uiState.value.currentPosition + offsetMs)
    }

    fun nextTrack() {
        if (player.currentItemIndex < player.itemCount - 1) {
            saveProgress()
            player.seekToNextItem()
        }
    }

    fun prevTrack() {
        if (player.currentItemIndex > 0) {
            saveProgress()
            player.seekToPreviousItem()
        }
    }

    fun selectTrack(index: Int) {
        val state = _uiState.value
        val track = state.tracks.getOrNull(index) ?: return
        if (index !in 0 until player.itemCount) return
        saveProgress()
        val target = effectiveStartPosition(resumePosition(track), activeBook)
        player.seekTo(index, target)
        playAudio()
    }

    fun saveProgress(positionOverride: Long? = null) {
        val state = _uiState.value
        if (state.bookId == 0L) return
        val position = positionOverride ?: player.currentPosition.coerceAtLeast(0)
        val duration = validDuration(player.duration, state.duration)
        if (state.isPlaylist) {
            persistTrackProgress(state.currentTrackIndex, position, duration)
        } else {
            viewModelScope.launch { bookRepository.updateProgress(state.bookId, position, duration) }
        }
    }

    fun setSleepTimer(choice: TimerChoice) {
        val bookId = _uiState.value.bookId
        if (bookId == 0L) return
        cancelSleepTimerInternal()
        when (choice) {
            is TimerChoice.Minutes -> startMinuteTimer(choice.minutes)
            is TimerChoice.Episodes -> startEpisodeTimer(choice.count)
        }
        sleepTimerPrefs?.saveForBook(bookId, choice)
        sleepTimerPrefs?.saveGlobal(choice)
        _uiState.update { it.copy(lastTimerChoice = choice) }
    }

    /** 切换上次定时：未启用则恢复，已启用则取消。 */
    fun toggleLastTimer() {
        val last = _uiState.value.lastTimerChoice ?: return
        val state = _uiState.value
        val isLastActive = when (last) {
            is TimerChoice.Minutes -> state.sleepTimerRemaining != null
            is TimerChoice.Episodes -> state.sleepTimerEpisodesRemaining != null
        }
        if (isLastActive) cancelSleepTimer() else setSleepTimer(last)
    }

    fun cancelSleepTimer() {
        cancelSleepTimerInternal()
    }

    private fun cancelSleepTimerInternal() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        episodesRemaining = 0
        _uiState.update {
            it.copy(
                sleepTimerRemaining = null,
                sleepTimerEpisodesRemaining = null
            )
        }
    }

    private fun startMinuteTimer(minutes: Int) {
        _uiState.update { it.copy(sleepTimerRemaining = minutes) }
        sleepTimerJob = viewModelScope.launch {
            var remaining = minutes
            while (remaining > 0) {
                delay(60_000)
                remaining--
                _uiState.update { it.copy(sleepTimerRemaining = remaining) }
            }
            player.pause()
            _uiState.update { it.copy(sleepTimerRemaining = null) }
        }
    }

    private fun startEpisodeTimer(count: Int) {
        require(count >= 1) { "集数必须 >= 1" }
        episodesRemaining = count
        _uiState.update { it.copy(sleepTimerEpisodesRemaining = count) }
    }

    fun scrapeCoverFromDouban(query: String = "") {
        val state = _uiState.value
        if (state.bookId == 0L || state.isCoverUpdating) return
        val repository = coverRepository ?: run {
            _uiState.update { it.copy(coverError = "封面服务不可用") }
            return
        }
        val effectiveQuery = query.trim().ifBlank { state.title }
        _uiState.update { it.copy(isCoverUpdating = true, coverError = null) }
        viewModelScope.launch {
            repository.scrapeFromDouban(state.bookId, effectiveQuery)
                .onSuccess { coverUrl ->
                    activeBook = activeBook?.copy(coverUrl = coverUrl)
                    _uiState.update {
                        it.copy(coverUrl = coverUrl, isCoverUpdating = false, coverError = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCoverUpdating = false,
                            coverError = error.message ?: "封面搜刮失败"
                        )
                    }
                }
        }
    }

    fun importLocalCover(uri: Uri, crop: CoverCrop) {
        val state = _uiState.value
        if (state.bookId == 0L || state.isCoverUpdating) return
        val repository = coverRepository ?: run {
            _uiState.update { it.copy(coverError = "封面服务不可用") }
            return
        }
        _uiState.update { it.copy(isCoverUpdating = true, coverError = null) }
        viewModelScope.launch {
            repository.importLocalCover(state.bookId, uri, crop)
                .onSuccess { coverUrl ->
                    activeBook = activeBook?.copy(coverUrl = coverUrl)
                    _uiState.update {
                        it.copy(coverUrl = coverUrl, isCoverUpdating = false, coverError = null)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isCoverUpdating = false,
                            coverError = error.message ?: "封面导入失败"
                        )
                    }
                }
        }
    }

    /**
     * 保存「跳过头尾」设置并立即生效。
     * - 持久化到数据库（合并历史时长）；
     * - 若当前播放时间处于 [0, introMs) 且启用 intro，则直接跳到 introMs。
     */
    fun applySkipSettings(
        introEnabled: Boolean,
        introSeconds: Int,
        outroEnabled: Boolean,
        outroSeconds: Int
    ) = applySkipSettingsInternal(introEnabled, introSeconds, outroEnabled, outroSeconds, persist = true)

    /**
     * 仅在内存中应用「跳过头尾」值（用于跳过弹窗的滑杆拖动等高频调整场景），
     * 不写数据库、不合并历史、不触发「片头跳到 introMs」之类的副作用。
     * 提交用 [applySkipSettings]。
     */
    fun previewSkipSettings(
        introEnabled: Boolean,
        introSeconds: Int,
        outroEnabled: Boolean,
        outroSeconds: Int
    ) = applySkipSettingsInternal(introEnabled, introSeconds, outroEnabled, outroSeconds, persist = false)

    private fun applySkipSettingsInternal(
        introEnabled: Boolean,
        introSeconds: Int,
        outroEnabled: Boolean,
        outroSeconds: Int,
        persist: Boolean
    ) {
        val state = _uiState.value
        if (state.bookId == 0L) return
        val introSec = introSeconds.coerceIn(0, MAX_SKIP_SECONDS)
        val outroSec = outroSeconds.coerceIn(0, MAX_SKIP_SECONDS)
        // 总是先把当前选择同步到 UI / activeBook，保证 activeBook 与 PersistedState 一致
        _uiState.update {
            it.copy(
                introSkipEnabled = introEnabled,
                introSkipSeconds = introSec,
                outroSkipEnabled = outroEnabled,
                outroSkipSeconds = outroSec
            )
        }
        activeBook = activeBook?.copy(
            introSkipEnabled = introEnabled,
            introSkipSeconds = introSec,
            outroSkipEnabled = outroEnabled,
            outroSkipSeconds = outroSec
        )
        if (!persist) return

        viewModelScope.launch {
            bookRepository.updateSkipSettings(state.bookId, introEnabled, introSec, outroEnabled, outroSec)
            // 重新读取以拿到合并后的历史（Repository 内部做了去重与截断）
            bookRepository.getBookById(state.bookId)?.let { updated ->
                activeBook = updated
                _uiState.update {
                    it.copy(
                        introSkipHistory = updated.introSkipHistory,
                        outroSkipHistory = updated.outroSkipHistory
                    )
                }
            }
        }
        // 保存时立即生效：当前时间处于片头区间内则一次性跳到片头
        if (introEnabled && introSec > 0) {
            val introMs = introSec * 1000L
            val pos = player.currentPosition.coerceAtLeast(0)
            if (pos in 0 until introMs) {
                player.seekTo(introMs)
                _uiState.update { it.copy(currentPosition = introMs) }
                saveProgress(introMs)
            }
        }
    }

    private fun persistTrackProgress(index: Int, position: Long, duration: Long) {
        val state = _uiState.value
        if (index !in state.tracks.indices) return
        val safeDuration = duration.coerceAtLeast(0)
        val safePosition = position.coerceIn(0, if (safeDuration > 0) safeDuration else Long.MAX_VALUE)
        _uiState.update {
            it.copy(tracks = updateTrackProgress(it.tracks, index, safePosition, safeDuration))
        }
        viewModelScope.launch {
            bookRepository.saveTrackProgress(state.bookId, index, safePosition, safeDuration)
            bookRepository.updateCurrentTrack(state.bookId, _uiState.value.currentTrackIndex)
        }
    }

    private fun startJobs(bookId: Long) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            while (true) {
                delay(PROGRESS_SAVE_INTERVAL_MS)
                if (_uiState.value.bookId == bookId) saveProgress()
            }
        }
        positionJob?.cancel()
        positionJob = viewModelScope.launch {
            while (true) {
                delay(POSITION_UPDATE_INTERVAL_MS)
                val state = _uiState.value
                val position = player.currentPosition.coerceAtLeast(0)
                val duration = validDuration(player.duration, state.duration)
                _uiState.update {
                    it.copy(
                        currentPosition = position,
                        duration = duration,
                        tracks = if (it.isPlaylist) {
                            updateTrackProgress(it.tracks, it.currentTrackIndex, position, duration)
                        } else it.tracks
                    )
                }
                // 片尾跳过：到达末尾前 outroMs 主动暂停。pause() 会让 playWhenReady=false，
                // 下一次循环不会重复触发；用户手动继续播放若仍在区间内会再被拦下（符合预期）
                if (state.outroSkipEnabled && state.outroSkipSeconds > 0 && duration > 0
                    && player.playWhenReady && position >= duration - state.outroSkipSeconds * 1000L
                    && position > 0
                ) {
                    player.pause()
                }
            }
        }
    }

    /** 保存进度并停掉所有后台任务；onCleared 调用，测试也可直接调用以结束轮询协程。 */
    internal fun release() {
        saveProgress()
        initializeJob?.cancel()
        progressSaveJob?.cancel()
        positionJob?.cancel()
        sleepTimerJob?.cancel()
        if (listenerAttached) {
            player.removeListener(playerListener)
            listenerAttached = false
        }
    }

    override fun onCleared() {
        release()
        super.onCleared()
    }

    private companion object {
        const val TAG = "PlayerViewModel"
        const val PROGRESS_SAVE_INTERVAL_MS = 15_000L
        const val POSITION_UPDATE_INTERVAL_MS = 500L
        /** 片头/片尾跳过的最大秒数（与 UI 滑杆上限一致）。 */
        const val MAX_SKIP_SECONDS = 180
    }
}

internal const val TRACK_COMPLETION_THRESHOLD = 0.95

internal fun isTrackCompleted(track: Track): Boolean =
    track.duration > 0 && track.position.toDouble() / track.duration >= TRACK_COMPLETION_THRESHOLD

internal fun trackProgressPercent(track: Track): Int = when {
    track.duration <= 0 -> 0
    else -> ((track.position.coerceIn(0, track.duration) * 100) / track.duration).toInt()
}

internal fun resumePosition(track: Track): Long =
    if (isTrackCompleted(track)) 0 else track.position.coerceAtLeast(0)

private fun validDuration(playerDuration: Long, fallback: Long): Long =
    playerDuration.takeIf { it > 0 } ?: fallback.coerceAtLeast(0)

private fun updateTrackProgress(
    tracks: List<Track>,
    index: Int,
    position: Long,
    duration: Long
): List<Track> = tracks.mapIndexed { trackIndex, track ->
    if (trackIndex == index) track.copy(position = position, duration = duration) else track
}

/**
 * 切集 / 首次播放时，根据「跳片头」设置决定实际起始位置：
 * 若启用且「自然起始位置」< introMs，则直接落到 introMs；否则按原值起播。
 */
private fun effectiveStartPosition(resumeMs: Long, book: Book?): Long {
    val introMs = book?.introMs() ?: 0L
    if (introMs <= 0) return resumeMs
    return if (resumeMs < introMs) introMs else resumeMs
}

private fun Book.introMs(): Long =
    if (introSkipEnabled && introSkipSeconds > 0) introSkipSeconds * 1000L else 0L

private fun Track.toPlayableItem(book: Book): PlayableItem = PlayableItem(
    url = webdavUrl,
    title = title,
    artwork = book.coverUrl.takeIf { it.isNotBlank() }
)
