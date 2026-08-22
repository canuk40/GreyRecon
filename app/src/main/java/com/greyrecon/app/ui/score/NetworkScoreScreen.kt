package com.greyrecon.app.ui.score

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.score.NetworkScore
import com.greyrecon.app.engine.score.NetworkScoreResult
import com.greyrecon.app.ui.main.DeviceActions

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScoreScreen(devices: List<Device>, deviceActions: Map<String, DeviceActions>, onBack: () -> Unit) {
    val result = NetworkScore.compute(devices, deviceActions)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Security Score") },
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
                Text(
                    "Run a scan first -- the score is built from the devices it finds.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            item { ScoreHeader(result) }
            item { CoverageSummary(result) }
            if (result.findings.isNotEmpty()) {
                item {
                    Text(
                        "What's dragging the score down",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp),
                    )
                }
                items(result.findings) { finding ->
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text(
                            "${finding.label} (-${finding.pointsDeducted})",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text("${finding.ipAddress} — ${finding.detail}", style = MaterialTheme.typography.bodySmall)
                    }
                    HorizontalDivider()
                }
            } else {
                item {
                    Text(
                        "No issues found among what's been checked so far.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScoreHeader(result: NetworkScoreResult) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "${result.score}",
            fontSize = 64.sp,
            style = MaterialTheme.typography.displayLarge,
            color = gradeColor(result.grade),
        )
        Text(
            "Grade ${result.grade}",
            style = MaterialTheme.typography.titleLarge,
            color = gradeColor(result.grade),
        )
    }
}

@Composable
private fun CoverageSummary(result: NetworkScoreResult) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            "${result.totalDevices} devices found · " +
                "${result.devicesPortScanned} port-scanned · " +
                "${result.devicesShodanChecked} Shodan-checked · " +
                "${result.devicesSecurityChecked} security-checked · " +
                "${result.devicesNvdChecked} NVD-checked",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "A high score reflects nothing bad found in what's been checked -- it isn't proof the rest of the network is clean. Deep-scan a device (Scan Ports / Check Shodan / Check Security from the device list) to fold it into this score.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

private fun gradeColor(grade: String): Color = when (grade) {
    "A" -> Color(0xFF4CAF50)
    "B" -> Color(0xFF8BC34A)
    "C" -> Color(0xFFFFC107)
    "D" -> Color(0xFFFF9800)
    else -> Color(0xFFF44336)
}
