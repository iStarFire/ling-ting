package com.tingyiting.network

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.tingyiting.data.repository.WebDavRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * 每次创建 DataSource 时动态读取当前 WebDAV 鉴权头，
 * 这样 ExoPlayer 在任意时刻（含播放列表多集）都能自动带上鉴权。
 */
class DynamicAuthDataSourceFactory(
    private val webDavRepository: WebDavRepository
) : DataSource.Factory {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun createDataSource(): DataSource {
        val header = runCatching { webDavRepository.getAuthHeader() }.getOrDefault("")
        return OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(mapOf("Authorization" to header))
            .createDataSource()
    }
}
