package com.tingyiting.service

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.tingyiting.MainActivity
import com.tingyiting.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    companion object {
        private const val CHANNEL_ID = "playback_channel"
    }

    private var mediaSession: MediaSession? = null

    @Inject
    lateinit var player: ExoPlayer

    override fun onCreate() {
        // 配置媒体通知提供器：返回桌面后展示状态栏（灵动岛）媒体胶囊，并在锁屏展示媒体控件。
        // 需在 super.onCreate() 前设置，使 MediaSessionService 在通知更新时使用自定义通道与图标。
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
        provider.setSmallIcon(R.drawable.media3_notification_small_icon)
        setMediaNotificationProvider(provider)
        super.onCreate()
        initializePlayerAndSession()
    }

    private fun initializePlayerAndSession() {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.playWhenReady == true && player.mediaItemCount > 0) {
            // 有播放内容，不停止服务
            return
        }
        stopSelf()
    }

    override fun onDestroy() {
        // ExoPlayer 是 Hilt Singleton，生命周期跟随应用进程。
        // MediaSessionService 可能在休眠/后台后被系统销毁；如果这里 release 播放器，
        // 前台 Activity/ViewModel 仍会持有同一个已释放实例，后续 play() 会发消息到 dead thread。
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}
