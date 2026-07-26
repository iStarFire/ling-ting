package com.tingyiting.ui.player

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyiting.data.model.Book
import com.tingyiting.data.model.Track
import com.tingyiting.data.repository.BookRepository
import com.tingyiting.playback.AudioPlayer
import com.tingyiting.playback.PlayState
import com.tingyiting.playback.PlayableItem
import com.tingyiting.playback.PlaybackError
import com.tingyiting.playback.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val isPlaylist: Boolean = false,
    val currentTrackIndex: Int = 0,
    val trackCount: Int = 0,
    val trackTitle: String = "",
    val tracks: List<Track> = emptyList()
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val player: AudioPlayer,
    private val playbackState: PlaybackState
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

    fun initialize(bookId: Long) {
        if (bookId == 0L || (initializeJob?.isActive == true && _uiState.value.bookId == bookId)) return
        initializeJob?.cancel()
        _uiState.update { it.copy(bookId = bookId, isInitialLoading = true, error = null) }

        initializeJob = viewModelScope.launch {
            try {
                val book = bookRepository.getBookById(bookId)
                if (book == null) {
                    _uiState.update { it.copy(error = "书籍未找到", isInitialLoading = false) }
                    return@launch
                }
                val tracks = bookRepository.getTracks(bookId)
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
        _uiState.value = PlayerUiState(
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
            tracks = updateTrackProgress(tracks, index, position, duration)
        )
    }

    private fun buildPlaylist(book: Book, tracks: List<Track>) {
        val startIndex = book.currentTrackIndex.coerceIn(0, tracks.lastIndex)
        val startTrack = tracks[startIndex]
        val startPosition = resumePosition(startTrack)
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
                isInitialLoading = false,
                isBuffering = true,
                error = null,
                playbackError = null
            )
        }
        val items = tracks.map { PlayableItem(url = it.webdavUrl, title = it.title) }
        player.setPlaylist(items, startIndex, startPosition)
        player.prepare()
        player.play()
        playbackState.setCurrentBook(book.id, tracks.size)
    }

    private fun playSingleFile(book: Book) {
        _uiState.update {
            it.copy(
                bookId = book.id,
                title = book.title,
                duration = book.duration,
                currentPosition = book.position,
                isPlaylist = false,
                trackCount = 1,
                trackTitle = book.title,
                tracks = emptyList(),
                isInitialLoading = false,
                isBuffering = true,
                error = null,
                playbackError = null
            )
        }
        player.setItem(
            PlayableItem(url = book.webdavUrl, title = book.title),
            book.position.coerceAtLeast(0)
        )
        player.prepare()
        player.play()
        playbackState.setCurrentBook(book.id, 1)
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
            }
            val track = oldState.tracks.getOrNull(index)
            val position = player.currentPosition.coerceAtLeast(0)
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

    fun togglePlayPause() {
        if (_uiState.value.playbackError != null) {
            _uiState.update { it.copy(playbackError = null, isBuffering = true) }
            recoverFromError()
            return
        }
        when (player.playState) {
            PlayState.IDLE -> {
                player.prepare()
                player.play()
            }
            PlayState.ENDED -> {
                if (player.itemCount > 1) player.seekTo(player.currentItemIndex, 0) else player.seekTo(0)
                player.play()
            }
            else -> if (player.playWhenReady) player.pause() else player.play()
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
            player.replaceItem(index, PlayableItem(url = track.webdavUrl, title = track.title))
            player.seekTo(index, resumePosition(track))
        } else {
            player.setItem(
                PlayableItem(url = book.webdavUrl, title = book.title),
                book.position.coerceAtLeast(0)
            )
        }
        player.prepare()
        player.play()
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
        player.seekTo(index, resumePosition(track))
        player.play()
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

    fun setSleepTimer(minutes: Int) {
        _uiState.update { it.copy(sleepTimerRemaining = minutes) }
        sleepTimerJob?.cancel()
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

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(sleepTimerRemaining = null) }
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
