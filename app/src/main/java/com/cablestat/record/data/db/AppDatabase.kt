package com.cablestat.record.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, RecordEntity::class, SpecEntity::class, ColorEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun projectDao(): ProjectDao
    abstract fun recordDao(): RecordDao
    abstract fun specDao(): SpecDao
    abstract fun colorDao(): ColorDao

    companion object {
        @Volatile
        private var inst: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            inst ?: synchronized(this) {
                inst ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "cablestat.db"
                ).build().also { inst = it }
            }
    }
}