package com.lingting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.lingting.data.model.SOURCE_WEBDAV

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    // 单文件书籍的音频地址；目录导入的有声剧也可留空（曲目存于 tracks 表）
    val webdavUrl: String = "",
    // 导入目录的根路径（用于去重）；单文件书籍为空
    val rootPath: String = "",
    // 数据来源：SOURCE_WEBDAV（网盘）或 SOURCE_LOCAL（本地导入）
    val source: String = SOURCE_WEBDAV,
    // 当前播放到的集序号（目录有声剧用）；单文件书籍为 0
    val currentTrackIndex: Int = 0,
    val duration: Long = 0,
    val position: Long = 0,
    val lastPlayedAt: Long = 0,
    // 跳过头尾设置（仅作用于本专辑所有声音；详见 Book 中字段注释）
    val introSkipEnabled: Boolean = false,
    val introSkipSeconds: Int = 0,
    /** 片头历史时长，逗号分隔，例如 "30,60"，空字符串表示无历史。 */
    val introSkipHistory: String = "",
    val outroSkipEnabled: Boolean = false,
    val outroSkipSeconds: Int = 0,
    /** 片尾历史时长，逗号分隔。 */
    val outroSkipHistory: String = ""
)
