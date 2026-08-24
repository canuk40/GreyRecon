package com.greyrecon.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.LocalActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.greyrecon.app.ai.AIProviderType
import com.greyrecon.app.billing.BillingManager
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.engine.discovery.SubnetInfo
import com.greyrecon.app.mcp.McpService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(keyStore: SecureKeyStore, billingManager: BillingManager, onBack: () -> Unit) {
    var deepseekKey by remember { mutableStateOf(keyStore.deepseekKey.orEmpty()) }
    var anthropicKey by remember { mutableStateOf(keyStore.anthropicKey.orEmpty()) }
    var shodanKey by remember { mutableStateOf(keyStore.shodanKey.orEmpty()) }
    var nvdKey by remember { mutableStateOf(keyStore.nvdKey.orEmpty()) }
    var greynoiseKey by remember { mutableStateOf(keyStore.greynoiseKey.orEmpty()) }
    var abuseipdbKey by remember { mutableStateOf(keyStore.abuseipdbKey.orEmpty()) }
    var aiProvider by remember { mutableStateOf(keyStore.aiProvider) }
    var savedMessage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val activity = LocalActivity.current
    val isPro by billingManager.isPro.collectAsState()
    var mcpEnabled by remember { mutableStateOf(keyStore.mcpEnabled) }
    var purchaseError by remember { mutableStateOf<String?>(null) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            mcpEnabled = true
            keyStore.mcpEnabled = true
            context.startForegroundService(Intent(context, McpService::class.java))
        }
    }

    LaunchedEffect(Unit) { billingManager.start() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                "API keys are stored on-device only, encrypted via Android Keystore. GreyRecon never sees or forwards your usage.",
                style = MaterialTheme.typography.bodySmall,
            )

            Text(
                "Ask AI uses",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 24.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                FilterChip(
                    selected = aiProvider == AIProviderType.DEEPSEEK,
                    onClick = { aiProvider = AIProviderType.DEEPSEEK; savedMessage = null },
                    label = { Text("DeepSeek") },
                )
                FilterChip(
                    selected = aiProvider == AIProviderType.ANTHROPIC,
                    onClick = { aiProvider = AIProviderType.ANTHROPIC; savedMessage = null },
                    label = { Text("Anthropic (Claude)") },
                )
            }

            OutlinedTextField(
                value = deepseekKey,
                onValueChange = { deepseekKey = it; savedMessage = null },
                label = { Text("DeepSeek API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            OutlinedTextField(
                value = anthropicKey,
                onValueChange = { anthropicKey = it; savedMessage = null },
                label = { Text("Anthropic API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            OutlinedTextField(
                value = shodanKey,
                onValueChange = { shodanKey = it; savedMessage = null },
                label = { Text("Shodan API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            OutlinedTextField(
                value = nvdKey,
                onValueChange = { nvdKey = it; savedMessage = null },
                label = { Text("NVD API key (optional -- works without one, just at a lower rate limit)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            OutlinedTextField(
                value = greynoiseKey,
                onValueChange = { greynoiseKey = it; savedMessage = null },
                label = { Text("GreyNoise API key (optional -- raises the daily IP-reputation lookup limit)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            OutlinedTextField(
                value = abuseipdbKey,
                onValueChange = { abuseipdbKey = it; savedMessage = null },
                label = { Text("AbuseIPDB API key (free -- required for IP abuse-reputation checks)") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            )

            TextButton(
                onClick = {
                    keyStore.deepseekKey = deepseekKey.takeIf { it.isNotBlank() }
                    keyStore.anthropicKey = anthropicKey.takeIf { it.isNotBlank() }
                    keyStore.shodanKey = shodanKey.takeIf { it.isNotBlank() }
                    keyStore.nvdKey = nvdKey.takeIf { it.isNotBlank() }
                    keyStore.greynoiseKey = greynoiseKey.takeIf { it.isNotBlank() }
                    keyStore.abuseipdbKey = abuseipdbKey.takeIf { it.isNotBlank() }
                    keyStore.aiProvider = aiProvider
                    savedMessage = "Saved."
                },
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Text("Save")
            }

            savedMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            HorizontalDivider(modifier = Modifier.padding(top = 24.dp))

            Text(
                "GreyRecon Pro",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 16.dp),
            )

            if (!isPro) {
                Text(
                    "Unlock Device History, Tools (subnet calculator, DNS/WHOIS lookup, CT monitoring, typosquat check, BLE scan), the Terminal, the AI Assistant (chat with an AI that runs real scans using your own API key), and the MCP server -- expose scan results as tools your own self-hosted nanobot instance (or any MCP client) can query conversationally. Scan Network stays free.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = {
                        purchaseError = null
                        val act = activity
                        if (act != null) {
                            billingManager.launchPurchase(act) { message -> purchaseError = message }
                        }
                    },
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text("Upgrade to Pro")
                }
                purchaseError?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text("MCP server", style = MaterialTheme.typography.bodyMedium)
                    Switch(
                        checked = mcpEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                                if (needsPermission) {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                } else {
                                    mcpEnabled = true
                                    keyStore.mcpEnabled = true
                                    context.startForegroundService(Intent(context, McpService::class.java))
                                }
                            } else {
                                mcpEnabled = false
                                keyStore.mcpEnabled = false
                                context.stopService(Intent(context, McpService::class.java))
                            }
                        },
                    )
                }
                if (mcpEnabled) {
                    val subnet = remember { SubnetInfo.fromCurrentConnection(context) }
                    val token = remember { keyStore.mcpAuthToken ?: SecureKeyStore.generateToken().also { keyStore.mcpAuthToken = it } }
                    Text(
                        "Address: ${subnet?.localIpAddress ?: "?"}:${McpService.PORT}/mcp\nToken: $token",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "Configure your nanobot instance with this address and an Authorization: Bearer <token> header. Anyone with this token on your network can trigger scans -- keep it private.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}
