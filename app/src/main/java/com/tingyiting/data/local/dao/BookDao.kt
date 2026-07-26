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

    @Query("UPDATE books SET coverUrl = :coverUrl WHERE id = :id")
    suspend fun updateCover(id: Long, coverUrl: String)
}
