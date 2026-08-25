package com.greyrecon.app.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(onBack: () -> Unit, onNavigate: (String) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tools") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            ToolMenuItem(
                icon = Icons.Filled.Calculate,
                label = "Subnet Calculator",
                description = "Network/broadcast address, usable host range, netmask",
                onClick = { onNavigate("tools/subnet") },
            )
            ToolMenuItem(
                icon = Icons.Filled.Dns,
                label = "DNS Lookup",
                description = "A, AAAA, MX, TXT, NS, CNAME records for a domain",
                onClick = { onNavigate("tools/dns") },
            )
            ToolMenuItem(
                icon = Icons.Filled.Search,
                label = "WHOIS Lookup",
                description = "Registration info for a domain or IP",
                onClick = { onNavigate("tools/whois") },
            )
            ToolMenuItem(
                icon = Icons.Filled.VerifiedUser,
                label = "Certificate Transparency",
                description = "Passive subdomain discovery + new-cert monitoring via CT logs",
                onClick = { onNavigate("tools/ctlog") },
            )
            ToolMenuItem(
                icon = Icons.Filled.Warning,
                label = "Typosquat Check",
                description = "Checks common typo/homograph variants of a domain for registration",
                onClick = { onNavigate("tools/typosquat") },
            )
            ToolMenuItem(
                icon = Icons.Filled.Bluetooth,
                label = "BLE Scan",
                description = "Nearby BLE devices, plus AirTag/Tile/SmartTag tracker and BLE-spam-attack detection",
                onClick = { onNavigate("tools/ble") },
            )
            ToolMenuItem(
                icon = Icons.Filled.Nfc,
                label = "NFC Inspector",
                description = "Read NDEF tags and Mifare Classic tags still on their factory-default key",
                onClick = { onNavigate("tools/nfc") },
            )
        }
    }
}

@Composable
private fun ToolMenuItem(icon: ImageVector, label: String, description: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.padding(start = 16.dp)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
