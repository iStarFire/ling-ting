package com.tingyiting.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val author: String = "",
    val coverUrl: String = "",
    val webdavUrl: String,
    val duration: Long = 0,
    val position: Long = 0,
    val lastPlayedAt: Long = 0
)
