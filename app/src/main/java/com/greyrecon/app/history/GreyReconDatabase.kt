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

/**
 * Scopes device history and events to a network (see
 * [com.greyrecon.app.engine.discovery.NetworkIdentity]) and adds the `network_profiles` table.
 *
 * Done by rebuilding both tables rather than `ALTER TABLE ... ADD COLUMN`, because adding a
 * NOT NULL column in SQLite requires a DEFAULT clause, that default is then baked into the stored
 * schema, and Room's identity check compares defaults. A mismatch there would not throw --
 * `fallbackToDestructiveMigration` is enabled, so it would silently delete every tester's history
 * instead. Rebuilding gives byte-exact control over the resulting schema.
 *
 * Pre-v4 rows have no network attribution and cannot be given one retroactively, so they are
 * gathered under a single legacy profile rather than discarded. Their ids stay unscoped, which is
 * safe: scoped ids written from v4 onward always contain "|", so the two can never collide.
 */
private const val LEGACY_NETWORK_KEY = "legacy:pre-v4"

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `network_profiles` (" +
                "`networkKey` TEXT NOT NULL, `label` TEXT NOT NULL, " +
                "`isStrongKey` INTEGER NOT NULL, `firstSeenAt` INTEGER NOT NULL, " +
                "`lastSeenAt` INTEGER NOT NULL, `watchEnabled` INTEGER NOT NULL, " +
                "PRIMARY KEY(`networkKey`))"
        )

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `device_history_new` (" +
                "`id` TEXT NOT NULL, `networkKey` TEXT NOT NULL, `macAddress` TEXT, " +
                "`lastKnownIp` TEXT NOT NULL, `vendor` TEXT, `hostname` TEXT, " +
                "`deviceType` TEXT NOT NULL, `customName` TEXT, `notes` TEXT, " +
                "`firstSeenAt` INTEGER NOT NULL, `lastSeenAt` INTEGER NOT NULL, " +
                "`isOnline` INTEGER NOT NULL, PRIMARY KEY(`id`))"
        )
        db.execSQL(
            "INSERT INTO `device_history_new` (`id`, `networkKey`, `macAddress`, `lastKnownIp`, " +
                "`vendor`, `hostname`, `deviceType`, `customName`, `notes`, `firstSeenAt`, " +
                "`lastSeenAt`, `isOnline`) SELECT `id`, '$LEGACY_NETWORK_KEY', `macAddress`, " +
                "`lastKnownIp`, `vendor`, `hostname`, `deviceType`, `customName`, `notes`, " +
                "`firstSeenAt`, `lastSeenAt`, `isOnline` FROM `device_history`"
        )
        db.execSQL("DROP TABLE `device_history`")
        db.execSQL("ALTER TABLE `device_history_new` RENAME TO `device_history`")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `network_events_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `networkKey` TEXT NOT NULL, " +
                "`deviceId` TEXT NOT NULL, `type` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, " +
                "`detail` TEXT NOT NULL)"
        )
        db.execSQL(
            "INSERT INTO `network_events_new` (`id`, `networkKey`, `deviceId`, `type`, " +
                "`timestamp`, `detail`) SELECT `id`, '$LEGACY_NETWORK_KEY', `deviceId`, `type`, " +
                "`timestamp`, `detail` FROM `network_events`"
        )
        db.execSQL("DROP TABLE `network_events`")
        db.execSQL("ALTER TABLE `network_events_new` RENAME TO `network_events`")

        // Only create the legacy profile if it actually has rows, so a user who never scanned
        // before upgrading does not see a phantom network in the picker.
        db.query("SELECT COUNT(*) FROM `device_history`").use { cursor ->
            val carried = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            if (carried > 0) {
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT OR REPLACE INTO `network_profiles` (`networkKey`, `label`, " +
                        "`isStrongKey`, `firstSeenAt`, `lastSeenAt`, `watchEnabled`) " +
                        "VALUES ('$LEGACY_NETWORK_KEY', 'Earlier scans', 0, $now, $now, 0)"
                )
            }
        }
    }
}

@Database(entities = [DeviceRecord::class, NetworkEvent::class, TrackerSighting::class, NetworkProfile::class], version = 4, exportSchema = true)
abstract class GreyReconDatabase : RoomDatabase() {
    abstract fun deviceHistoryDao(): DeviceHistoryDao
    abstract fun networkEventDao(): NetworkEventDao
    abstract fun trackerSightingDao(): TrackerSightingDao
    abstract fun networkProfileDao(): NetworkProfileDao

    companion object {
        @Volatile private var instance: GreyReconDatabase? = null

        fun get(context: Context): GreyReconDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GreyReconDatabase::class.java,
                    "greyrecon.db",
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // Safety net for any *other* schema drift this explicit migration doesn't
                    // cover -- real testers now have data worth keeping, but this is still
                    // pre-1.0 enough that a clean reset beats a crash if something's missed.
                    .fallbackToDestructiveMigration(true)
                    .build().also { instance = it }
            }
    }
}
