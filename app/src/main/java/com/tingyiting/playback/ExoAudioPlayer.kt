package com.tingyiting.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer

/** 将 Media3 ExoPlayer 适配为窄接口 [AudioPlayer]，Media3 类型不外泄。 */
class ExoAudioPlayer(private val player: ExoPlayer) : AudioPlayer {

    private val delegates = mutableMapOf<AudioPlayer.Listener, Player.Listener>()

    override val isPlaying: Boolean get() = player.isPlaying
    override val playWhenReady: Boolean get() = player.playWhenReady
    override val playState: PlayState get() = player.playbackState.toPlayState()
    override val currentPosition: Long get() = player.currentPosition
    override val duration: Long get() = player.duration
    override val itemCount: Int get() = player.mediaItemCount
    override val currentItemIndex: Int get() = player.currentMediaItemIndex

    override fun setPlaylist(items: List<PlayableItem>, startIndex: Int, startPositionMs: Long) {
        player.setMediaItems(items.map { it.toMediaItem() }, startIndex, startPositionMs)
    }

    override fun setItem(item: PlayableItem, startPositionMs: Long) {
        player.setMediaItem(item.toMediaItem(), startPositionMs)
    }

    override fun replaceItem(index: Int, item: PlayableItem) {
        player.removeMediaItem(index)
        player.addMediaItem(index, item.toMediaItem())
    }

    override fun prepare() = player.prepare()
    override fun play() = player.play()
    override fun pause() = player.pause()
    override fun seekTo(positionMs: Long) = player.seekTo(positionMs)
    override fun seekTo(itemIndex: Int, positionMs: Long) = player.seekTo(itemIndex, positionMs)
    override fun seekToNextItem() = player.seekToNextMediaItem()
    override fun seekToPreviousItem() = player.seekToPreviousMediaItem()

    override fun addListener(listener: AudioPlayer.Listener) {
        val delegate = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) =
                listener.onIsPlayingChanged(isPlaying)

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) =
                listener.onPlayWhenReadyChanged(playWhenReady)

            override fun onPlaybackStateChanged(playbackState: Int) =
                listener.onPlayStateChanged(playbackState.toPlayState())

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) =
                listener.onItemTransition(
                    player.currentMediaItemIndex,
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO
                )

            override fun onPlayerError(error: PlaybackException) =
                listener.onPlaybackError(error.toPlaybackError())
        }
        delegates[listener] = delegate
        player.addListener(delegate)
    }

    override fun removeListener(listener: AudioPlayer.Listener) {
        delegates.remove(listener)?.let(player::removeListener)
    }
}

private fun Int.toPlayState(): PlayState = when (this) {
    Player.STATE_BUFFERING -> PlayState.BUFFERING
    Player.STATE_READY -> PlayState.READY
    Player.STATE_ENDED -> PlayState.ENDED
    else -> PlayState.IDLE
}

private fun PlayableItem.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(url)
    .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
    .build()

private fun PlaybackException.toPlaybackError(): PlaybackError = PlaybackError(
    codeName = errorCodeName,
    httpStatus = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
    causeType = cause?.javaClass?.simpleName
)
