package com.tingyiting.di

import android.content.Context
import androidx.media3.common.C
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tingyiting.data.repository.WebDavRepository
import com.tingyiting.network.DynamicAuthDataSourceFactory
import com.tingyiting.playback.AudioPlayer
import com.tingyiting.playback.ExoAudioPlayer
import com.tingyiting.playback.ExoPlaybackState
import com.tingyiting.playback.PlaybackState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideExoPlayer(
        @ApplicationContext context: Context,
        webDavRepository: WebDavRepository
    ): ExoPlayer {
        // http(s) 走带 WebDAV 鉴权的 OkHttp 工厂；content:// 由 DefaultDataSource 自动路由到 ContentDataSource
        val dataSourceFactory = DefaultDataSource.Factory(
            context,
            DynamicAuthDataSourceFactory(webDavRepository)
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
            .setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(3))
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
    }

    @Provides
    @Singleton
    fun provideAudioPlayer(@ApplicationContext context: Context, player: ExoPlayer): AudioPlayer =
        ExoAudioPlayer(player, context)

    @Provides
    @Singleton
    fun providePlaybackState(player: ExoPlayer): PlaybackState = ExoPlaybackState(player)
}
