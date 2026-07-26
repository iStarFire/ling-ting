package com.tingyiting.playback

/** 播放条目：仅含播放所需的 URL 与标题，与 Media3 类型解耦。
 * [artwork] 为封面地址（本地 file:// 或 http(s)），用于状态栏/锁屏媒体通知展示封面。 */
data class PlayableItem(
    val url: String,
    val title: String,
    val artwork: String? = null
)

/** 播放器状态，对应 Media3 的 Player.STATE_*。 */
enum class PlayState { IDLE, BUFFERING, READY, ENDED }

/** 脱敏后的播放错误：只保留排查所需字段，不含媒体 URL。 */
data class PlaybackError(
    val codeName: String,
    val httpStatus: Int? = null,
    val causeType: String? = null
)

/**
 * 播放页所需的最小播放器接口。
 * 生产实现为 [ExoAudioPlayer]；单元测试用手写 Fake 替代，
 * 避免 mock 巨型 ExoPlayer 接口带来的开销与不稳定。
 */
interface AudioPlayer {
    val isPlaying: Boolean
    val playWhenReady: Boolean
    val playState: PlayState
    val currentPosition: Long

    /** 未知时长时返回 <= 0（如 Media3 的 C.TIME_UNSET）。 */
    val duration: Long
    val itemCount: Int
    val currentItemIndex: Int

    fun setPlaylist(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long)
    fun setItem(item: PlayableItem, startPositionMs: Long)

    /** 原位替换指定条目（用于错误恢复时重建媒体源）。 */
    fun replaceItem(index: Int, item: PlayableItem)
    fun prepare()
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun seekTo(itemIndex: Int, positionMs: Long)
    fun seekToNextItem()
    fun seekToPreviousItem()
    fun addListener(listener: Listener)
    fun removeListener(listener: Listener)

    interface Listener {
        fun onIsPlayingChanged(isPlaying: Boolean) {}
        fun onPlayWhenReadyChanged(playWhenReady: Boolean) {}
        fun onPlayStateChanged(state: PlayState) {}

        /** [newIndex] 为切换后的条目索引；[isAuto] 表示上一条自然播完自动切换。 */
        fun onItemTransition(newIndex: Int, isAuto: Boolean) {}
        fun onPlaybackError(error: PlaybackError) {}
    }
}
