package com.greyrecon.app.watch

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat

/** Pro-only switch for background Bluetooth tracker monitoring. */
@Composable
fun TrackerWatchSettings() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean(KEY_ENABLED, false)) }

    val scanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Manifest.permission.BLUETOOTH_SCAN
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }
    var granted by remember {
        mutableStateOf(ActivityCompat.checkSelfPermission(context, scanPermission) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok && enabled) TrackerWatchWorker.schedule(context)
    }

    val bluetoothOn = runCatching {
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
    }.getOrDefault(false)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("Tracker Watch", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = enabled,
                onCheckedChange = { want ->
                    enabled = want
                    prefs.edit().putBoolean(KEY_ENABLED, want).apply()
                    if (want) {
                        if (granted) TrackerWatchWorker.schedule(context) else permissionLauncher.launch(scanPermission)
                    } else {
                        TrackerWatchWorker.cancel(context)
                    }
                },
            )
        }

        Text(
            "Scans for Bluetooth item-finder trackers (AirTag, SmartTag, Tile, Chipolo, Pebblebee) in the background and warns you when the same one keeps turning up near you.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Cannot tell a tracker following you from one travelling with you, because GreyRecon does not use your location. AirTags and SmartTags also rotate their Bluetooth address to defeat exactly this kind of detection, so they are under-reported.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (enabled && !bluetoothOn) {
            Text(
                "Bluetooth is turned off, so nothing can be scanned for. Tracker Watch will stay idle until you turn it on.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (enabled && !granted) {
            Text(
                "Bluetooth scanning permission is required for this to run.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private const val PREFS = "greyrecon_watch"
private const val KEY_ENABLED = "tracker_watch_enabled"
