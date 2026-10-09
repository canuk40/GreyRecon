package com.greyrecon.app.ui.tools

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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.greyrecon.app.R
import com.greyrecon.app.engine.discovery.SubnetInfo
import com.greyrecon.app.engine.quality.ConnectionQuality
import com.greyrecon.app.engine.quality.QualityResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Measures the gateway hop and the internet hop separately, which is the entire point.
 *
 * "My WiFi is bad" and "my internet is bad" are different problems with different fixes, and a
 * single speed-test number cannot tell them apart. Clean latency to the router with poor latency
 * beyond it means the WiFi is fine and the line is not; poor latency to the router means no
 * amount of shouting at the ISP will help.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionQualityScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var running by remember { mutableStateOf(false) }
    var gateway by remember { mutableStateOf<QualityResult?>(null) }
    var internet by remember { mutableStateOf<QualityResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run() {
        if (running) return
        running = true
        error = null
        gateway = null
        internet = null
        scope.launch {
            val subnet = withContext(Dispatchers.IO) { SubnetInfo.fromCurrentConnection(context) }
            val gatewayIp = subnet?.gatewayAddress
            if (gatewayIp == null) error = "Not connected to a WiFi network with a default gateway."

            // Run both hops concurrently. Measured sequentially on a router that ignores ICMP,
            // this took over a minute of staring at "Measuring..." -- the ping timeout budget for
            // the silent hop was being spent before the internet hop even started.
            coroutineScope {
                val gatewayJob = gatewayIp?.let { ip -> async { ConnectionQuality.measure(ip) } }
                val internetJob = async { ConnectionQuality.measure(INTERNET_TARGET) }
                gateway = gatewayJob?.await()
                internet = internetJob.await()
            }
            running = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection Quality") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())
        ) {
            Text(
                "Measures latency, jitter and packet loss rather than download speed. Jitter and loss are what actually ruin calls, games and video; download speed is the number your ISP already advertises, and testing it would burn your data to tell you something you know.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(onClick = { run() }, enabled = !running, modifier = Modifier.padding(top = 16.dp)) {
                Text(if (running) "Measuring…" else "Run test")
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
            }

            gateway?.let {
                ResultBlock("Your router", "The WiFi hop. Bad numbers here are a local problem.", it)
            }
            internet?.let {
                ResultBlock("The internet", "Beyond your router. Bad here with a clean router means the line, not the WiFi.", it)
            }
        }
    }
}

@Composable
private fun ResultBlock(title: String, subtitle: String, result: QualityResult) {
    HorizontalDivider(modifier = Modifier.padding(top = 20.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(result.grade, style = MaterialTheme.typography.titleLarge)
    }
    Text(subtitle, style = MaterialTheme.typography.bodySmall)
    Text(result.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))

    if (result.noResponse) {
        Text(
            "Not counted as a failure: silence from a device that filters probes says nothing about the quality of the link to it.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (result.received > 0) {
        Text(
            "min %.0f ms · avg %.0f ms · max %.0f ms · %d/%d replies".format(
                result.minMs, result.avgMs, result.maxMs, result.received, result.sent
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    Text(
        when (result.method) {
            QualityResult.Method.ICMP -> "Measured with ICMP ping against ${result.target}."
            QualityResult.Method.TCP ->
                if (result.noResponse) {
                    "${result.target} answered neither ping nor a TCP probe on 53, 443, 80 or 8080."
                } else {
                    "ICMP was unavailable or blocked, so this used TCP connect timing against ${result.target}. Loss here means unreachable rather than dropped."
                }
        },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

private const val INTERNET_TARGET = "1.1.1.1"
