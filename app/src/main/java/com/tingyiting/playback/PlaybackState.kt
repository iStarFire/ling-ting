package com.tingyiting.playback

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 全局播放状态。与单例 ExoPlayer 同生命周期，
 * 在返回书架后仍持续暴露当前播放书籍的实时进度。
 */
interface PlaybackState {
    val info: StateFlow<PlaybackInfo?>

    /** 播放器加载某本书完成后调用；bookId=0 表示清空。 */
    fun setCurrentBook(bookId: Long, trackCount: Int)

    fun clear()
}

/**
 * 基于单例 ExoPlayer 的实现：以 500ms 周期在主线程轮询播放器状态。
 * 仅在 setCurrentBook 设置了有效 bookId 后才轮询。
 */
class ExoPlaybackState(
    private val player: ExoPlayer
) : PlaybackState {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _info = MutableStateFlow<PlaybackInfo?>(null)
    override val info: StateFlow<PlaybackInfo?> = _info.asStateFlow()

    private var pollJob: Job? = null
    private var bookId: Long = 0L
    private var trackCount: Int = 0

    override fun setCurrentBook(bookId: Long, trackCount: Int) {
        this.bookId = bookId
        this.trackCount = trackCount
        pollJob?.cancel()
        if (bookId == 0L) {
            _info.value = null
            return
        }
        pollJob = scope.launch {
            while (isActive) {
                emitInfo()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    override fun clear() {
        pollJob?.cancel()
        bookId = 0L
        trackCount = 0
        _info.value = null
    }

    private fun emitInfo() {
        // duration 可能为 C.TIME_UNSET(负值)，钳制为 0 避免负数进度
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val position = player.currentPosition.coerceAtLeast(0)
        _info.value = PlaybackInfo(
            bookId = bookId,
            isPlaying = player.isPlaying,
            currentPosition = position,
            duration = duration,
            currentTrackIndex = player.currentMediaItemIndex,
            trackTitle = player.mediaMetadata.title?.toString() ?: "",
            trackCount = trackCount
        )
    }

    private companion object {
        const val POLL_INTERVAL_MS = 500L
    }
}
