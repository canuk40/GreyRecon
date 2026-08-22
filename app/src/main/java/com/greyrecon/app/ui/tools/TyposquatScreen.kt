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
import com.greyrecon.app.engine.tools.TyposquatChecker
import com.greyrecon.app.engine.tools.TyposquatCandidate
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TyposquatScreen(onBack: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<TyposquatCandidate>?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Typosquat Check") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                "Generates common typo/lookalike variants of a domain (character omission, transposition, lookalike substitution, TLD swap) and checks which are actually registered by someone else.",
                style = MaterialTheme.typography.bodySmall,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Domain, e.g. example.com") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )

            Button(
                onClick = {
                    if (query.isBlank()) return@Button
                    loading = true
                    error = null
                    results = null
                    scope.launch {
                        TyposquatChecker.check(query.trim()).fold(
                            onSuccess = { results = it },
                            onFailure = { e -> error = e.message ?: "Typosquat check failed" },
                        )
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Check") }

            if (loading) CircularProgressIndicator(modifier = Modifier.padding(top = 16.dp))
            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp)) }

            results?.let { candidates ->
                val registered = candidates.filter { it.registered }
                Text(
                    "${registered.size} of ${candidates.size} variants are registered",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp),
                )
                candidates.sortedByDescending { it.registered }.forEach { candidate ->
                    Text(
                        (if (candidate.registered) "⚠ " else "  ") + candidate.domain + (if (candidate.registered) " -- registered" else " -- not registered"),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
