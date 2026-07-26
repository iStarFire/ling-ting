package com.tingyiting.di

import android.content.Context
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.tingyiting.data.repository.WebDavRepository
import com.tingyiting.network.DynamicAuthDataSourceFactory
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
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build()
    }
}
