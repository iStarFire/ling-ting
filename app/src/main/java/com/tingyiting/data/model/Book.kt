package com.tingyiting.data.model

/** 书籍数据来源：来自 WebDAV 网盘，或本地存储导入。 */
const val SOURCE_WEBDAV = "webdav"
const val SOURCE_LOCAL = "local"

data class Book(
    val id: Long = 0,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val webdavUrl: String = "",
    val rootPath: String = "",
    val source: String = SOURCE_WEBDAV,
    val currentTrackIndex: Int = 0,
    val duration: Long = 0,
    val position: Long = 0
)

data class WebDavFile(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val contentType: String = ""
) {
    val isAudio: Boolean
        get() = !isDirectory && SUPPORTED_AUDIO_EXTENSIONS.any { name.endsWith(it, ignoreCase = true) }

    companion object {
        val SUPPORTED_AUDIO_EXTENSIONS = listOf(".mp3", ".m4a", ".m4b", ".ogg", ".wav", ".flac", ".aac", ".opus")
    }
}
