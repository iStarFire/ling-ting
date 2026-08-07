package com.lingting.playback

/**
 * 全局播放快照：由 [PlaybackState] 周期性刷新，
 * 供书架等非播放页在后台播放期间展示实时进度。
 */
data class PlaybackInfo(
    val bookId: Long,
    val isPlaying: Boolean,
    val currentPosition: Long,
    val duration: Long,
    /** 0-based 当前集索引 */
    val currentTrackIndex: Int,
    val trackTitle: String,
    val trackCount: Int
)
