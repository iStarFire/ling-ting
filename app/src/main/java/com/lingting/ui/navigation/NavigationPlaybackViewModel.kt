package com.lingting.ui.navigation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lingting.data.model.Book
import com.lingting.data.repository.BookRepository
import com.lingting.playback.AudioPlayer
import com.lingting.playback.PlaybackInfo
import com.lingting.playback.PlaybackState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.stateIn
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

    /**
     * 最近一次播放过的专辑。本次启动内若尚无播放，底部播放按钮用它展示封面。
     * Eagerly 保证 app 一启动就拿到首帧，避开底部播放条空白闪烁。
     */
    val lastPlayedBook: StateFlow<Book?> = bookRepository.getLastPlayedBook()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

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