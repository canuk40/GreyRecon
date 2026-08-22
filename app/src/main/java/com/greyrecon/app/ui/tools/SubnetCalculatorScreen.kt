package com.greyrecon.app.ui.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.greyrecon.app.engine.tools.SubnetCalculation
import com.greyrecon.app.engine.tools.SubnetCalculator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubnetCalculatorScreen(onBack: () -> Unit) {
    var ip by remember { mutableStateOf("192.168.1.0") }
    var prefix by remember { mutableStateOf("24") }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<SubnetCalculation?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Subnet Calculator") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("IP address") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = prefix,
                onValueChange = { prefix = it },
                label = { Text("Prefix length (CIDR)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                onClick = {
                    val prefixInt = prefix.toIntOrNull()
                    if (prefixInt == null) {
                        error = "Prefix must be a number 0-32"
                        result = null
                        return@Button
                    }
                    SubnetCalculator.calculate(ip, prefixInt).fold(
                        onSuccess = { calc -> error = null; result = calc },
                        onFailure = { e -> error = e.message; result = null },
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
            ) { Text("Calculate") }

            error?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 16.dp)) }

            result?.let { calc ->
                Column(modifier = Modifier.padding(top = 16.dp)) {
                    Text("Network address: ${calc.networkAddress}", style = MaterialTheme.typography.bodyMedium)
                    Text("Broadcast address: ${calc.broadcastAddress}", style = MaterialTheme.typography.bodyMedium)
                    Text("Netmask: ${calc.netmask} (/${calc.prefixLength})", style = MaterialTheme.typography.bodyMedium)
                    Text("First usable: ${calc.firstUsable}", style = MaterialTheme.typography.bodyMedium)
                    Text("Last usable: ${calc.lastUsable}", style = MaterialTheme.typography.bodyMedium)
                    Text("Usable hosts: ${calc.usableHostCount}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}
