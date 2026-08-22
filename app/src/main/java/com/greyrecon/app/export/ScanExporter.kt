package com.greyrecon.app.export

import com.greyrecon.app.engine.model.Device
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class ExportableDevice(
    val ipAddress: String,
    val macAddress: String?,
    val vendor: String?,
    val hostname: String?,
    val deviceType: String,
    val isGateway: Boolean,
    val openPorts: List<Int>,
    val discoveredBy: List<String>,
)

object ScanExporter {

    private val json = Json { prettyPrint = true }

    fun toJson(devices: List<Device>): String = json.encodeToString(devices.map { it.toExportable() })

    fun toCsv(devices: List<Device>): String = buildString {
        appendLine("ip,mac,vendor,hostname,device_type,is_gateway,open_ports,discovered_by")
        devices.forEach { d ->
            appendLine(
                listOf(
                    d.ipAddress,
                    d.macAddress.orEmpty(),
                    csvEscape(d.vendor.orEmpty()),
                    csvEscape(d.hostname.orEmpty()),
                    d.deviceType.name,
                    d.isGateway.toString(),
                    d.openPorts.joinToString(";") { it.number.toString() },
                    d.discoveredBy.joinToString(";") { it.name },
                ).joinToString(","),
            )
        }
    }

    private fun csvEscape(value: String): String =
        if (value.contains(",") || value.contains("\"")) "\"${value.replace("\"", "\"\"")}\"" else value

    private fun Device.toExportable() = ExportableDevice(
        ipAddress = ipAddress,
        macAddress = macAddress,
        vendor = vendor,
        hostname = hostname,
        deviceType = deviceType.name,
        isGateway = isGateway,
        openPorts = openPorts.map { it.number },
        discoveredBy = discoveredBy.map { it.name },
    )
}
