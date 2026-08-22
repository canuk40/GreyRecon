package com.greyrecon.app.history

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.greyrecon.app.engine.model.Device

/** Fires a local notification when a scan finds a device that's never been seen before -- the core "is someone on my network" alert. */
class NewDeviceNotifier(private val context: Context) {

    fun notifyNewDevice(device: Device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "New devices", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val label = device.vendor ?: device.hostname ?: device.deviceType.name
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("New device on your network")
            .setContentText("$label — ${device.ipAddress}")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(device.ipAddress.hashCode(), notification)
    }

    private companion object {
        const val CHANNEL_ID = "new_device"
    }
}
