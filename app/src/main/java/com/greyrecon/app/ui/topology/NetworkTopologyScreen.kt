package com.greyrecon.app.ui.topology

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.model.DeviceType
import com.greyrecon.app.engine.topology.ClusterNode
import com.greyrecon.app.engine.topology.RadialLayout
import kotlin.math.hypot

private const val NODE_RADIUS_PX = 70f
private const val GATEWAY_RADIUS_PX = 90f

/**
 * A logical star topology, not a physical wiring diagram -- GreyRecon can't see real Layer-2
 * structure without SNMP access to managed switches. Gateway at center, one node per device type
 * around it (not one node per device -- unreadable at 40+ devices), tap a node to see what's in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkTopologyScreen(devices: List<Device>, onBack: () -> Unit) {
    var selectedCluster by remember { mutableStateOf<ClusterNode?>(null) }
    var showGatewayInfo by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Topology") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
    ) { padding ->
        if (devices.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Run a scan first -- the map is built from the devices it finds.", style = MaterialTheme.typography.bodyMedium)
            }
            return@Scaffold
        }

        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            var layout by remember { mutableStateOf(RadialLayout.compute(devices, 1000f, 1000f)) }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(devices) {
                        detectTapGestures { tapOffset ->
                            layout.clusters.firstOrNull { hypot((it.x - tapOffset.x).toDouble(), (it.y - tapOffset.y).toDouble()) < NODE_RADIUS_PX }
                                ?.let { selectedCluster = it }
                            layout.gateway?.let {
                                if (hypot((layout.centerX - tapOffset.x).toDouble(), (layout.centerY - tapOffset.y).toDouble()) < GATEWAY_RADIUS_PX) {
                                    showGatewayInfo = true
                                }
                            }
                        }
                    }
            ) {
                layout = RadialLayout.compute(devices, size.width, size.height)

                layout.clusters.forEach { cluster ->
                    drawLine(
                        color = Color.Gray.copy(alpha = 0.5f),
                        start = Offset(layout.centerX, layout.centerY),
                        end = Offset(cluster.x, cluster.y),
                        strokeWidth = 3f,
                    )
                }

                layout.gateway?.let {
                    drawCircle(color = Color(0xFF4CAF50), radius = GATEWAY_RADIUS_PX, center = Offset(layout.centerX, layout.centerY))
                    drawContextLabel("Gateway", layout.centerX, layout.centerY)
                }

                layout.clusters.forEach { cluster ->
                    drawCircle(color = clusterColor(cluster.type), radius = NODE_RADIUS_PX, center = Offset(cluster.x, cluster.y))
                    drawContextLabel("${cluster.devices.size}", cluster.x, cluster.y - 10f, sizeSp = 22f)
                    drawContextLabel(clusterShortLabel(cluster.type), cluster.x, cluster.y + 20f, sizeSp = 12f)
                }
            }

            Text(
                "Logical map -- shows how devices group by type around your gateway, not real switch wiring. Tap a node.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(12.dp),
            )
        }

        selectedCluster?.let { cluster ->
            AlertDialog(
                onDismissRequest = { selectedCluster = null },
                title = { Text("${clusterShortLabel(cluster.type)} (${cluster.devices.size})") },
                text = {
                    LazyColumn {
                        items(cluster.devices) { device ->
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                Text(
                                    listOfNotNull(device.ipAddress, device.vendor).joinToString(" — "),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                device.hostname?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            }
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { selectedCluster = null }) { Text("Close") } },
            )
        }

        if (showGatewayInfo) {
            val gateway = devices.firstOrNull { it.isGateway }
            AlertDialog(
                onDismissRequest = { showGatewayInfo = false },
                title = { Text("Gateway") },
                text = {
                    Column {
                        Text(gateway?.ipAddress.orEmpty(), style = MaterialTheme.typography.bodyMedium)
                        gateway?.vendor?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        gateway?.macAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                },
                confirmButton = { TextButton(onClick = { showGatewayInfo = false }) { Text("Close") } },
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawContextLabel(text: String, x: Float, y: Float, sizeSp: Float = 16f) {
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.WHITE
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = sizeSp * density
            isFakeBoldText = true
        }
        drawText(text, x, y, paint)
    }
}

private fun clusterColor(type: DeviceType): Color = when (type) {
    DeviceType.ROUTER -> Color(0xFF4CAF50)
    DeviceType.CAMERA -> Color(0xFFF44336)
    DeviceType.PRINTER -> Color(0xFF9C27B0)
    DeviceType.MOBILE -> Color(0xFF2196F3)
    DeviceType.COMPUTER -> Color(0xFF3F51B5)
    DeviceType.TV_OR_MEDIA -> Color(0xFFFF9800)
    DeviceType.SMART_HOME -> Color(0xFF009688)
    DeviceType.GAME_CONSOLE -> Color(0xFF795548)
    DeviceType.UNKNOWN -> Color(0xFF607D8B)
}

private fun clusterShortLabel(type: DeviceType): String = when (type) {
    DeviceType.ROUTER -> "Router"
    DeviceType.CAMERA -> "Camera"
    DeviceType.PRINTER -> "Printer"
    DeviceType.MOBILE -> "Mobile"
    DeviceType.COMPUTER -> "Computer"
    DeviceType.TV_OR_MEDIA -> "TV/Media"
    DeviceType.SMART_HOME -> "Smart Home"
    DeviceType.GAME_CONSOLE -> "Console"
    DeviceType.UNKNOWN -> "Unknown"
}
