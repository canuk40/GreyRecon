package com.greyrecon.app.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.greyrecon.app.engine.tools.CtLogClient
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CtLogScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<CtLogClient.CtLogResult?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Certificate Transparency") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                "Searches public CT logs via crt.sh for every certificate ever issued for this domain -- reveals subdomains passively (no DNS bruteforcing) and flags newly issued certs you may not expect.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Domain") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Button(
                onClick = {
                    if (query.isBlank()) return@Button
                    loading = true
                    error = null
                    result = null
                    scope.launch {
                        CtLogClient.search(query.trim()).fold(
                            onSuccess = { result = it },
                            onFailure = { e -> error = e.message ?: "Certificate Transparency search failed" },
                        )
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Search") }

            if (loading) CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp)) }

            result?.let { r ->
                if (r.subdomains.isEmpty() && r.recentCerts.isEmpty()) {
                    Text("No certificates found for this domain in CT logs.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
                } else {
                    Text(
                        "${r.subdomains.size} unique hostnames seen",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    Text(
                        r.subdomains.joinToString("\n"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )

                    Text(
                        "Most recent certificates",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    r.recentCerts.forEach { cert ->
                        Text(
                            "${cert.entryTimestamp} · ${cert.commonName} · ${cert.issuerName}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
