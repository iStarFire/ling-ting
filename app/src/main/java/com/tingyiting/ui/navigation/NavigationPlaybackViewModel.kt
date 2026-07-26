package com.tingyiting.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tingyiting.data.repository.BookRepository
import com.tingyiting.playback.AudioPlayer
import com.tingyiting.playback.PlaybackInfo
import com.tingyiting.playback.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NavigationPlaybackViewModel @Inject constructor(
    playbackState: PlaybackState,
    private val audioPlayer: AudioPlayer,
    private val bookRepository: BookRepository
) : ViewModel() {
    val playbackInfo: StateFlow<PlaybackInfo?> = playbackState.info

    /** 当前播放书籍的封面 URL（用于底部导航的圆形封面展示）。 */
    private val _coverUrl = MutableStateFlow<String?>(null)
    val coverUrl: StateFlow<String?> = _coverUrl.asStateFlow()

    init {
        viewModelScope.launch {
            playbackInfo.collectLatest { info ->
                _coverUrl.value = info?.let { bookRepository.getBookById(it.bookId)?.coverUrl }
            }
        }
    }

    /** 当前播放处于暂停状态时恢复播放，供底部导航按钮点击使用。 */
    fun resumeIfPaused() {
        val info = playbackInfo.value ?: return
        if (!info.isPlaying) {
            audioPlayer.play()
        }
    }
}