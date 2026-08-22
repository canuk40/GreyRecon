package com.greyrecon.app.engine.discovery

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bluetooth SIG company-identifier code (the manufacturer ID BLE advertisements carry in their
 * manufacturer-specific-data field) to vendor name lookup -- the BLE analog of [VendorLookup]'s
 * IEEE OUI mapping, since BLE devices don't have a MAC-prefix vendor block the same way WiFi/
 * Ethernet NICs do (the whole advertising address can be randomized per-connection).
 *
 * Bundled asset (`assets/ble_company_ids.json`) is `NordicSemiconductor/bluetooth-numbers-database`
 * (BSD-3-Clause, verified via the repo's own LICENSE file), the real published company-identifier
 * list from an actual Bluetooth chip vendor -- not a fabricated or sampled subset.
 */
class BleCompanyLookup(private val context: Context) {

    private val namesByCode: Map<Int, String> by lazy { loadAsset() }

    fun lookup(companyId: Int): String? = namesByCode[companyId]

    private fun loadAsset(): Map<Int, String> {
        val text = context.assets.open("ble_company_ids.json").bufferedReader().readText()
        val json = Json.parseToJsonElement(text).jsonArray
        val map = HashMap<Int, String>(json.size)
        json.forEach { entry ->
            val obj = entry.jsonObject
            val code = obj["code"]?.jsonPrimitive?.content?.toIntOrNull() ?: return@forEach
            val name = obj["name"]?.jsonPrimitive?.content ?: return@forEach
            map[code] = name
        }
        return map
    }
}
