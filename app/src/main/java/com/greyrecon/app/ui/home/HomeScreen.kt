package com.greyrecon.app.ui.home

import androidx.compose.ui.res.stringResource
import com.greyrecon.app.R

import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.filled.SmartToy
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
    var upsellFeature by remember { mutableStateOf<Int?>(null) }

    upsellFeature?.let { feature ->
        AlertDialog(
            onDismissRequest = { upsellFeature = null },
            title = { Text(stringResource(R.string.greyrecon_pro)) },
            text = { Text(stringResource(R.string.upsell_body, stringResource(feature))) },
            confirmButton = {
                TextButton(onClick = { upsellFeature = null; onNavigate("settings") }) { Text(stringResource(R.string.view_pro)) }
            },
            dismissButton = {
                TextButton(onClick = { upsellFeature = null }) { Text(stringResource(R.string.not_now)) }
            },
        )
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
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
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(32.dp))

            HomeMenuItem(
                icon = Icons.Filled.Wifi,
                label = stringResource(R.string.scan_network),
                description = stringResource(R.string.home_scan_desc),
                onClick = { onNavigate("scan") },
            )
            HomeMenuItem(
                icon = Icons.Filled.History,
                label = stringResource(R.string.device_history),
                description = stringResource(R.string.home_history_desc),
                locked = !isPro,
                onClick = { if (isPro) onNavigate("history") else upsellFeature = R.string.device_history },
            )
            HomeMenuItem(
                icon = Icons.Filled.Build,
                label = stringResource(R.string.tools),
                description = stringResource(R.string.home_tools_desc),
                locked = !isPro,
                onClick = { if (isPro) onNavigate("tools") else upsellFeature = R.string.tools },
            )
            HomeMenuItem(
                icon = Icons.Filled.Settings,
                label = stringResource(R.string.settings),
                description = stringResource(R.string.home_settings_desc),
                onClick = { onNavigate("settings") },
            )
            HomeMenuItem(
                icon = Icons.Filled.Terminal,
                label = stringResource(R.string.terminal),
                description = stringResource(R.string.home_terminal_desc),
                locked = !isPro,
                onClick = { if (isPro) onNavigate("terminal") else upsellFeature = R.string.terminal },
            )
            HomeMenuItem(
                icon = Icons.Filled.SmartToy,
                label = stringResource(R.string.ai_assistant),
                description = stringResource(R.string.home_agent_desc),
                locked = !isPro,
                onClick = { if (isPro) onNavigate("agent") else upsellFeature = R.string.ai_assistant },
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
                    contentDescription = stringResource(R.string.requires_pro),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
