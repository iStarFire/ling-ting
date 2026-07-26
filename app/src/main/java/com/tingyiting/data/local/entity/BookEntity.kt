package com.tingyiting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.tingyiting.data.model.SOURCE_WEBDAV

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
    val lastPlayedAt: Long = 0
)
