package com.greyrecon.app.ui.tools

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.greyrecon.app.engine.discovery.BleDevice
import com.greyrecon.app.engine.discovery.BleScanner
import com.greyrecon.app.engine.discovery.TrackerSightingStore
import com.greyrecon.app.history.TrackerSighting
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleScanScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var scanning by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<BleDevice>>(emptyList()) }
    var sightings by remember { mutableStateOf<Map<String, TrackerSighting>>(emptyMap()) }
    val scope = rememberCoroutineScope()
    val sightingStore = remember { TrackerSightingStore(context) }

    fun runScan() {
        scanning = true
        devices = emptyList()
        sightings = emptyMap()
        scope.launch {
            BleScanner(context).scan().collect { found ->
                devices = (devices.filter { it.address != found.address } + found).sortedByDescending { it.rssi }
                found.trackerType?.let { type ->
                    sightings = sightings + (found.address to sightingStore.recordSighting(found.address, type))
                }
            }
            scanning = false
        }
    }

    // BLUETOOTH_SCAN is a real runtime-requested permission on API 31+ (targetSdk 36) -- below
    // that it's covered by the legacy install-time BLUETOOTH/BLUETOOTH_ADMIN permissions instead
    // (see AndroidManifest.xml), so no prompt is needed at all pre-S.
    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) runScan() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BLE Scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                "Scans for nearby Bluetooth Low Energy advertisements (earbuds, wearables, beacons, smart-home gear) -- separate from the WiFi-network device scan, since BLE addresses aren't part of this network's IP space.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(
                onClick = {
                    if (scanning) return@Button
                    val needsScanPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED
                    if (needsScanPermission) {
                        scanPermissionLauncher.launch(Manifest.permission.BLUETOOTH_SCAN)
                    } else {
                        runScan()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text(if (scanning) "Scanning..." else "Scan") }

            if (scanning) CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))

            val spamCount = devices.count { it.spamSignature != null }
            if (spamCount > 0) {
                Text(
                    "⚠ $spamCount possible BLE spam packet(s) detected -- someone nearby may be running a BLE-spam attack (spoofed pairing/action prompts). See flagged entries below.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            if (devices.isNotEmpty()) {
                Text(
                    "${devices.size} device(s) found",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                devices.forEach { device ->
                    Text(
                        "${device.address}  ${device.rssi}dBm" +
                            (device.name?.let { "  $it" } ?: "") +
                            (device.vendor?.let { "  [$it]" } ?: "") +
                            (device.services.takeIf { it.isNotEmpty() }?.joinToString(prefix = "  {", postfix = "}") ?: ""),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    device.trackerType?.let { type ->
                        val sighting = sightings[device.address]
                        val repeatNote = sighting?.takeIf { it.sightingCount > 1 }
                            ?.let { "  --  seen ${it.sightingCount}x, first noticed ${formatAgo(it.firstSeenAt)} ago" }
                            ?: ""
                        Text(
                            "  ⚠ ${type.displayName}$repeatNote",
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    device.spamSignature?.let { spam ->
                        Text(
                            "  🚨 ${spam.displayName}",
                            color = MaterialTheme.colorScheme.error,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private fun formatAgo(timestampMs: Long): String {
    val minutes = (System.currentTimeMillis() - timestampMs) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m"
        minutes < 1440 -> "${minutes / 60}h"
        else -> "${minutes / 1440}d"
    }
}
