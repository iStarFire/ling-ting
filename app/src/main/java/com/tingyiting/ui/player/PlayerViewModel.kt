package com.tingyiting.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.tingyiting.data.model.Book
import com.tingyiting.data.model.Track
import com.tingyiting.data.repository.BookRepository
import com.tingyiting.data.repository.WebDavRepository
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
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    val sleepTimerRemaining: Int? = null,
    // 多集（有声剧）相关
    val isPlaylist: Boolean = false,
    val currentTrackIndex: Int = 0,
    val trackCount: Int = 0,
    val trackTitle: String = "",
    val tracks: List<Track> = emptyList()
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val bookRepository: BookRepository,
    private val webDavRepository: WebDavRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState = _uiState.asStateFlow()

    private var progressSaveJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var player: ExoPlayer? = null

    fun initialize(player: ExoPlayer, bookId: Long) {
        this.player = player

        viewModelScope.launch {
            val book = bookRepository.getBookById(bookId)
            if (book == null) {
                _uiState.update { it.copy(error = "书籍未找到", isLoading = false) }
                return@launch
            }

            val tracks = bookRepository.getTracks(bookId)

            if (tracks.isEmpty()) {
                // 旧单文件书籍：回退到原有单 URL 逻辑
                playSingleFile(player, book)
                return@launch
            }

            // 多集有声剧：以播放列表承载（鉴权已在 ExoPlayer 的 MediaSourceFactory 中处理）
            val startIndex = book.currentTrackIndex.coerceIn(0, tracks.lastIndex)
            val startPosition = tracks[startIndex].position.coerceAtLeast(0)

            val mediaItems = tracks.map { track ->
                MediaItem.Builder()
                    .setUri(track.webdavUrl)
                    .setMediaMetadata(
                        MediaMetadata.Builder().setTitle(track.title).build()
                    )
                    .build()
            }

            player.setMediaItems(mediaItems, startIndex, startPosition)
            player.addListener(playlistListener)
            player.prepare()
            player.play()

            _uiState.update {
                it.copy(
                    bookId = book.id,
                    title = book.title,
                    isPlaylist = true,
                    trackCount = tracks.size,
                    currentTrackIndex = startIndex,
                    trackTitle = tracks[startIndex].title,
                    tracks = tracks,
                    isLoading = false
                )
            }

            startProgressSaver(book.id)
            startPositionLoop()
        }
    }

    private fun playSingleFile(
        player: ExoPlayer,
        book: Book
    ) {
        _uiState.update {
            it.copy(
                bookId = book.id,
                title = book.title,
                duration = book.duration,
                isPlaylist = false,
                isLoading = false
            )
        }

        val mediaItem = MediaItem.Builder()
            .setUri(book.webdavUrl)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(book.title).build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.seekTo(book.position)
        player.prepare()
        player.play()

        player.addListener(singleFileListener)

        startProgressSaver(book.id)
        startPositionLoop()
    }

    private val singleFileListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressSaver(_uiState.value.bookId)
            else stopProgressSaver()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> _uiState.update {
                    it.copy(duration = player?.duration?.coerceAtLeast(0) ?: 0, isLoading = false)
                }
                Player.STATE_BUFFERING -> _uiState.update { it.copy(isLoading = true) }
                Player.STATE_ENDED -> _uiState.update { it.copy(isPlaying = false) }
            }
        }
    }

    private val playlistListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _uiState.update { it.copy(isPlaying = isPlaying) }
            if (isPlaying) startProgressSaver(_uiState.value.bookId)
            else stopProgressSaver()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> _uiState.update {
                    it.copy(duration = player?.duration?.coerceAtLeast(0) ?: 0, isLoading = false)
                }
                Player.STATE_BUFFERING -> _uiState.update { it.copy(isLoading = true) }
                Player.STATE_ENDED -> _uiState.update { it.copy(isPlaying = false) }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val prevIndex = _uiState.value.currentTrackIndex
            val newIndex = player?.currentMediaItemIndex ?: prevIndex
            if (newIndex != prevIndex) {
                // 保存上一集进度
                saveCurrentTrackProgress(prevIndex)
                _uiState.value.bookId.let { bookId ->
                    val title = _uiState.value.tracks.getOrNull(newIndex)?.title ?: ""
                    _uiState.update {
                        it.copy(currentTrackIndex = newIndex, trackTitle = title, currentPosition = 0)
                    }
                    viewModelScope.launch { bookRepository.updateCurrentTrack(bookId, newIndex) }
                }
            }
        }
    }

    fun togglePlayPause() {
        player?.let { if (it.isPlaying) it.pause() else it.play() }
    }

    fun seekTo(position: Long) {
        player?.seekTo(position)
        val state = _uiState.value
        viewModelScope.launch {
            if (state.isPlaylist) {
                bookRepository.saveTrackProgress(state.bookId, state.currentTrackIndex, position, state.duration)
            } else {
                bookRepository.updateProgress(state.bookId, position, state.duration)
            }
        }
    }

    fun nextTrack() {
        player?.let { if (it.currentMediaItemIndex < it.mediaItemCount - 1) it.seekToNextMediaItem() }
    }

    fun prevTrack() {
        player?.let { if (it.currentMediaItemIndex > 0) it.seekToPreviousMediaItem() }
    }

    fun selectTrack(index: Int) {
        player?.let { if (index in 0 until it.mediaItemCount) it.seekTo(index, 0) }
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
            player?.pause()
            _uiState.update { it.copy(sleepTimerRemaining = null) }
        }
    }

    fun cancelSleepTimer() {
        sleepTimerJob?.cancel()
        _uiState.update { it.copy(sleepTimerRemaining = null) }
    }

    private fun saveCurrentTrackProgress(index: Int) {
        val state = _uiState.value
        val pos = player?.currentPosition?.coerceAtLeast(0) ?: 0
        val dur = player?.duration?.coerceAtLeast(0) ?: state.duration
        viewModelScope.launch {
            bookRepository.saveTrackProgress(state.bookId, index, pos, dur)
        }
    }

    private fun startProgressSaver(bookId: Long) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                val state = _uiState.value
                val pos = player?.currentPosition?.coerceAtLeast(0) ?: state.currentPosition
                val dur = player?.duration?.coerceAtLeast(0) ?: state.duration
                if (state.isPlaylist) {
                    bookRepository.saveTrackProgress(bookId, state.currentTrackIndex, pos, dur)
                    bookRepository.updateCurrentTrack(bookId, state.currentTrackIndex)
                } else {
                    bookRepository.updateProgress(bookId, pos, dur)
                }
            }
        }
    }

    private fun stopProgressSaver() {
        val state = _uiState.value
        if (state.isPlaylist) saveCurrentTrackProgress(state.currentTrackIndex)
        else viewModelScope.launch {
            bookRepository.updateProgress(state.bookId, state.currentPosition, state.duration)
        }
        progressSaveJob?.cancel()
    }

    private fun startPositionLoop() {
        viewModelScope.launch {
            while (true) {
                delay(500)
                _uiState.update {
                    it.copy(currentPosition = player?.currentPosition?.coerceAtLeast(0) ?: it.currentPosition)
                }
            }
        }
    }

    override fun onCleared() {
        progressSaveJob?.cancel()
        sleepTimerJob?.cancel()
        player?.removeListener(singleFileListener)
        player?.removeListener(playlistListener)
        super.onCleared()
    }
}
