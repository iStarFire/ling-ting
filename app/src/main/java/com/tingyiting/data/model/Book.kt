package com.tingyiting.data.model

data class Book(
    val id: Long = 0,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val webdavUrl: String = "",
    val rootPath: String = "",
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
