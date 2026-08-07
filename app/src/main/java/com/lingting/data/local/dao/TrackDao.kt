package com.lingting.data.local.dao

import androidx.room.*
import com.lingting.data.local.entity.TrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY trackIndex ASC")
    suspend fun getByBookId(bookId: Long): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY trackIndex ASC")
    fun observeByBookId(bookId: Long): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId AND trackIndex = :index LIMIT 1")
    suspend fun getByIndex(bookId: Long, index: Int): TrackEntity?

    @Query("SELECT COUNT(*) FROM tracks WHERE bookId = :bookId")
    suspend fun countByBookId(bookId: Long): Int

    @Query(
        "UPDATE tracks SET position = :position, duration = :duration " +
            "WHERE bookId = :bookId AND trackIndex = :index"
    )
    suspend fun updateProgress(bookId: Long, index: Int, position: Long, duration: Long)

    @Query("DELETE FROM tracks WHERE bookId = :bookId")
    suspend fun deleteByBookId(bookId: Long)
}
