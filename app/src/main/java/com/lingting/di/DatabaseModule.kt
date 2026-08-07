package com.lingting.di

import android.content.Context
import androidx.room.Room
import com.lingting.data.local.LingTingDatabase
import com.lingting.data.local.dao.BookDao
import com.lingting.data.local.dao.TrackDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): LingTingDatabase {
        return Room.databaseBuilder(
            context,
            LingTingDatabase::class.java,
            "lingting.db"
        ).addMigrations(
            LingTingDatabase.MIGRATION_1_2,
            LingTingDatabase.MIGRATION_2_3,
            LingTingDatabase.MIGRATION_3_4,
            LingTingDatabase.MIGRATION_4_5
        ).build()
    }

    @Provides
    fun provideBookDao(database: LingTingDatabase): BookDao {
        return database.bookDao()
    }

    @Provides
    fun provideTrackDao(database: LingTingDatabase): TrackDao {
        return database.trackDao()
    }
}
