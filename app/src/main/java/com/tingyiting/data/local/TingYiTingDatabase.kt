package com.tingyiting.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tingyiting.data.local.dao.BookDao
import com.tingyiting.data.local.entity.BookEntity

@Database(entities = [BookEntity::class], version = 1, exportSchema = false)
abstract class TingYiTingDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
}
