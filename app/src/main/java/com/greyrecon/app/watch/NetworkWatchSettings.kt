package com.greyrecon.app.watch

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.greyrecon.app.engine.discovery.NetworkIdentity
import com.greyrecon.app.history.DeviceHistoryStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Pro-only control for background monitoring, scoped to the network the phone is on right now.
 *
 * Per-network rather than global on purpose: the useful version of this feature is "watch my
 * home", not "watch whatever I happen to be connected to". A global switch would quietly start
 * scanning every guest network the user joins, which is both noisy and impolite.
 */
@Composable
fun NetworkWatchSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { DeviceHistoryStore(context) }

    var networkLabel by remember { mutableStateOf<String?>(null) }
    var networkKey by remember { mutableStateOf<String?>(null) }
    var enabled by remember { mutableStateOf(false) }
    var intervalMinutes by remember { mutableLongStateOf(NetworkWatchWorker.DEFAULT_INTERVAL_MINUTES) }

    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ActivityCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsGranted = granted }

    LaunchedEffect(Unit) {
        val identity = withContext(Dispatchers.IO) { NetworkIdentity.resolve(context) }
        if (identity != null) {
            val profile = store.registerNetwork(identity)
            networkKey = profile.networkKey
            networkLabel = profile.label
            enabled = profile.watchEnabled
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Network Watch", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = enabled,
                enabled = networkKey != null,
                onCheckedChange = { want ->
                    val key = networkKey ?: return@Switch
                    enabled = want
                    scope.launch {
                        store.setWatchEnabled(key, want)
                        if (want) {
                            NetworkWatchWorker.schedule(context, intervalMinutes)
                            if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        } else if (store.watchedNetworks().isEmpty()) {
                            // Only tear the schedule down when no network wants it any more --
                            // the worker is shared across every watched network.
                            NetworkWatchWorker.cancel(context)
                        }
                    }
                },
            )
        }

        Text(
            when {
                networkKey == null ->
                    "Connect to a WiFi network to enable monitoring for it."
                enabled ->
                    "Scanning ${networkLabel ?: "this network"} in the background and alerting you when an unrecognised device joins."
                else ->
                    "Scan ${networkLabel ?: "this network"} in the background and get alerted when an unrecognised device joins. Applies to this network only."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        if (enabled && !notificationsGranted) {
            Text(
                "Notifications are turned off, so alerts will be recorded in the timeline but not shown.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (enabled) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                NetworkWatchWorker.INTERVALS_MINUTES.forEach { minutes ->
                    FilterChip(
                        selected = intervalMinutes == minutes,
                        onClick = {
                            intervalMinutes = minutes
                            NetworkWatchWorker.schedule(context, minutes)
                        },
                        label = { Text(if (minutes < 60) "${minutes}m" else "${minutes / 60}h") },
                        modifier = Modifier.padding(end = 6.dp),
                    )
                }
            }
            Text(
                "Android will not run background work more often than every 15 minutes, and may delay it further to save battery.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
