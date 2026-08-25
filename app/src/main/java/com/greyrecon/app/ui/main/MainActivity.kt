package com.greyrecon.app.ui.main

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.greyrecon.app.ai.AIProviderType
import com.greyrecon.app.billing.BillingManager
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.model.DeviceType
import com.greyrecon.app.engine.model.Port
import com.greyrecon.app.engine.model.ShodanFinding
import com.greyrecon.app.engine.security.SecurityCheckResult
import com.greyrecon.app.engine.snmp.SnmpClient
import com.greyrecon.app.export.ExportShare
import com.greyrecon.app.export.NmapXmlExporter
import com.greyrecon.app.export.OcsfExporter
import com.greyrecon.app.export.ScanExporter
import com.greyrecon.app.history.HistoryScreen
import com.greyrecon.app.ui.home.HomeScreen
import com.greyrecon.app.ui.score.NetworkScoreScreen
import com.greyrecon.app.ui.settings.SettingsScreen
import com.greyrecon.app.ui.theme.GreyReconTheme
import com.greyrecon.app.ui.tools.DnsLookupScreen
import com.greyrecon.app.ui.tools.SubnetCalculatorScreen
import com.greyrecon.app.ui.tools.ToolsScreen
import com.greyrecon.app.ui.tools.WhoisLookupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val keyStore = SecureKeyStore(applicationContext)
        val billingManager = BillingManager(applicationContext)
        billingManager.start() // top-level, not screen-scoped -- entitlement must be fresh on Home too, not just Settings
        setContent {
            GreyReconTheme {
                val navController = rememberNavController()
                NavHost(navController = navController, startDestination = "home") {
                    composable("home") {
                        val isPro by billingManager.isPro.collectAsState()
                        HomeScreen(isPro = isPro, onNavigate = { route -> navController.navigate(route) })
                    }
                    composable("scan") {
                        GreyReconApp(
                            viewModel, keyStore,
                            onBack = { navController.popBackStack() },
                            onOpenScore = { navController.navigate("score") },
                            onOpenTopology = { navController.navigate("topology") },
                            // ping rather than nmap -- toybox (see PkgFetchServer/greyrecon-pkg work)
                            // actually ships a ping applet; the terminal never bundles nmap itself
                            // (NPSL forbids it), so pre-filling a command implying it's installed would
                            // be misleading for most users.
                            onOpenTerminal = { ip -> navController.navigate("terminal?command=${Uri.encode("ping -c 4 $ip")}") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(keyStore, billingManager, onBack = { navController.popBackStack() })
                    }
                    composable("history") {
                        HistoryScreen(onBack = { navController.popBackStack() }, onOpenTimeline = { navController.navigate("timeline") })
                    }
                    composable("timeline") {
                        com.greyrecon.app.history.NetworkTimelineScreen(onBack = { navController.popBackStack() })
                    }
                    composable("tools") {
                        ToolsScreen(onBack = { navController.popBackStack() }, onNavigate = { route -> navController.navigate(route) })
                    }
                    composable("tools/subnet") {
                        SubnetCalculatorScreen(onBack = { navController.popBackStack() })
                    }
                    composable("tools/dns") {
                        DnsLookupScreen(onBack = { navController.popBackStack() })
                    }
                    composable("tools/whois") {
                        WhoisLookupScreen(onBack = { navController.popBackStack() })
                    }
                    composable("tools/ctlog") {
                        com.greyrecon.app.ui.tools.CtLogScreen(onBack = { navController.popBackStack() })
                    }
                    composable("tools/typosquat") {
                        com.greyrecon.app.ui.tools.TyposquatScreen(onBack = { navController.popBackStack() })
                    }
                    composable("tools/ble") {
                        com.greyrecon.app.ui.tools.BleScanScreen(onBack = { navController.popBackStack() })
                    }
                    composable("agent") {
                        com.greyrecon.app.ui.agent.AgentChatScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        "terminal?command={command}",
                        arguments = listOf(navArgument("command") { type = NavType.StringType; nullable = true; defaultValue = null }),
                    ) { backStackEntry ->
                        com.greyrecon.app.ui.terminal.TerminalScreen(
                            onBack = { navController.popBackStack() },
                            prefilledCommand = backStackEntry.arguments?.getString("command"),
                        )
                    }
                    composable("score") {
                        val state by viewModel.state.collectAsState()
                        val deviceActions by viewModel.deviceActions.collectAsState()
                        val devices = when (val s = state) {
                            is ScanState.Done -> s.devices
                            is ScanState.Scanning -> s.devices
                            else -> emptyList()
                        }
                        NetworkScoreScreen(devices, deviceActions, onBack = { navController.popBackStack() })
                    }
                    composable("topology") {
                        val state by viewModel.state.collectAsState()
                        val devices = when (val s = state) {
                            is ScanState.Done -> s.devices
                            is ScanState.Scanning -> s.devices
                            else -> emptyList()
                        }
                        com.greyrecon.app.ui.topology.NetworkTopologyScreen(devices, onBack = { navController.popBackStack() })
                    }
                }
            }
        }
    }
}

/** Which key-entry dialog is currently showing, and what to do once a key is entered. */
private sealed class PendingKeyRequest {
    data class ForAi(val device: Device, val question: String) : PendingKeyRequest()
    data class ForShodan(val ipAddress: String) : PendingKeyRequest()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GreyReconApp(
    viewModel: ScanViewModel,
    keyStore: SecureKeyStore,
    onBack: () -> Unit,
    onOpenScore: () -> Unit,
    onOpenTopology: () -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val deviceActions by viewModel.deviceActions.collectAsState()

    val context = androidx.compose.ui.platform.LocalContext.current

    val scanPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Only ACCESS_FINE_LOCATION actually gates the scan -- POST_NOTIFICATIONS is requested
        // alongside it for convenience but its denial shouldn't block scanning, just silence
        // new-device alerts. Re-check rather than trust the result map, since location may not
        // even have been in this request if it was already granted (only notifications was missing).
        val hasLocation = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasLocation) viewModel.startScan()
    }

    var expandedIp by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    val devicesSnapshot: List<Device> = when (val s = state) {
        is ScanState.Scanning -> s.devices
        is ScanState.Done -> s.devices
        else -> emptyList()
    }

    var pendingKeyRequest by remember { mutableStateOf<PendingKeyRequest?>(null) }

    pendingKeyRequest?.let { request ->
        KeyEntryDialog(
            title = when (request) {
                is PendingKeyRequest.ForAi -> when (keyStore.aiProvider) {
                    AIProviderType.ANTHROPIC -> "Anthropic API key"
                    else -> "DeepSeek API key"
                }
                is PendingKeyRequest.ForShodan -> "Shodan API key"
            },
            onSubmit = { key ->
                when (request) {
                    is PendingKeyRequest.ForAi -> {
                        val provider = keyStore.aiProvider
                        when (provider) {
                            AIProviderType.ANTHROPIC -> keyStore.anthropicKey = key
                            else -> keyStore.deepseekKey = key
                        }
                        viewModel.askAi(request.device, key, provider, request.question)
                    }
                    is PendingKeyRequest.ForShodan -> {
                        keyStore.shodanKey = key
                        viewModel.checkShodan(request.ipAddress, key)
                    }
                }
                pendingKeyRequest = null
            },
            onDismiss = { pendingKeyRequest = null },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan Network") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = "More options")
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("Export CSV") },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                ExportShare.share(context, ScanExporter.toCsv(devicesSnapshot), "greyrecon_scan.csv", "text/csv")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export JSON") },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                ExportShare.share(context, ScanExporter.toJson(devicesSnapshot), "greyrecon_scan.json", "application/json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export Nmap XML") },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                ExportShare.share(context, NmapXmlExporter.toNmapXml(devicesSnapshot), "greyrecon_scan.xml", "text/xml")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export OCSF") },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                ExportShare.share(context, OcsfExporter.toOcsf(devicesSnapshot), "greyrecon_scan_ocsf.json", "application/json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Security Score") },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = { menuExpanded = false; onOpenScore() },
                        )
                        DropdownMenuItem(
                            text = { Text("Topology") },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = { menuExpanded = false; onOpenTopology() },
                        )
                    }
                },
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Button(
                    onClick = {
                        val hasLocation = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val needsNotificationPermission = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        if (hasLocation && !needsNotificationPermission) {
                            viewModel.startScan()
                        } else {
                            val permissions = buildList {
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                if (needsNotificationPermission) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            scanPermissionLauncher.launch(permissions.toTypedArray())
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Scan Network")
                }

                val devices = when (val s = state) {
                    is ScanState.Idle -> {
                        Text(
                            text = "Tap Scan Network to discover devices on this WiFi network.",
                            modifier = Modifier.padding(top = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        emptyList()
                    }
                    is ScanState.NoWifiSubnet -> {
                        Text(
                            text = "Not connected to WiFi -- can't determine a subnet to scan.",
                            modifier = Modifier.padding(top = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        emptyList()
                    }
                    is ScanState.Scanning -> {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.padding(vertical = 16.dp))
                            Text("Scanning... ${s.devices.size} found so far")
                        }
                        s.devices
                    }
                    is ScanState.Done -> {
                        Text(
                            text = "${s.devices.size} devices found",
                            modifier = Modifier.padding(vertical = 8.dp),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        s.devices
                    }
                }

                DeviceList(
                    devices = devices,
                    expandedIp = expandedIp,
                    onToggleExpand = { ip -> expandedIp = if (expandedIp == ip) null else ip },
                    deviceActions = deviceActions,
                    onScanPorts = { ip -> viewModel.scanPorts(ip) },
                    onAskAi = { device ->
                        val provider = keyStore.aiProvider
                        val key = when (provider) {
                            AIProviderType.ANTHROPIC -> keyStore.anthropicKey
                            else -> keyStore.deepseekKey
                        }
                        if (key != null) viewModel.askAi(device, key, provider)
                        else pendingKeyRequest = PendingKeyRequest.ForAi(device, ScanViewModel.DEFAULT_AI_QUESTION)
                    },
                    onGetFixSteps = { device ->
                        val provider = keyStore.aiProvider
                        val key = when (provider) {
                            AIProviderType.ANTHROPIC -> keyStore.anthropicKey
                            else -> keyStore.deepseekKey
                        }
                        if (key != null) viewModel.askAi(device, key, provider, ScanViewModel.REMEDIATION_AI_QUESTION)
                        else pendingKeyRequest = PendingKeyRequest.ForAi(device, ScanViewModel.REMEDIATION_AI_QUESTION)
                    },
                    onCheckShodan = { ip ->
                        val key = keyStore.shodanKey
                        if (key != null) viewModel.checkShodan(ip, key)
                        else pendingKeyRequest = PendingKeyRequest.ForShodan(ip)
                    },
                    onWakeOnLan = { device -> viewModel.wakeOnLan(device) },
                    onCheckSecurity = { ip -> viewModel.checkSecurity(ip) },
                    onCheckNvd = { device -> viewModel.checkNvd(device, keyStore.nvdKey) },
                    onCheckSnmp = { ip -> viewModel.checkSnmp(ip) },
                    onCheckSnmpWalk = { ip -> viewModel.checkSnmpWalk(ip) },
                    onCheckSnmpBruteForce = { ip -> viewModel.checkSnmpBruteForce(ip) },
                    onCheckExposures = { ip -> viewModel.checkExposures(ip) },
                    onCheckDefaultCreds = { ip -> viewModel.checkDefaultCreds(ip) },
                    onOpenTerminal = onOpenTerminal,
                )
            }
        }
    }
}

@Composable
private fun KeyEntryDialog(title: String, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    "Saved encrypted on-device -- you won't be asked again. Manage keys anytime in Settings.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onSubmit(text) }) { Text("Use key") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun DeviceList(
    devices: List<Device>,
    expandedIp: String?,
    onToggleExpand: (String) -> Unit,
    deviceActions: Map<String, DeviceActions>,
    onScanPorts: (String) -> Unit,
    onAskAi: (Device) -> Unit,
    onGetFixSteps: (Device) -> Unit,
    onCheckShodan: (String) -> Unit,
    onWakeOnLan: (Device) -> Unit,
    onCheckSecurity: (String) -> Unit,
    onCheckNvd: (Device) -> Unit,
    onCheckSnmp: (String) -> Unit,
    onCheckSnmpWalk: (String) -> Unit,
    onCheckSnmpBruteForce: (String) -> Unit,
    onCheckExposures: (String) -> Unit,
    onCheckDefaultCreds: (String) -> Unit,
    onOpenTerminal: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(devices, key = { it.ipAddress }) { device ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand(device.ipAddress) }
                    .padding(vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(deviceTypeLabel(device.deviceType), style = MaterialTheme.typography.labelLarge)
                    Text(
                        listOfNotNull(device.ipAddress, device.vendor).joinToString(" — "),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                val subtitle = listOfNotNull(
                    device.hostname,
                    device.macAddress,
                    device.discoveredBy.joinToString(", ") { it.name },
                ).joinToString(" · ")
                Text(subtitle, style = MaterialTheme.typography.bodySmall)

                if (expandedIp == device.ipAddress) {
                    DeviceActionsPanel(
                        device = device,
                        actions = deviceActions[device.ipAddress] ?: DeviceActions(),
                        onScanPorts = { onScanPorts(device.ipAddress) },
                        onAskAi = { onAskAi(device) },
                        onGetFixSteps = { onGetFixSteps(device) },
                        onCheckShodan = { onCheckShodan(device.ipAddress) },
                        onWakeOnLan = { onWakeOnLan(device) },
                        onCheckSecurity = { onCheckSecurity(device.ipAddress) },
                        onCheckNvd = { onCheckNvd(device) },
                        onCheckSnmp = { onCheckSnmp(device.ipAddress) },
                        onCheckSnmpWalk = { onCheckSnmpWalk(device.ipAddress) },
                        onCheckSnmpBruteForce = { onCheckSnmpBruteForce(device.ipAddress) },
                        onCheckExposures = { onCheckExposures(device.ipAddress) },
                        onCheckDefaultCreds = { onCheckDefaultCreds(device.ipAddress) },
                        onOpenTerminal = { onOpenTerminal(device.ipAddress) },
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun DeviceActionsPanel(
    device: Device,
    actions: DeviceActions,
    onScanPorts: () -> Unit,
    onAskAi: () -> Unit,
    onGetFixSteps: () -> Unit,
    onCheckShodan: () -> Unit,
    onWakeOnLan: () -> Unit,
    onCheckSecurity: () -> Unit,
    onCheckNvd: () -> Unit,
    onCheckSnmp: () -> Unit,
    onCheckSnmpWalk: () -> Unit,
    onCheckSnmpBruteForce: () -> Unit,
    onCheckExposures: () -> Unit,
    onCheckDefaultCreds: () -> Unit,
    onOpenTerminal: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        var actionsMenuExpanded by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { actionsMenuExpanded = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                Text("Actions")
            }
            DropdownMenu(expanded = actionsMenuExpanded, onDismissRequest = { actionsMenuExpanded = false }) {
                DropdownMenuItem(text = { Text("Scan Ports") }, onClick = { actionsMenuExpanded = false; onScanPorts() })
                DropdownMenuItem(text = { Text("Ask AI") }, onClick = { actionsMenuExpanded = false; onAskAi() })
                DropdownMenuItem(text = { Text("Check Shodan") }, onClick = { actionsMenuExpanded = false; onCheckShodan() })
                DropdownMenuItem(text = { Text("Check NVD") }, onClick = { actionsMenuExpanded = false; onCheckNvd() })
                DropdownMenuItem(text = { Text("Check SNMP") }, onClick = { actionsMenuExpanded = false; onCheckSnmp() })
                DropdownMenuItem(text = { Text("SNMP Walk (interfaces)") }, onClick = { actionsMenuExpanded = false; onCheckSnmpWalk() })
                DropdownMenuItem(text = { Text("Try Common Community Strings") }, onClick = { actionsMenuExpanded = false; onCheckSnmpBruteForce() })
                DropdownMenuItem(text = { Text("Check Common Exposures") }, onClick = { actionsMenuExpanded = false; onCheckExposures() })
                DropdownMenuItem(text = { Text("Try Default Credentials (Tomcat)") }, onClick = { actionsMenuExpanded = false; onCheckDefaultCreds() })
                DropdownMenuItem(
                    text = { Text("Wake on LAN") },
                    enabled = device.macAddress != null,
                    onClick = { actionsMenuExpanded = false; onWakeOnLan() },
                )
                DropdownMenuItem(text = { Text("Check Security") }, onClick = { actionsMenuExpanded = false; onCheckSecurity() })
                DropdownMenuItem(text = { Text("Get Fix Steps") }, onClick = { actionsMenuExpanded = false; onGetFixSteps() })
                DropdownMenuItem(text = { Text("Open in Terminal") }, onClick = { actionsMenuExpanded = false; onOpenTerminal() })
            }
        }

        ActionResultSection(label = "Ports", result = actions.ports) { ports ->
            if (ports.isEmpty()) {
                Text("No open ports found in the scanned range.", style = MaterialTheme.typography.bodySmall)
            } else {
                ports.forEach { port -> Text(portLine(port), style = MaterialTheme.typography.bodySmall) }
            }
        }

        ActionResultSection(label = "AI", result = actions.aiResponse) { text ->
            Column {
                Text(text, style = MaterialTheme.typography.bodySmall)
                Text(
                    "AI-generated, best-effort -- not verified against your device's exact firmware or menus.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        ActionResultSection(label = "Shodan", result = actions.shodanFindings) { findings ->
            if (findings.isEmpty()) {
                Text(
                    "No Shodan record for this IP -- expected for most LAN devices unless directly internet-exposed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                findings.forEach { finding -> Text("• ${finding.summary}", style = MaterialTheme.typography.bodySmall) }
            }
        }

        ActionResultSection(label = "NVD", result = actions.nvdFindings) { findings ->
            if (findings.isEmpty()) {
                Text(
                    "No CVEs matched this vendor/service in NVD's keyword search.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Column {
                    findings.sortedByDescending { it.epssScore ?: -1.0 }.forEach { finding ->
                        val epssText = finding.epssScore?.let { " · EPSS %.1f%%".format(it * 100) } ?: ""
                        Text(
                            "• ${finding.cveId}" + (finding.severity?.let { " ($it)" } ?: "") + epssText + " — ${finding.summary}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        "Keyword match on vendor/service, not a confirmed match to this exact device's firmware. EPSS is first.org's probability of real-world exploitation in the next 30 days -- independent of CVSS severity, and sorted highest-first here.",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }

        ActionResultSection(label = "SNMP", result = actions.snmpInfo) { info ->
            Column {
                info.sysName?.let { Text("sysName: $it", style = MaterialTheme.typography.bodySmall) }
                info.sysDescr?.let { Text("sysDescr: $it", style = MaterialTheme.typography.bodySmall) }
                if (info.sysName == null && info.sysDescr == null) {
                    Text("Device responded but returned no name/description.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        ActionResultSection(label = "SNMP Walk", result = actions.snmpWalk) { values ->
            Column {
                values.forEach { (oid, value) -> Text("${oid.removePrefix(SnmpClient.IF_DESCR_OID + ".")}: $value", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "GetBulk walk of the interfaces table (ifDescr) -- devices without SNMP or without this table return nothing.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        ActionResultSection(label = "SNMP Community Strings", result = actions.snmpBruteForce) { (community, info) ->
            Column {
                Text("Found working community string: \"$community\"", style = MaterialTheme.typography.bodySmall)
                info.sysName?.let { Text("sysName: $it", style = MaterialTheme.typography.bodySmall) }
                info.sysDescr?.let { Text("sysDescr: $it", style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Tried the bundled SecLists common-community-string list -- on-demand only, never part of the automatic scan.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        ActionResultSection(label = "Exposures", result = actions.exposures) { findings ->
            if (findings.isEmpty()) {
                Text("None of the 16 checked common exposures were found.", style = MaterialTheme.typography.bodySmall)
            } else {
                Column {
                    findings.forEach { finding -> Text("⚠ ${finding.path} — ${finding.name}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }

        ActionResultSection(label = "Default Credentials (Tomcat)", result = actions.defaultCreds) { hit ->
            Text("⚠ ${hit.product} accepted ${hit.username.ifEmpty { "<blank>" }} / ${hit.password.ifEmpty { "<blank>" }}", style = MaterialTheme.typography.bodySmall)
        }

        ActionResultSection(label = "Wake on LAN", result = actions.wakeOnLan) { message ->
            Text(message, style = MaterialTheme.typography.bodySmall)
        }

        ActionResultSection(label = "Security", result = actions.securityCheck) { result -> SecurityCheckSummary(result) }
    }
}

@Composable
private fun SecurityCheckSummary(result: SecurityCheckResult) {
    Column {
        Text("${result.url} — HTTP ${result.statusCode}", style = MaterialTheme.typography.bodySmall)

        if (result.presentHeaders.isEmpty()) {
            Text("No security headers present.", style = MaterialTheme.typography.bodySmall)
        } else {
            result.presentHeaders.forEach { (name, value) ->
                Text("✓ $name: $value", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (result.missingHeaders.isNotEmpty()) {
            Text("Missing: ${result.missingHeaders.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }

        result.tls?.let { tls ->
            Text("TLS subject: ${tls.subject}", style = MaterialTheme.typography.bodySmall)
            Text("TLS issuer: ${tls.issuer}", style = MaterialTheme.typography.bodySmall)
            Text(
                "Expires ${tls.notAfter}" + (if (tls.expired) " (EXPIRED)" else "") + (if (tls.selfSigned) " · self-signed" else ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (tls.protocol != null || tls.cipherSuite != null) {
                Text(
                    listOfNotNull(tls.protocol, tls.cipherSuite).joinToString(" · ") +
                        (if (tls.weakCipherOrProtocol) " ⚠ weak" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        if (result.detectedTech.isNotEmpty()) {
            Text("Detected: ${result.detectedTech.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun <T> ActionResultSection(label: String, result: ActionResult<T>, content: @Composable (T) -> Unit) {
    when (result) {
        is ActionResult.Idle -> Unit
        is ActionResult.Loading -> {
            Row(modifier = Modifier.padding(top = 8.dp)) {
                CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                Text("$label loading...", style = MaterialTheme.typography.bodySmall)
            }
        }
        is ActionResult.Error -> {
            Text("$label error: ${result.message}", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }
        is ActionResult.Success -> {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                Text(label, style = MaterialTheme.typography.labelMedium)
                content(result.value)
            }
        }
    }
}

private fun portLine(port: Port): String =
    "${port.number}/${port.protocol}" + (port.serviceName?.let { " ($it)" } ?: "")

private fun deviceTypeLabel(type: DeviceType): String = when (type) {
    DeviceType.ROUTER -> "📡"
    DeviceType.CAMERA -> "📹"
    DeviceType.PRINTER -> "🖨"
    DeviceType.MOBILE -> "📱"
    DeviceType.COMPUTER -> "💻"
    DeviceType.TV_OR_MEDIA -> "📺"
    DeviceType.SMART_HOME -> "🏠"
    DeviceType.GAME_CONSOLE -> "🎮"
    DeviceType.UNKNOWN -> "❓"
}
