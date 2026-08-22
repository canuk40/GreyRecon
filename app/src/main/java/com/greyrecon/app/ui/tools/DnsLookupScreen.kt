package com.greyrecon.app.ui.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greyrecon.app.engine.tools.DnsLookup
import com.greyrecon.app.engine.tools.DnsRecord
import kotlinx.coroutines.launch

private val RECORD_TYPES = listOf("A", "AAAA", "MX", "TXT", "NS", "CNAME")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsLookupScreen(onBack: () -> Unit) {
    var domain by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("A") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var records by remember { mutableStateOf<List<DnsRecord>?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("DNS Lookup") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = domain,
                onValueChange = { domain = it },
                label = { Text("Domain") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                RECORD_TYPES.forEach { t ->
                    FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) })
                }
            }

            Button(
                onClick = {
                    if (domain.isBlank()) return@Button
                    loading = true
                    error = null
                    records = null
                    scope.launch {
                        DnsLookup.lookup(domain.trim(), type).fold(
                            onSuccess = { records = it },
                            onFailure = { e -> error = e.message ?: "DNS lookup failed" },
                        )
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Lookup") }

            if (loading) CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp)) }

            records?.let { list ->
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    if (list.isEmpty()) {
                        Text("No $type records found for $domain.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        list.forEach { record ->
                            Text(
                                "${record.type}  ${record.data}  (TTL ${record.ttl}s)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}
