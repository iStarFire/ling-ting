package com.tingyiting.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.tingyiting.data.local.dao.BookDao
import com.tingyiting.data.local.dao.TrackDao
import com.tingyiting.data.local.entity.BookEntity
import com.tingyiting.data.local.entity.TrackEntity

@Database(entities = [BookEntity::class, TrackEntity::class], version = 4, exportSchema = false)
abstract class TingYiTingDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun trackDao(): TrackDao

    companion object {
        // v1 -> v2：仅新增 tracks 表，不改动 books，旧单文件书籍不受影响
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tracks (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        bookId INTEGER NOT NULL,
                        trackIndex INTEGER NOT NULL,
                        title TEXT NOT NULL,
                        webdavUrl TEXT NOT NULL,
                        path TEXT NOT NULL,
                        duration INTEGER NOT NULL DEFAULT 0,
                        position INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(bookId) REFERENCES books(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_tracks_bookId ON tracks(bookId)")
            }
        }

        // v2 -> v3：books 表新增 rootPath 与 currentTrackIndex 两列
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN rootPath TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE books ADD COLUMN currentTrackIndex INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v3 -> v4：books 表新增 source 列（数据来源：webdav / local），默认 webdav
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE books ADD COLUMN source TEXT NOT NULL DEFAULT 'webdav'")
            }
        }
    }
}
