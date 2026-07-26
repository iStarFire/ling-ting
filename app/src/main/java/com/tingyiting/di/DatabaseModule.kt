package com.tingyiting.di

import android.content.Context
import androidx.room.Room
import com.tingyiting.data.local.TingYiTingDatabase
import com.tingyiting.data.local.dao.BookDao
import com.tingyiting.data.local.dao.TrackDao
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
    fun provideDatabase(@ApplicationContext context: Context): TingYiTingDatabase {
        return Room.databaseBuilder(
            context,
            TingYiTingDatabase::class.java,
            "tingyiting.db"
        ).addMigrations(
            TingYiTingDatabase.MIGRATION_1_2,
            TingYiTingDatabase.MIGRATION_2_3
        ).build()
    }

    @Provides
    fun provideBookDao(database: TingYiTingDatabase): BookDao {
        return database.bookDao()
    }

    @Provides
    fun provideTrackDao(database: TingYiTingDatabase): TrackDao {
        return database.trackDao()
    }
}
