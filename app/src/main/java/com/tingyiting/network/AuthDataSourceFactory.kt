package com.tingyiting.ui.player

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class AuthDataSourceFactory(
    private val authHeader: String
) : DataSource.Factory {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    override fun createDataSource(): DataSource {
        return OkHttpDataSource.Factory(okHttpClient)
            .setDefaultRequestProperties(mapOf("Authorization" to authHeader))
            .createDataSource()
    }
}
