package com.greyrecon.app.ui.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.greyrecon.app.R

/**
 * The app's actual landing screen -- branding + a menu of feature entry points,
 * instead of dropping straight into Scan Network as if that were the only
 * thing GreyRecon does. Add a new HomeMenuItem here whenever a new top-level
 * feature screen is built, rather than bolting another icon onto some other
 * screen's TopAppBar.
 *
 * Free tier is Scan Network alone (including everything reachable from within it -- export,
 * Security Score, Topology, and every per-device action). Device History, Tools, and Terminal
 * are Pro-gated: a locked card opens an upsell dialog instead of navigating, rather than
 * silently disappearing (a real user should see what Pro unlocks, not just find it missing).
 */
@Composable
fun HomeScreen(isPro: Boolean, onNavigate: (String) -> Unit) {
    var upsellFeature by remember { mutableStateOf<String?>(null) }

    upsellFeature?.let { feature ->
        AlertDialog(
            onDismissRequest = { upsellFeature = null },
            title = { Text("GreyRecon Pro") },
            text = { Text("$feature is a Pro feature. Scan Network stays free -- Device History, Tools, and Terminal unlock with GreyRecon Pro.") },
            confirmButton = {
                TextButton(onClick = { upsellFeature = null; onNavigate("settings") }) { Text("View Pro") }
            },
            dismissButton = {
                TextButton(onClick = { upsellFeature = null }) { Text("Not now") }
            },
        )
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // painterResource() can't load the launcher mipmap directly -- it's an adaptive-icon
            // XML wrapper (foreground+background), and painterResource only supports plain
            // VectorDrawable/raster assets (confirmed via a real crash on-device). Use the
            // foreground vector layer on its own instead.
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.size(88.dp).padding(top = 16.dp),
            )
            Text(
                "GreyRecon",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Network recon & security toolkit",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            HomeMenuItem(
                icon = Icons.Filled.Wifi,
                label = "Scan Network",
                description = "Discover and classify devices on this WiFi network",
                onClick = { onNavigate("scan") },
            )
            HomeMenuItem(
                icon = Icons.Filled.History,
                label = "Device History",
                description = "Devices seen over time, custom names, new-device alerts",
                locked = !isPro,
                onClick = { if (isPro) onNavigate("history") else upsellFeature = "Device History" },
            )
            HomeMenuItem(
                icon = Icons.Filled.Build,
                label = "Tools",
                description = "Subnet calculator, DNS lookup, WHOIS lookup",
                locked = !isPro,
                onClick = { if (isPro) onNavigate("tools") else upsellFeature = "Tools" },
            )
            HomeMenuItem(
                icon = Icons.Filled.Settings,
                label = "Settings",
                description = "API keys, AI provider, GreyRecon Pro",
                onClick = { onNavigate("settings") },
            )
            HomeMenuItem(
                icon = Icons.Filled.Terminal,
                label = "Terminal",
                description = "Real interactive shell -- run your own CLI tools directly",
                locked = !isPro,
                onClick = { if (isPro) onNavigate("terminal") else upsellFeature = "Terminal" },
            )
        }
    }
}

@Composable
private fun HomeMenuItem(icon: ImageVector, label: String, description: String, locked: Boolean = false, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Column(modifier = Modifier.padding(start = 16.dp).weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
            if (locked) {
                Icon(
                    Icons.Filled.Lock,
                    contentDescription = "Requires GreyRecon Pro",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
