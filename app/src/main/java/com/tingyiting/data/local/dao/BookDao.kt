package com.tingyiting.data.local.dao

import androidx.room.*
import com.tingyiting.data.local.entity.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {

    @Query("SELECT * FROM books ORDER BY lastPlayedAt DESC")
    fun getAllBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE webdavUrl = :url LIMIT 1")
    suspend fun getBookByUrl(url: String): BookEntity?

    @Query("SELECT * FROM books WHERE rootPath = :rootPath LIMIT 1")
    suspend fun getBookByRootPath(rootPath: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity)

    @Delete
    suspend fun delete(book: BookEntity)

    @Query("UPDATE books SET position = :position, duration = :duration, lastPlayedAt = :timestamp WHERE id = :id")
    suspend fun updateProgress(id: Long, position: Long, duration: Long, timestamp: Long)

    @Query("UPDATE books SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE books SET coverUrl = :coverUrl WHERE id = :id")
    suspend fun updateCover(id: Long, coverUrl: String)

    /**
     * 更新本专辑的「跳过头尾」设置（六列同步写入）。
     * 历史时长由调用方序列化（逗号分隔字符串），DAO 不解析，避免循环依赖。
     */
    @Query(
        """
        UPDATE books SET
            introSkipEnabled = :introEnabled,
            introSkipSeconds = :introSeconds,
            introSkipHistory = :introHistory,
            outroSkipEnabled = :outroEnabled,
            outroSkipSeconds = :outroSeconds,
            outroSkipHistory = :outroHistory
        WHERE id = :id
        """
    )
    suspend fun updateSkipSettings(
        id: Long,
        introEnabled: Boolean,
        introSeconds: Int,
        introHistory: String,
        outroEnabled: Boolean,
        outroSeconds: Int,
        outroHistory: String
    )
}
