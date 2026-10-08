package com.greyrecon.app.ui.main

import androidx.compose.ui.res.stringResource
import com.greyrecon.app.R

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.greyrecon.app.ads.BannerAdBar
import com.greyrecon.app.ads.InterstitialAdManager
import com.greyrecon.app.ai.AIProviderConfig
import com.greyrecon.app.ai.AIProviderType
import com.greyrecon.app.billing.BillingManager
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.model.DeviceType
import com.greyrecon.app.engine.model.Port
import com.greyrecon.app.engine.model.ShodanFinding
import com.greyrecon.app.engine.nfc.NfcTagBus
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
import com.greyrecon.app.ui.tools.NfcInspectorScreen
import com.greyrecon.app.ui.tools.SubnetCalculatorScreen
import com.greyrecon.app.ui.tools.ToolsScreen
import com.greyrecon.app.ui.tools.WhoisLookupScreen

class MainActivity : ComponentActivity() {

    private val viewModel: ScanViewModel by viewModels()

    // Foreground dispatch is the only way Android delivers a discovered NFC tag while this
    // Activity is the one on screen -- must be enabled/disabled around onResume/onPause (a stale
    // registration left active after onPause can steal the tag-discovery intent from whatever the
    // system would otherwise hand it to next). android:launchMode="singleTop" in the manifest is
    // what makes the discovery arrive via onNewIntent() below on this same instance, rather than
    // spawning a second MainActivity.
    private val nfcPendingIntent: PendingIntent by lazy {
        val intent = Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        PendingIntent.getActivity(this, 0, intent, flags)
    }

    override fun onResume() {
        super.onResume()
        NfcAdapter.getDefaultAdapter(this)?.enableForegroundDispatch(this, nfcPendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(NfcAdapter.EXTRA_TAG, Tag::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        }
        tag?.let { NfcTagBus.onTagDiscovered?.invoke(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val keyStore = SecureKeyStore(applicationContext)
        val billingManager = BillingManager(applicationContext)
        // Ask Play for a review only after a scan that really found devices (see ReviewPrompter).
        lifecycleScope.launch {
            var previous: ScanState = ScanState.Idle
            viewModel.state.collect { current ->
                if (previous is ScanState.Scanning && current is ScanState.Done && current.devices.isNotEmpty()) {
                    ReviewPrompter.onScanSucceeded(this@MainActivity)
                }
                previous = current
            }
        }
        billingManager.start() // top-level, not screen-scoped -- entitlement must be fresh on Home too, not just Settings
        setContent {
            GreyReconTheme {
                val navController = rememberNavController()
                val isProForAds by billingManager.isPro.collectAsState()

                // Interstitial on screen transitions (Pro-gated and frequency-capped inside
                // InterstitialAdManager). Skips the very first composition so an ad never shows on
                // app launch itself - only on real navigation between screens.
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route
                var isFirstTransition by remember { mutableStateOf(true) }
                LaunchedEffect(currentRoute) {
                    if (isFirstTransition) {
                        isFirstTransition = false
                    } else {
                        InterstitialAdManager.maybeShowOnTransition(this@MainActivity, isProForAds)
                    }
                }

                // The banner is a real sibling BELOW the nav content rather than an overlay, so it
                // reserves its own layout space - scan results and other scrollable content can
                // never end up underneath it. NavHost takes the remaining height via weight(1f).
                // (Its body is left at the original indentation to keep this diff reviewable.)
                Column(modifier = Modifier.fillMaxSize()) {
                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.weight(1f),
                ) {
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
                    composable("tools/nfc") {
                        NfcInspectorScreen(onBack = { navController.popBackStack() })
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
                BannerAdBar(isPro = isProForAds)
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
                title = { Text(stringResource(R.string.scan_network)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Filled.Menu, contentDescription = stringResource(R.string.more_options))
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_csv)) },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                ExportShare.share(context, ScanExporter.toCsv(devicesSnapshot), "greyrecon_scan.csv", "text/csv")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_json)) },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                ExportShare.share(context, ScanExporter.toJson(devicesSnapshot), "greyrecon_scan.json", "application/json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_nmap_xml)) },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                ExportShare.share(context, NmapXmlExporter.toNmapXml(devicesSnapshot), "greyrecon_scan.xml", "text/xml")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.export_ocsf)) },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = {
                                menuExpanded = false
                                ExportShare.share(context, OcsfExporter.toOcsf(devicesSnapshot), "greyrecon_scan_ocsf.json", "application/json")
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.security_score)) },
                            enabled = devicesSnapshot.isNotEmpty(),
                            onClick = { menuExpanded = false; onOpenScore() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.topology)) },
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
                    Text(stringResource(R.string.scan_network))
                }

                val devices = when (val s = state) {
                    is ScanState.Idle -> {
                        Text(
                            text = stringResource(R.string.empty_tap_scan),
                            modifier = Modifier.padding(top = 24.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        emptyList()
                    }
                    is ScanState.NoWifiSubnet -> {
                        Text(
                            text = stringResource(R.string.not_connected_wifi),
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
                    onCheckExposures = { ip -> viewModel.checkExposures(ip, aiConfigFrom(keyStore)) },
                    onCheckDefaultCreds = { ip -> viewModel.checkDefaultCreds(ip, aiConfigFrom(keyStore)) },
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
            TextButton(onClick = { if (text.isNotBlank()) onSubmit(text) }) { Text(stringResource(R.string.use_key)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
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
                        // modelInfo comes from the device's own mDNS TXT record, so it beats a
                        // vendor name derived from the MAC prefix whenever it is present.
                        listOfNotNull(device.ipAddress, device.modelInfo ?: device.vendor).joinToString(" — "),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                val subtitle = listOfNotNull(
                    device.hostname,
                    device.macAddress,
                    // A randomised MAC has no OUI, so the vendor lookup finds nothing and the row
                    // would otherwise read as an anonymous blank. Saying why it is unidentifiable
                    // is more useful, and more accurate, than saying nothing.
                    if (device.hasRandomizedMac) "randomised MAC" else null,
                    device.discoveredBy.joinToString(", ") { it.name },
                ).joinToString(" · ")
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
                device.httpBanner?.let { banner ->
                    Text(banner, style = MaterialTheme.typography.bodySmall)
                }
                if (device.modelInfo != null && device.vendor != null) {
                    Text(device.vendor, style = MaterialTheme.typography.bodySmall)
                }

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
                Text(stringResource(R.string.actions))
            }
            DropdownMenu(expanded = actionsMenuExpanded, onDismissRequest = { actionsMenuExpanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.scan_ports)) }, onClick = { actionsMenuExpanded = false; onScanPorts() })
                DropdownMenuItem(text = { Text(stringResource(R.string.ask_ai)) }, onClick = { actionsMenuExpanded = false; onAskAi() })
                DropdownMenuItem(text = { Text(stringResource(R.string.check_shodan)) }, onClick = { actionsMenuExpanded = false; onCheckShodan() })
                DropdownMenuItem(text = { Text(stringResource(R.string.check_nvd)) }, onClick = { actionsMenuExpanded = false; onCheckNvd() })
                DropdownMenuItem(text = { Text(stringResource(R.string.check_snmp)) }, onClick = { actionsMenuExpanded = false; onCheckSnmp() })
                DropdownMenuItem(text = { Text(stringResource(R.string.snmp_walk)) }, onClick = { actionsMenuExpanded = false; onCheckSnmpWalk() })
                DropdownMenuItem(text = { Text(stringResource(R.string.try_common_strings)) }, onClick = { actionsMenuExpanded = false; onCheckSnmpBruteForce() })
                DropdownMenuItem(text = { Text(stringResource(R.string.check_common_exposures)) }, onClick = { actionsMenuExpanded = false; onCheckExposures() })
                DropdownMenuItem(text = { Text(stringResource(R.string.try_default_creds)) }, onClick = { actionsMenuExpanded = false; onCheckDefaultCreds() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.wake_on_lan)) },
                    enabled = device.macAddress != null,
                    onClick = { actionsMenuExpanded = false; onWakeOnLan() },
                )
                DropdownMenuItem(text = { Text(stringResource(R.string.check_security)) }, onClick = { actionsMenuExpanded = false; onCheckSecurity() })
                DropdownMenuItem(text = { Text(stringResource(R.string.get_fix_steps)) }, onClick = { actionsMenuExpanded = false; onGetFixSteps() })
                DropdownMenuItem(text = { Text(stringResource(R.string.open_in_terminal)) }, onClick = { actionsMenuExpanded = false; onOpenTerminal() })
            }
        }

        ActionResultSection(label = stringResource(R.string.result_ports), result = actions.ports) { ports ->
            if (ports.isEmpty()) {
                Text(stringResource(R.string.no_open_ports), style = MaterialTheme.typography.bodySmall)
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
                    Text(stringResource(R.string.device_no_name), style = MaterialTheme.typography.bodySmall)
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

        ActionResultSection(label = stringResource(R.string.result_snmp_community), result = actions.snmpBruteForce) { (community, info) ->
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

        ActionResultSection(label = stringResource(R.string.result_exposures), result = actions.exposures) { findings ->
            if (findings.isEmpty()) {
                Text(stringResource(R.string.no_common_exposures), style = MaterialTheme.typography.bodySmall)
            } else {
                Column {
                    findings.forEach { finding ->
                        val mark = if (finding.triage?.truePositive == false) "🤖" else "⚠"
                        Text("$mark ${finding.path} — ${finding.name}", style = MaterialTheme.typography.bodySmall)
                        finding.triage?.let { verdict ->
                            val label = if (verdict.truePositive) "AI triage: likely real" else "AI triage: likely false positive"
                            Text("$label — ${verdict.reason}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        ActionResultSection(label = stringResource(R.string.result_default_creds), result = actions.defaultCreds) { hit ->
            Column {
                Text("⚠ ${hit.product} accepted ${hit.username.ifEmpty { "<blank>" }} / ${hit.password.ifEmpty { "<blank>" }}", style = MaterialTheme.typography.bodySmall)
                hit.triage?.let { verdict ->
                    val label = if (verdict.truePositive) "AI triage: likely real" else "AI triage: likely false positive"
                    Text("$label — ${verdict.reason}", style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        ActionResultSection(label = stringResource(R.string.result_wake_on_lan), result = actions.wakeOnLan) { message ->
            Text(message, style = MaterialTheme.typography.bodySmall)
        }

        ActionResultSection(label = stringResource(R.string.result_security), result = actions.securityCheck) { result -> SecurityCheckSummary(result) }
    }
}

@Composable
private fun SecurityCheckSummary(result: SecurityCheckResult) {
    Column {
        Text("${result.url} — HTTP ${result.statusCode}", style = MaterialTheme.typography.bodySmall)

        if (result.presentHeaders.isEmpty()) {
            Text(stringResource(R.string.no_security_headers), style = MaterialTheme.typography.bodySmall)
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

/** Null when no key is configured for the selected provider -- callers treat that as "skip triage",
 * not an error, since [ScanViewModel.checkExposures]/[ScanViewModel.checkDefaultCreds] triage is opt-in. */
private fun aiConfigFrom(keyStore: SecureKeyStore): AIProviderConfig? {
    val provider = keyStore.aiProvider
    val key = when (provider) {
        AIProviderType.ANTHROPIC -> keyStore.anthropicKey
        else -> keyStore.deepseekKey
    } ?: return null
    val model = when (provider) {
        AIProviderType.ANTHROPIC -> "claude-sonnet-5"
        else -> "deepseek-chat"
    }
    return AIProviderConfig(type = provider, apiKey = key, model = model)
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
