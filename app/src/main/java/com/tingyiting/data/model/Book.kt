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
    val position: Long = 0,
    /** 是否启用片头跳过；开关仅作用于本专辑。 */
    val introSkipEnabled: Boolean = false,
    /** 片头跳过秒数（0-180）。 */
    val introSkipSeconds: Int = 0,
    /** 历史使用过的片头跳过时长（按当前专辑聚合，便于不同集选不同片头时长）。 */
    val introSkipHistory: List<Int> = emptyList(),
    /** 是否启用片尾跳过。 */
    val outroSkipEnabled: Boolean = false,
    /** 片尾跳过秒数（距结尾倒推，0-180）。 */
    val outroSkipSeconds: Int = 0,
    /** 历史使用过的片尾跳过时长。 */
    val outroSkipHistory: List<Int> = emptyList()
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
