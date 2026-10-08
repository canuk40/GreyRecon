package com.greyrecon.app.watch

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.greyrecon.app.engine.discovery.BleScanner
import com.greyrecon.app.engine.discovery.TrackerSightingStore
import com.greyrecon.app.engine.discovery.TrackerType
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList

/**
 * Periodically scans for Bluetooth item-finder trackers while the app is closed, and warns when
 * the same one keeps turning up.
 *
 * The signature detection this relies on already existed and is good -- it was verified against
 * AirGuard's own source. What it never had was a reason to run: a stalker-detection feature that
 * only works while you are holding the app open and looking at the BLE screen is not one. This
 * worker is the missing half.
 *
 * Two limits stated plainly, because the alert copy has to stay honest about them:
 *
 *  - **No location correlation.** AirGuard decides "this followed me" by comparing sightings
 *    against location changes. GreyRecon holds no location permission and is not taking one for
 *    this, so it cannot separate "followed me across town" from "I sat near the same backpack all
 *    afternoon". The heuristic is therefore sessions-over-time, and the notification says
 *    "repeatedly near you", not "following you".
 *  - **Address rotation.** AirTags and SmartTags rotate their advertised address specifically to
 *    defeat correlation by a passive observer, which is exactly what this does. Rotation makes the
 *    same physical tracker look like a new row, so this under-reports those types and works best
 *    on Tile/Chipolo/Pebblebee. Android also throttles background BLE scanning, so cycles will be
 *    missed. Both push toward false negatives rather than false alarms, which is the right way to
 *    be wrong here.
 */
class TrackerWatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        if (!hasScanPermission(context)) return Result.success()

        val store = TrackerSightingStore(context)

        // One record per address per session -- sightingCount is a session count, and BLE
        // advertisements arrive many times a second.
        val seen: Map<String, TrackerType> = try {
            BleScanner(context).scan(SCAN_DURATION_MS).toList()
                .mapNotNull { device -> device.trackerType?.let { device.address to it } }
                .toMap()
        } catch (_: SecurityException) {
            return Result.success()
        }

        seen.forEach { (address, type) -> store.recordSighting(address, type) }

        val candidates = store.followCandidates()
        if (candidates.isNotEmpty()) {
            val top = candidates.first()
            notify(context, TrackerType.valueOf(top.trackerType).displayName, top.sightingCount, candidates.size)
            candidates.forEach { store.markAlerted(it.bleAddress) }
        }

        return Result.success()
    }

    private fun hasScanPermission(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            // Below API 31 BLE scanning is gated on location, which this app deliberately does not
            // request. Tracker watch is simply unavailable there rather than silently broken.
            ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }

    private fun notify(context: Context, typeName: String, sessions: Int, total: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Tracker watch", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val text = if (total == 1) {
            "A $typeName has been near you in $sessions separate scans. This may be your own, or someone you are with."
        } else {
            "$total trackers have been repeatedly near you, including a $typeName seen in $sessions scans."
        }

        NotificationManagerCompat.from(context).notify(
            TRACKER_NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Tracker repeatedly nearby")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .build(),
        )
    }

    companion object {
        private const val CHANNEL_ID = "tracker_watch"
        private const val TRACKER_NOTIFICATION_ID = 0x7A7D
        private const val WORK_NAME = "greyrecon-tracker-watch"
        private const val SCAN_DURATION_MS = 10_000L

        /**
         * 15 minutes is WorkManager's floor and also roughly the right cadence: the heuristic
         * needs four sessions spread over half an hour before it says anything.
         */
        const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TrackerWatchWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                    // No network constraint: this is pure BLE and must keep working with the
                    // phone offline, which is exactly when someone would want it.
                    .setConstraints(Constraints.Builder().build())
                    .build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
