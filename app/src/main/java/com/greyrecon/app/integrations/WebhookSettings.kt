package com.greyrecon.app.integrations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Pro-only webhook configuration, with a real test post rather than a "saved!" toast. */
@Composable
fun WebhookSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notifier = remember { WebhookNotifier(context) }

    var url by remember { mutableStateOf(notifier.url.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }

    val acceptable = url.isBlank() || notifier.isAcceptable(url)

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text("Event webhook", style = MaterialTheme.typography.bodyMedium)
        Text(
            "POST a JSON event to your own endpoint when Network Watch finds a new device. Reaches Home Assistant, n8n, ntfy, a Discord relay, or anything else you already run.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )

        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                status = null
            },
            label = { Text("https://…") },
            singleLine = true,
            isError = !acceptable,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )

        if (!acceptable) {
            Text(
                "Must be an https:// URL. Plaintext is refused rather than warned about -- this payload describes devices on your network and must not travel in the clear.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Row(modifier = Modifier.padding(top = 4.dp)) {
            TextButton(
                enabled = acceptable,
                onClick = {
                    notifier.url = url
                    status = if (url.isBlank()) "Webhook cleared." else "Saved."
                },
            ) { Text("Save") }

            TextButton(
                enabled = acceptable && url.isNotBlank(),
                onClick = {
                    notifier.url = url
                    status = "Sending…"
                    scope.launch {
                        val code = notifier.post(
                            event = "test",
                            networkLabel = "Test",
                            detail = "Test event from GreyRecon",
                        )
                        status = when {
                            code == null -> "No response -- check the URL is reachable."
                            code in 200..299 -> "Endpoint responded $code."
                            else -> "Endpoint responded $code."
                        }
                    }
                },
            ) { Text("Send test") }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodySmall)
        }

        Text(
            "Only the event type, network name, and the triggering device's IP and label are sent -- never your full device inventory.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
