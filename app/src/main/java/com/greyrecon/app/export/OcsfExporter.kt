package com.greyrecon.app.export

import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.model.DeviceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Exports the current scan as OCSF (Open Cybersecurity Schema Framework, ocsf.io) "Device Inventory
 * Info" events -- one JSON object per device, schema version 1.9.0. Verified against the real
 * ocsf-schema repo rather than guessed: `events/discovery/inventory_info.json` (class_uid 5001,
 * extends `discovery` -> `base_event`) and `objects/device.json` (extends `endpoint`, which carries
 * `mac`/`type_id`).
 *
 * Alongside the existing CSV/JSON/Nmap-XML exports, this is the one aimed at interoperability with
 * SIEMs and log pipelines that already speak OCSF (Splunk, AWS Security Lake, Elastic, and others),
 * rather than a human-readable or nmap-tool-specific format.
 *
 * `activity_id: 2` ("Collect") matches the class's own description ("running a network sweep of
 * connected devices") -- this is exactly that, not a passive log ingestion (`activity_id: 1`).
 * `type_id` is mapped from GreyRecon's own [DeviceType] onto OCSF's `endpoint.type_id` enum, which
 * has no home-network-specific categories (camera/smart-home/game-console) -- the mapping below is
 * a judgment call, not a schema-defined equivalence, documented inline per case. Fields with no
 * clean OCSF home (`isGateway`, `openPorts`, `discoveredBy`) go into `unmapped`, the schema's own
 * documented place for source data that doesn't fit the standard attributes, rather than being
 * invented as new top-level fields a real OCSF consumer wouldn't recognize.
 */
object OcsfExporter {

    private const val OCSF_SCHEMA_VERSION = "1.9.0"
    private const val CATEGORY_UID = 5 // Discovery
    private const val CLASS_UID = 5001 // Device Inventory Info
    private const val ACTIVITY_ID = 2 // Collect
    private const val TYPE_UID = CLASS_UID * 100 + ACTIVITY_ID
    private const val SEVERITY_ID = 1 // Informational

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Serializable
    private data class OcsfProduct(val name: String, val vendor_name: String)

    @Serializable
    private data class OcsfMetadata(val product: OcsfProduct, val version: String)

    @Serializable
    private data class OcsfDevice(
        val hostname: String? = null,
        val ip: String,
        val mac: String? = null,
        val type: String,
        val type_id: Int,
        val vendor_name: String? = null,
    )

    @Serializable
    private data class OcsfUnmapped(
        val is_gateway: Boolean,
        val open_ports: List<Int>,
        val discovered_by: List<String>,
    )

    @Serializable
    private data class OcsfEvent(
        val activity_id: Int = ACTIVITY_ID,
        val activity_name: String = "Collect",
        val category_uid: Int = CATEGORY_UID,
        val category_name: String = "Discovery",
        val class_uid: Int = CLASS_UID,
        val class_name: String = "Device Inventory Info",
        val type_uid: Int = TYPE_UID,
        val severity_id: Int = SEVERITY_ID,
        val time: Long,
        val metadata: OcsfMetadata,
        val device: OcsfDevice,
        val unmapped: OcsfUnmapped,
    )

    fun toOcsf(devices: List<Device>): String {
        val metadata = OcsfMetadata(product = OcsfProduct(name = "GreyRecon", vendor_name = "GreyRecon"), version = OCSF_SCHEMA_VERSION)
        val now = System.currentTimeMillis()
        val events = devices.map { d ->
            OcsfEvent(
                time = now,
                metadata = metadata,
                device = OcsfDevice(
                    hostname = d.hostname,
                    ip = d.ipAddress,
                    mac = d.macAddress,
                    type = d.deviceType.toOcsfTypeCaption(),
                    type_id = d.deviceType.toOcsfTypeId(),
                    vendor_name = d.vendor,
                ),
                unmapped = OcsfUnmapped(
                    is_gateway = d.isGateway,
                    open_ports = d.openPorts.map { it.number },
                    discovered_by = d.discoveredBy.map { it.name },
                ),
            )
        }
        return json.encodeToString(events)
    }

    // OCSF's endpoint.type_id enum has no home-network-specific categories, so this maps onto the
    // closest available concept rather than an exact match -- 0/99 (Unknown/Other) are the
    // standard OCSF fallbacks for anything without a real equivalent.
    private fun DeviceType.toOcsfTypeId(): Int = when (this) {
        DeviceType.ROUTER -> 12 // "Router" -- exact match
        DeviceType.MOBILE -> 5 // "Mobile" -- exact match
        DeviceType.CAMERA -> 7 // "IOT" -- closest fit for a network-attached IP camera
        DeviceType.TV_OR_MEDIA -> 7 // "IOT" -- closest fit for a smart TV/media device
        DeviceType.SMART_HOME -> 7 // "IOT" -- exact conceptual fit
        DeviceType.COMPUTER -> 2 // "Desktop" -- GreyRecon can't distinguish desktop from laptop
        DeviceType.PRINTER -> 99 // "Other" -- no printer category in this enum
        DeviceType.GAME_CONSOLE -> 99 // "Other" -- no console category, and not embedded/sensor-like enough to call IOT
        DeviceType.UNKNOWN -> 0 // "Unknown"
    }

    private fun DeviceType.toOcsfTypeCaption(): String = when (this) {
        DeviceType.ROUTER -> "Router"
        DeviceType.MOBILE -> "Mobile"
        DeviceType.CAMERA, DeviceType.TV_OR_MEDIA, DeviceType.SMART_HOME -> "IOT"
        DeviceType.COMPUTER -> "Desktop"
        DeviceType.PRINTER, DeviceType.GAME_CONSOLE -> "Other"
        DeviceType.UNKNOWN -> "Unknown"
    }
}
