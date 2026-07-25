package com.tingyiting.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.ExoPlayer
import com.tingyiting.data.repository.BookRepository
import com.tingyiting.data.repository.WebDavRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject

data class PlayerUiState(
    val bookId: Long = 0,
    val title: String = "",
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val isLoading: Boolean = true,
    val error: String? = null,
    val sleepTimerRemaining: Int? = null // 剩余分钟
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

            _uiState.update {
                it.copy(
                    bookId = book.id,
                    title = book.title,
                    duration = book.duration,
                    isLoading = false
                )
            }

            // 构建 MediaItem，注入 WebDAV 鉴权
            val authHeader = webDavRepository.getAuthHeader()
            val dataSourceFactory = AuthDataSourceFactory(authHeader)

            val mediaItem = MediaItem.Builder()
                .setUri(book.webdavUrl)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(book.title)
                        .build()
                )
                .build()

            player.apply {
                setMediaSource(DefaultMediaSourceFactory(dataSourceFactory).createMediaSource(mediaItem))
                seekTo(book.position)
                prepare()
                play()
            }

            // 添加播放状态监听
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _uiState.update { it.copy(isPlaying = isPlaying) }
                    if (isPlaying) {
                        startProgressSaver(book.id)
                    } else {
                        stopProgressSaver()
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            _uiState.update {
                                it.copy(
                                    duration = player.duration.coerceAtLeast(0),
                                    isLoading = false
                                )
                            }
                        }
                        Player.STATE_BUFFERING -> {
                            _uiState.update { it.copy(isLoading = true) }
                        }
                        Player.STATE_ENDED -> {
                            _uiState.update { it.copy(isPlaying = false) }
                        }
                    }
                }
            })

            // 启动定期位置更新
            viewModelScope.launch {
                while (true) {
                    delay(500)
                    _uiState.update {
                        it.copy(currentPosition = player.currentPosition.coerceAtLeast(0))
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    fun seekTo(position: Long) {
        player?.seekTo(position)
        // 手动拖动进度后立即保存
        val state = _uiState.value
        viewModelScope.launch {
            bookRepository.updateProgress(state.bookId, position, state.duration)
        }
    }

    fun setSleepTimer(minutes: Int) {
        _uiState.update { it.copy(sleepTimerRemaining = minutes) }
        sleepTimerJob?.cancel()
        sleepTimerJob = viewModelScope.launch {
            var remaining = minutes
            while (remaining > 0) {
                delay(60_000) // 每分钟减 1
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

    private fun startProgressSaver(bookId: Long) {
        progressSaveJob?.cancel()
        progressSaveJob = viewModelScope.launch {
            while (true) {
                delay(15_000)
                val state = _uiState.value
                val pos = player?.currentPosition?.coerceAtLeast(0) ?: state.currentPosition
                val dur = player?.duration?.coerceAtLeast(0) ?: state.duration
                bookRepository.updateProgress(bookId, pos, dur)
            }
        }
    }

    private fun stopProgressSaver() {
        // 暂停时立即保存一次
        val state = _uiState.value
        viewModelScope.launch {
            bookRepository.updateProgress(state.bookId, state.currentPosition, state.duration)
        }
        progressSaveJob?.cancel()
    }

    override fun onCleared() {
        progressSaveJob?.cancel()
        sleepTimerJob?.cancel()
        super.onCleared()
    }
}
