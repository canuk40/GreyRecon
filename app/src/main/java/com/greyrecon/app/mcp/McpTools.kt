package com.greyrecon.app.mcp

import com.greyrecon.app.engine.model.Device
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** name -> one-line description of what that string argument means, in declaration order. */
data class ToolDef(val name: String, val description: String, val parameters: LinkedHashMap<String, String>, val required: List<String>)

/**
 * The tools GreyRecon exposes over MCP -- lets a user's own self-hosted
 * nanobot instance ask "what's on my network" and act on it conversationally,
 * instead of the BYOK free-tier path where the app itself calls an AI
 * provider. Same underlying scan engine either way (see [ScanDataSource]).
 *
 * Tool metadata is plain data here -- [GreyReconMcpServer] converts it to the SDK's `ToolSchema` and
 * registers it via `Server.addTool()`, which also generates the `tools/list` response itself. This
 * class no longer hand-builds any JSON-RPC shape.
 */
object McpTools {

    fun definitions(): List<ToolDef> = listOf(
        ToolDef(
            name = "scan_network",
            description = "Run a fresh discovery scan of the current WiFi network (ARP, mDNS, UPnP, active TCP sweep). Takes up to ~30s. Returns every device found.",
            parameters = linkedMapOf(),
            required = emptyList(),
        ),
        ToolDef(
            name = "list_devices",
            description = "Return the most recent scan's device list without running a new scan. Call scan_network first if nothing has been scanned yet.",
            parameters = linkedMapOf(),
            required = emptyList(),
        ),
        ToolDef(
            name = "scan_ports",
            description = "TCP port-scan a specific device found by a previous scan.",
            parameters = linkedMapOf("ip_address" to "The device's IP address, e.g. 192.168.1.42"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "check_shodan",
            description = "Look up a device's IP on Shodan for known CVEs/exposed services. Only useful for devices with a real public IP -- most LAN devices won't have a record. Requires a Shodan API key configured in GreyRecon's Settings.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to check"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "wake_on_lan",
            description = "Send a Wake-on-LAN magic packet to a device found by a previous scan, to power it on remotely. Only works if the device's MAC address is known and it supports WoL.",
            parameters = linkedMapOf("ip_address" to "The device's IP address, used to look up its MAC from the last scan"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "check_security",
            description = "Check a device's HTTP security headers and, over HTTPS, its TLS certificate details (issuer, expiry, self-signed status, protocol/cipher suite). Connects with certificate verification disabled so invalid/self-signed certs (common on LAN devices) can still be inspected.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to check"),
            required = listOf("ip_address"),
        ),
    )

    suspend fun call(name: String, arguments: JsonObject?, dataSource: ScanDataSource): Result<String> = runCatching {
        when (name) {
            "scan_network" -> formatDevices(dataSource.scanNetwork())
            "list_devices" -> {
                val devices = dataSource.lastDevices()
                if (devices.isEmpty()) "No devices scanned yet -- call scan_network first." else formatDevices(devices)
            }
            "scan_ports" -> {
                val ip = requireIp(arguments)
                val ports = dataSource.scanPorts(ip)
                if (ports.isEmpty()) "No open ports found on $ip in the scanned range." else
                    ports.joinToString("\n") { "${it.number}/${it.protocol}" + (it.serviceName?.let { n -> " ($n)" } ?: "") }
            }
            "check_shodan" -> {
                val ip = requireIp(arguments)
                val result = dataSource.checkShodan(ip)
                    ?: return@runCatching "No Shodan API key configured -- add one in GreyRecon's Settings first."
                result.fold(
                    onSuccess = { findings ->
                        if (findings.isEmpty()) "No Shodan record for $ip -- expected for most LAN devices unless directly internet-exposed."
                        else findings.joinToString("\n") { "- ${it.summary}" }
                    },
                    onFailure = { e -> throw e },
                )
            }
            "wake_on_lan" -> {
                val ip = requireIp(arguments)
                val result = dataSource.wakeOnLan(ip)
                    ?: return@runCatching "No MAC address known for $ip -- run scan_network first, or this device's MAC wasn't discoverable."
                result.fold(
                    onSuccess = { "Magic packet sent to $ip." },
                    onFailure = { e -> throw e },
                )
            }
            "check_security" -> {
                val ip = requireIp(arguments)
                dataSource.checkSecurity(ip).fold(
                    onSuccess = { result -> formatSecurityResult(result) },
                    onFailure = { e -> throw e },
                )
            }
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    private fun formatSecurityResult(result: com.greyrecon.app.engine.security.SecurityCheckResult): String = buildString {
        appendLine("${result.url} — HTTP ${result.statusCode}")
        if (result.presentHeaders.isEmpty()) appendLine("No security headers present.")
        else result.presentHeaders.forEach { (k, v) -> appendLine("$k: $v") }
        if (result.missingHeaders.isNotEmpty()) appendLine("Missing: ${result.missingHeaders.joinToString(", ")}")
        result.tls?.let { tls ->
            appendLine("TLS subject: ${tls.subject}")
            appendLine("TLS issuer: ${tls.issuer}")
            appendLine("Expires ${tls.notAfter}" + (if (tls.expired) " (EXPIRED)" else "") + (if (tls.selfSigned) " · self-signed" else ""))
            if (tls.protocol != null || tls.cipherSuite != null) {
                appendLine(
                    listOfNotNull(tls.protocol, tls.cipherSuite).joinToString(" / ") +
                        (if (tls.weakCipherOrProtocol) " (WEAK)" else "")
                )
            }
        }
        if (result.detectedTech.isNotEmpty()) append("Detected: ${result.detectedTech.joinToString(", ")}")
    }

    private fun requireIp(arguments: JsonObject?): String =
        arguments?.get("ip_address")?.jsonPrimitive?.contentOrNull()
            ?: throw IllegalArgumentException("ip_address is required")

    private fun JsonPrimitive.contentOrNull(): String? = if (this.isString) content else null

    private fun formatDevices(devices: List<Device>): String {
        if (devices.isEmpty()) return "No devices found."
        return devices.joinToString("\n") { device ->
            buildString {
                append(device.ipAddress)
                device.vendor?.let { append(" — $it") }
                append(" [${device.deviceType.name}]")
                device.hostname?.let { append(" host=$it") }
                if (device.isGateway) append(" (gateway)")
                if (device.openPorts.isNotEmpty()) {
                    append(" ports=${device.openPorts.joinToString(",") { it.number.toString() }}")
                }
            }
        }
    }
}
