package com.tingyiting.service

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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

        /** 后台非播放状态闲置超过此时间后主动停止服务，避免长时后台耗电。 */
        private const val IDLE_TIMEOUT_MS = 30 * 60 * 1000L // 30 分钟
    }

    private var mediaSession: MediaSession? = null

    @Inject
    lateinit var player: ExoPlayer

    private val idleHandler = Handler(Looper.getMainLooper())
    private val idleTimeoutRunnable = Runnable { stopSelf() }
    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                idleHandler.removeCallbacks(idleTimeoutRunnable)
            } else {
                idleHandler.removeCallbacks(idleTimeoutRunnable)
                idleHandler.postDelayed(idleTimeoutRunnable, IDLE_TIMEOUT_MS)
            }
        }
    }

    override fun onCreate() {
        android.util.Log.d("PlayerPerf", "PlaybackService.onCreate t=${System.currentTimeMillis()}")
        val provider = DefaultMediaNotificationProvider.Builder(this)
            .setChannelId(CHANNEL_ID)
            .setChannelName(R.string.notification_channel_name)
            .build()
        provider.setSmallIcon(R.drawable.media3_notification_small_icon)
        setMediaNotificationProvider(provider)
        super.onCreate()
        setupPlayer()
        initializePlayerAndSession()
        player.addListener(playerListener)
        if (!player.isPlaying) {
            idleHandler.postDelayed(idleTimeoutRunnable, IDLE_TIMEOUT_MS)
        }
    }

    /** 仅在服务首次创建时执行一次：配置音频属性。 */
    private fun setupPlayer() {
        player.setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
    }

    /**
     * 创建 MediaSession（仅一次）。
     *
     * 不在每次 onStartCommand 中重建：MediaSession 创建涉及与系统 MediaSessionManager 的 IPC 注册，
     * 频繁 release+build 会导致主线程卡顿（首次进入播放页明显）。
     * 通知/锁屏控件由 Media3 根据 player 状态自动更新，无需手动刷新会话。
     */
    private fun initializePlayerAndSession() {
        if (mediaSession != null) return
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(pendingIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("PlayerPerf", "PlaybackService.onStartCommand t=${System.currentTimeMillis()}")
        val result = super.onStartCommand(intent, flags, startId)
        if (result == START_NOT_STICKY) {
            return START_STICKY
        }
        return result
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.playWhenReady == true && player.mediaItemCount > 0) {
            return
        }
        stopSelf()
    }

    override fun onDestroy() {
        idleHandler.removeCallbacks(idleTimeoutRunnable)
        player.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}