package com.greyrecon.app.ui.tools

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.greyrecon.app.R
import com.greyrecon.app.engine.discovery.VendorLookup
import com.greyrecon.app.engine.wifi.AccessPoint
import com.greyrecon.app.engine.wifi.AirspaceFinding
import com.greyrecon.app.engine.wifi.WifiAirspaceAnalysis
import com.greyrecon.app.engine.wifi.WifiApScanner
import kotlinx.coroutines.delay

/**
 * Nearby access points, with the airspace findings above the list.
 *
 * Findings first on purpose. The channel/signal list is what every WiFi analyzer shows and is
 * mostly useful for troubleshooting; the findings are the reason a security app ships this screen
 * at all, and burying a possible rogue AP under forty rows of signal strength would be a strange
 * choice for a tool whose whole pitch is noticing things.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WifiAnalyzerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scanner = remember { WifiApScanner(context) }
    val vendorLookup = remember { VendorLookup(context) }

    var granted by remember { mutableStateOf(scanner.hasPermission()) }
    var accessPoints by remember { mutableStateOf<List<AccessPoint>>(emptyList()) }
    var findings by remember { mutableStateOf<List<AirspaceFinding>>(emptyList()) }
    var refreshing by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { ok -> granted = ok }

    suspend fun refresh() {
        if (!granted) return
        refreshing = true
        // startScan() is deprecated and throttled to roughly four calls per two minutes, so the
        // cached list is shown immediately and the fresh sweep is treated as a bonus rather than
        // something to block on.
        accessPoints = scanner.accessPoints { bssid -> vendorLookup.lookup(bssid) }
        findings = WifiAirspaceAnalysis.analyse(accessPoints)
        if (scanner.requestScan()) {
            delay(3_000)
            accessPoints = scanner.accessPoints { bssid -> vendorLookup.lookup(bssid) }
            findings = WifiAirspaceAnalysis.analyse(accessPoints)
        }
        refreshing = false
    }

    LaunchedEffect(granted) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("WiFi Analyzer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = { }, enabled = false) {
                        Text(if (refreshing) "Scanning…" else "${accessPoints.size} APs")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            when {
                !granted -> PermissionPrompt(
                    onRequest = { permissionLauncher.launch(scanner.requiredPermission()) },
                )

                !scanner.isWifiEnabled() -> Text(
                    "WiFi is turned off, so nearby networks cannot be listed.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp),
                )

                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (findings.isNotEmpty()) {
                        items(findings) { finding -> FindingRow(finding) }
                        item { HorizontalDivider() }
                    }
                    items(accessPoints, key = { it.bssid }) { ap -> AccessPointRow(ap) }
                    if (accessPoints.isEmpty()) {
                        item {
                            Text(
                                "No access points found yet. Android caches WiFi scan results and limits how often an app may ask for a fresh sweep, so this can take a moment.",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            "Listing nearby access points needs permission to see WiFi devices around you.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "GreyRecon declares this as \"never for location\" -- the access point list is used for channels, signal and security only, and is never used to work out where you are.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 16.dp)) { Text("Grant permission") }
    }
}

@Composable
private fun FindingRow(finding: AirspaceFinding) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            "${severityLabel(finding.severity)} · ${finding.title}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(finding.detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun severityLabel(severity: AirspaceFinding.Severity): String = when (severity) {
    AirspaceFinding.Severity.CRITICAL -> "CRITICAL"
    AirspaceFinding.Severity.WARNING -> "WARNING"
    AirspaceFinding.Severity.INFO -> "INFO"
}

@Composable
private fun AccessPointRow(ap: AccessPoint) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ap.ssid ?: "(hidden network)",
                style = MaterialTheme.typography.bodyLarge,
            )
            Text("${ap.signalDbm} dBm", style = MaterialTheme.typography.bodySmall)
        }
        Text(
            listOfNotNull(
                ap.security.label,
                "${ap.band.label} ch ${ap.channel}",
                ap.channelWidthMhz?.let { "${it} MHz" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            listOfNotNull(ap.bssid, ap.vendor).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
        )
    }
    HorizontalDivider()
}
