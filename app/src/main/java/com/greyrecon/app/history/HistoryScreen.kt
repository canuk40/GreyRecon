package com.greyrecon.app.history

import androidx.compose.ui.res.stringResource
import com.greyrecon.app.R

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onBack: () -> Unit, onOpenTimeline: () -> Unit, viewModel: HistoryViewModel = viewModel()) {
    val records by viewModel.history.collectAsState()
    val networks by viewModel.networks.collectAsState()
    val selectedKey by viewModel.selectedNetworkKey.collectAsState()
    val onThisNetwork by viewModel.onSelectedNetwork.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.device_history)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onOpenTimeline) { Text(stringResource(R.string.timeline)) }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

        if (networks.size > 1 || !onThisNetwork) {
            NetworkSelectorBar(
                networks = networks,
                selectedKey = selectedKey,
                onThisNetwork = onThisNetwork,
                onSelect = viewModel::selectNetwork,
            )
            HorizontalDivider()
        }

        if (records.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No device history yet -- run a scan first. Every device seen from then on is remembered here, and you'll get a notification when an unrecognized device joins the network.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(records, key = { it.id }) { record ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expandedId = if (expandedId == record.id) null else record.id }
                        .padding(16.dp),
                ) {
                    Text(
                        record.customName ?: record.vendor ?: record.hostname ?: record.lastKnownIp,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        "${record.lastKnownIp} · ${record.deviceType} · last seen ${TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(record.lastSeenAt))}",
                        style = MaterialTheme.typography.bodySmall,
                    )

                    if (expandedId == record.id) {
                        DeviceHistoryEditor(record = record, viewModel = viewModel)
                    }
                }
                HorizontalDivider()
            }
        }
        }
    }
}

/**
 * Lets the user switch between networks they have baselines for. Shown only when there is a choice
 * to make, or when the phone is not on the network being displayed -- otherwise it is noise.
 */
@Composable
private fun NetworkSelectorBar(
    networks: List<NetworkProfile>,
    selectedKey: String?,
    onThisNetwork: Boolean,
    onSelect: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val selected = networks.firstOrNull { it.networkKey == selectedKey }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistChip(
                onClick = { menuOpen = true },
                label = { Text(selected?.label ?: "Select network") },
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                networks.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.label) },
                        onClick = {
                            menuOpen = false
                            onSelect(profile.networkKey)
                        },
                    )
                }
            }
        }
        if (!onThisNetwork) {
            Text(
                "You are not connected to this network right now -- showing its last known state.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (selected?.isStrongKey == false) {
            Text(
                "Identified by gateway address rather than the router's MAC, so another network on the same address range could share this history.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DeviceHistoryEditor(record: DeviceRecord, viewModel: HistoryViewModel) {
    var name by remember(record.id) { mutableStateOf(record.customName.orEmpty()) }
    var notes by remember(record.id) { mutableStateOf(record.notes.orEmpty()) }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(
            "First seen ${TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(record.firstSeenAt))}" +
                (record.macAddress?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.custom_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        )
        OutlinedTextField(
            value = notes,
            onValueChange = { notes = it },
            label = { Text(stringResource(R.string.notes)) },
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            TextButton(onClick = {
                viewModel.setCustomName(record.id, name)
                viewModel.setNotes(record.id, notes)
            }) { Text(stringResource(R.string.save)) }
        }
    }
}
