package com.tingyiting.data.model

data class Track(
    val index: Int,
    val title: String,
    val webdavUrl: String,
    val path: String,
    val duration: Long = 0,
    val position: Long = 0
)
