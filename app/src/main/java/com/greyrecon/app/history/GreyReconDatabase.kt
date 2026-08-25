package com.greyrecon.app.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

// Purely additive (a new table, nothing existing changed), but real testers now have Device
// History data worth keeping across an update -- unlike the version 1->2 jump, this one gets a
// real migration instead of relying on fallbackToDestructiveMigration.
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `tracker_sightings` (" +
                "`bleAddress` TEXT NOT NULL, `trackerType` TEXT NOT NULL, " +
                "`firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                "`sightingCount` INTEGER NOT NULL, PRIMARY KEY(`bleAddress`))"
        )
    }
}

@Database(entities = [DeviceRecord::class, NetworkEvent::class, TrackerSighting::class], version = 3, exportSchema = false)
abstract class GreyReconDatabase : RoomDatabase() {
    abstract fun deviceHistoryDao(): DeviceHistoryDao
    abstract fun networkEventDao(): NetworkEventDao
    abstract fun trackerSightingDao(): TrackerSightingDao

    companion object {
        @Volatile private var instance: GreyReconDatabase? = null

        fun get(context: Context): GreyReconDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GreyReconDatabase::class.java,
                    "greyrecon.db",
                )
                    .addMigrations(MIGRATION_2_3)
                    // Safety net for any *other* schema drift this explicit migration doesn't
                    // cover -- real testers now have data worth keeping, but this is still
                    // pre-1.0 enough that a clean reset beats a crash if something's missed.
                    .fallbackToDestructiveMigration(true)
                    .build().also { instance = it }
            }
    }
}
