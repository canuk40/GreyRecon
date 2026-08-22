package com.greyrecon.app.history

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

/** "Did anything change on my network while I wasn't watching" -- a real diff feed, not just a device list with timestamps. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkTimelineScreen(onBack: () -> Unit, viewModel: HistoryViewModel = viewModel()) {
    val events by viewModel.events.collectAsState()
    val records by viewModel.history.collectAsState()
    val labelById = records.associateBy { it.id }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Network Timeline") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        }
    ) { padding ->
        if (events.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No events yet -- events start appearing after your second scan, once there's a prior state to compare against.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(events, key = { it.id }) { event ->
                val deviceLabel = labelById[event.deviceId]?.let { it.customName ?: it.vendor ?: it.lastKnownIp } ?: event.deviceId
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(
                        "${eventIcon(event.type)} ${eventTitle(event.type)} — $deviceLabel",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(event.detail, style = MaterialTheme.typography.bodySmall)
                    Text(TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(event.timestamp)), style = MaterialTheme.typography.labelSmall)
                }
                HorizontalDivider()
            }
        }
    }
}

private fun eventIcon(type: String): String = when (type) {
    NetworkEvent.NEW_DEVICE -> "➕"
    NetworkEvent.WENT_OFFLINE -> "⚫"
    NetworkEvent.IP_CHANGED -> "🔁"
    NetworkEvent.RECLASSIFIED -> "🏷"
    else -> "•"
}

private fun eventTitle(type: String): String = when (type) {
    NetworkEvent.NEW_DEVICE -> "New device"
    NetworkEvent.WENT_OFFLINE -> "Went offline"
    NetworkEvent.IP_CHANGED -> "IP changed"
    NetworkEvent.RECLASSIFIED -> "Reclassified"
    else -> type
}
