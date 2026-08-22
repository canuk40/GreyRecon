package com.greyrecon.app.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DeviceRecord::class, NetworkEvent::class], version = 2, exportSchema = false)
abstract class GreyReconDatabase : RoomDatabase() {
    abstract fun deviceHistoryDao(): DeviceHistoryDao
    abstract fun networkEventDao(): NetworkEventDao

    companion object {
        @Volatile private var instance: GreyReconDatabase? = null

        fun get(context: Context): GreyReconDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GreyReconDatabase::class.java,
                    "greyrecon.db",
                )
                    // Pre-release only, no real user data to preserve -- a proper migration
                    // isn't worth writing for a database that's never shipped.
                    .fallbackToDestructiveMigration(true)
                    .build().also { instance = it }
            }
    }
}
