package com.tingyiting

import android.app.Application
import com.tingyiting.data.repository.WebDavRepository
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class TingYiTingApp : Application() {

    @Inject
    lateinit var webDavRepository: WebDavRepository

    override fun onCreate() {
        super.onCreate()
        // 启动时自动恢复已保存的 WebDAV 配置，书架/播放免重复登录
        webDavRepository.restore()
    }
}
