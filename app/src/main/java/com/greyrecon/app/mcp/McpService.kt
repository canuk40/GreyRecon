package com.greyrecon.app.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.greyrecon.app.data.SecureKeyStore
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer

/**
 * Hosts [GreyReconMcpServer] as a foreground service so it keeps answering
 * requests from a user's nanobot instance even when GreyRecon isn't the
 * foreground app -- the whole point of a "server other devices query" Pro
 * feature. A persistent notification is required by Android for any
 * long-running background service and also doubles as an honest signal to
 * the user that GreyRecon is listening on the network.
 *
 * Type is `connectedDevice`, not `dataSync` -- this service exists specifically to let an
 * external device (the machine running nanobot) interact with the phone over the local network,
 * which is exactly what `connectedDevice` is for; `dataSync` was the wrong label for "hosts a
 * server other devices query" in the first place. Confirmed via Android's own foreground-service
 * docs: `connectedDevice` carries no execution-time limit (unlike `mediaProcessing`, which does),
 * and its manifest-permission prerequisite is already satisfied by `CHANGE_WIFI_MULTICAST_STATE`,
 * already required above for multicast discovery.
 */
class McpService : Service() {

    private var server: EmbeddedServer<*, *>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
        )

        val keyStore = SecureKeyStore(applicationContext)
        val token = keyStore.mcpAuthToken ?: SecureKeyStore.generateToken().also { keyStore.mcpAuthToken = it }
        val dataSource = ScanDataSource(applicationContext)

        server = embeddedServer(CIO, port = PORT, host = "0.0.0.0") {
            configureGreyReconMcp(token, dataSource)
        }.also { it.start(wait = false) }

        return START_STICKY
    }

    override fun onDestroy() {
        server?.stop(gracePeriodMillis = 200, timeoutMillis = 1000)
        server = null
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "MCP Server", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GreyRecon MCP server running")
            .setContentText("Listening on port $PORT for your nanobot instance")
            .setSmallIcon(android.R.drawable.ic_menu_search)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val PORT = 8642
        private const val CHANNEL_ID = "mcp_server"
        private const val NOTIFICATION_ID = 1
    }
}
