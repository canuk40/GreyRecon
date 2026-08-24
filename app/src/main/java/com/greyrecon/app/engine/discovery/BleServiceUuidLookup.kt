package com.greyrecon.app.engine.discovery

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * GATT service UUID to human-readable name lookup (e.g. "Battery Service", "Heart Rate") for the
 * service-UUID list a BLE advertisement carries -- the BLE analog of [BleCompanyLookup]'s
 * company-ID mapping, letting [BleScanner] classify a device by what it *does* rather than just
 * who made it.
 *
 * Bundled asset (`assets/ble_service_uuids.json`) is the same trusted source as
 * `ble_company_ids.json` -- `nordicsemi/bluetooth-numbers-database` (BSD-3-Clause) -- this time its
 * `v1/service_uuids.json` file. Standard (SIG-assigned) services carry a 16-bit short UUID (e.g.
 * "180F"); vendor-specific ones (Nordic UART, proprietary beacons, etc.) carry a full 128-bit UUID.
 * Both are normalized to the full lowercase 128-bit form here since that's what
 * `ParcelUuid.getUuid().toString()` returns from a real scan result -- short UUIDs live inside the
 * standard Bluetooth Base UUID (`0000xxxx-0000-1000-8000-00805f9b34fb`).
 */
class BleServiceUuidLookup(private val context: Context) {

    private val namesByUuid: Map<String, String> by lazy { loadAsset() }

    fun lookup(uuid: String): String? = namesByUuid[uuid.lowercase()]

    private fun loadAsset(): Map<String, String> {
        val text = context.assets.open("ble_service_uuids.json").bufferedReader().readText()
        val json = Json.parseToJsonElement(text).jsonArray
        val map = HashMap<String, String>(json.size)
        json.forEach { entry ->
            val obj = entry.jsonObject
            val rawUuid = obj["uuid"]?.jsonPrimitive?.content ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.content ?: return@forEach
            map[normalize(rawUuid)] = name
        }
        return map
    }

    private fun normalize(uuid: String): String =
        if (uuid.length == 4) "0000${uuid.lowercase()}-0000-1000-8000-00805f9b34fb" else uuid.lowercase()
}
