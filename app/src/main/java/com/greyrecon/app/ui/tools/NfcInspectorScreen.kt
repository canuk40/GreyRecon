package com.greyrecon.app.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.greyrecon.app.engine.nfc.NfcTagBus
import com.greyrecon.app.engine.nfc.NfcTagInfo
import com.greyrecon.app.engine.nfc.NfcTagReader

/**
 * NFC tag inspector: NDEF text/URI records, tag technologies, and a best-effort Mifare Classic
 * sector-0 read if (and only if) the tag still uses the factory-default key -- see
 * [NfcTagReader]'s doc comment for what this deliberately does *not* attempt (key recovery/cracking,
 * emulation/cloning) and why those are real Android platform walls, not missing effort.
 *
 * Foreground dispatch itself is owned by MainActivity (Android's NFC API only delivers discovered
 * tags via `onNewIntent` on the hosting Activity) -- this screen just registers as the current
 * listener on [NfcTagBus] while visible.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcInspectorScreen(onBack: () -> Unit) {
    var lastTag by remember { mutableStateOf<NfcTagInfo?>(null) }

    DisposableEffect(Unit) {
        NfcTagBus.onTagDiscovered = { tag -> lastTag = NfcTagReader.read(tag) }
        onDispose { NfcTagBus.onTagDiscovered = null }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("NFC Inspector") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                "Reads NFC tags via Android's own APIs -- NDEF text/URI records, tag technologies, and (only where the tag still uses its factory-default key) a Mifare Classic sector-0 dump. Doesn't attempt key recovery/cloning/emulation -- Android's NFC controller genuinely can't do those.",
                style = MaterialTheme.typography.bodySmall,
            )

            val tag = lastTag
            if (tag == null) {
                Text(
                    "Hold the back of your phone near an NFC tag...",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 24.dp),
                )
            } else {
                Text("UID: ${tag.uid}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "Technologies: ${tag.technologies.joinToString(", ")}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )

                if (tag.ndefRecords.isNotEmpty()) {
                    Text("NDEF records", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp))
                    tag.ndefRecords.forEach { record ->
                        Text(
                            "[${record.type}] ${record.text}",
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } else {
                    Text("No NDEF message on this tag.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp))
                }

                tag.mifareClassicSector0Dump?.let { dump ->
                    Text(
                        "Mifare Classic sector 0 (read with factory-default key)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                    dump.forEachIndexed { i, block ->
                        Text("block $i: $block", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
