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
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.greyrecon.app.engine.discovery.NetworkIdentity
import com.greyrecon.app.engine.discovery.NetworkScan
import com.greyrecon.app.engine.discovery.VendorLookup
import com.greyrecon.app.history.DeviceHistoryStore
import java.util.concurrent.TimeUnit

/**
 * Scans the network on a schedule while the app is closed and raises one alert if anything new
 * appeared.
 *
 * This is what makes the existing new-device detection actually mean something. Until now
 * [com.greyrecon.app.history.NewDeviceNotifier] could only fire during a manual, foreground scan,
 * which meant "tell me when an unknown device joins my network" only worked while the user was
 * already looking at the screen -- the one moment they do not need telling.
 *
 * Three deliberate constraints:
 *
 *  - **Opt in per network.** Only networks with `watchEnabled` are scanned. Watching your home is
 *    wanted; watching an airport is not, and scanning a network you are a guest on is rude at best.
 *  - **Never scan an unknown network.** If the current [NetworkIdentity] has no profile, or that
 *    profile is not watched, the worker exits without touching history. Recording a scan here
 *    would corrupt the baseline it is supposed to be protecting.
 *  - **One grouped alert, not N.** `recordScanResults(silent = true)` suppresses the per-device
 *    notifications and this worker posts a single summary instead. Arriving home to eleven
 *    separate notifications is how an app gets its notifications disabled.
 */
class NetworkWatchWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val context = applicationContext
        val identity = NetworkIdentity.resolve(context) ?: return Result.success()
        val store = DeviceHistoryStore(context)

        val profile = store.watchedNetworks().firstOrNull { it.networkKey == identity.key }
            ?: return Result.success()

        val devices = NetworkScan.run(context, VendorLookup(context))
        if (devices.isEmpty()) {
            // A scan that found nothing is far more likely to be a blocked ARP table or a dropped
            // connection than a genuinely empty network. Recording it would mark every known
            // device offline and then "new" again on the next run.
            return Result.retry()
        }

        val newDevices = store.recordScanResults(identity.key, devices, silent = true)
        if (newDevices.isNotEmpty()) notifySummary(context, profile.label, newDevices.size, newDevices.first().let {
            it.vendor ?: it.hostname ?: it.ipAddress
        })

        return Result.success()
    }

    private fun notifySummary(context: Context, networkLabel: String, count: Int, firstLabel: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Network watch", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val text = if (count == 1) "$firstLabel joined $networkLabel" else "$count new devices on $networkLabel"
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("New device detected")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(WATCH_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "network_watch"
        private const val WATCH_NOTIFICATION_ID = 0x7A7C
        private const val WORK_NAME = "greyrecon-network-watch"

        /**
         * WorkManager's floor for periodic work is 15 minutes; anything smaller is silently
         * clamped, so it is stated explicitly here rather than discovered later.
         */
        val INTERVALS_MINUTES = listOf(15L, 30L, 60L, 180L, 360L)
        const val DEFAULT_INTERVAL_MINUTES = 30L

        fun schedule(context: Context, intervalMinutes: Long = DEFAULT_INTERVAL_MINUTES) {
            val request = PeriodicWorkRequestBuilder<NetworkWatchWorker>(
                intervalMinutes.coerceAtLeast(15L), TimeUnit.MINUTES
            )
                .setConstraints(
                    Constraints.Builder()
                        // CONNECTED rather than UNMETERED: a scan is a handful of LAN packets and
                        // costs no mobile data, and plenty of home networks report as metered.
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                // UPDATE rather than KEEP so changing the interval in Settings takes effect
                // without the user having to toggle the whole feature off and on.
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
