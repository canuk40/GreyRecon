package com.greyrecon.app.engine.discovery

import android.os.ParcelUuid
import android.util.SparseArray

/**
 * Bluetooth item-finder trackers (AirTag-style "is this following me?" devices) identifiable from
 * a passive BLE advertisement alone -- the same signatures seemoo-lab/AirGuard (Apache-2.0, active)
 * uses, verified against its real source (`DeviceManager.kt`'s `calculateDeviceType`) rather than
 * guessed. Two identification paths:
 *
 * - Apple devices (AirTag, third-party Find My accessories, AirPods, and other Apple devices
 *   broadcasting Find My/Nearby-Interaction data) all share manufacturer ID 0x004C (76); which one
 *   is encoded in bits 4-5 of the third manufacturer-data byte (`(byte[2] and 0x30) shr 4`).
 * - Everyone else (Tile, Chipolo, PebbleBee, Samsung SmartTag/Find My Mobile, Google's Find My
 *   Device network) advertises a vendor-specific 16-bit service UUID instead.
 */
enum class TrackerType(val displayName: String) {
    AIRTAG("AirTag"),
    APPLE_FIND_MY("Apple Find My accessory"),
    AIRPODS("AirPods"),
    APPLE_DEVICE("Apple device"),
    GOOGLE_FIND_MY_NETWORK("Google Find My Device network item"),
    TILE("Tile tracker"),
    CHIPOLO("Chipolo tracker"),
    PEBBLEBEE("Pebblebee tracker"),
    SAMSUNG_SMARTTAG("Samsung SmartTag"),
    SAMSUNG_FIND_MY_MOBILE("Samsung Find My Mobile item"),
}

object TrackerDetector {

    private const val MANUFACTURER_ID_APPLE = 0x004C

    private val GOOGLE_FIND_MY_NETWORK_UUID = ParcelUuid.fromString("0000FEAA-0000-1000-8000-00805F9B34FB")
    private val TILE_UUID = ParcelUuid.fromString("0000FEED-0000-1000-8000-00805F9B34FB")
    private val CHIPOLO_UUID = ParcelUuid.fromString("0000FE33-0000-1000-8000-00805F9B34FB")
    private val PEBBLEBEE_UUID = ParcelUuid.fromString("0000FA25-0000-1000-8000-00805F9B34FB")
    private val SAMSUNG_SMARTTAG_UUID = ParcelUuid.fromString("0000FD5A-0000-1000-8000-00805F9B34FB")
    private val SAMSUNG_FIND_MY_MOBILE_UUID = ParcelUuid.fromString("0000FD69-0000-1000-8000-00805F9B34FB")

    /** Null if this advertisement doesn't match any known tracker signature. */
    fun detect(manufacturerData: SparseArray<ByteArray>?, serviceUuids: List<ParcelUuid>?): TrackerType? {
        manufacturerData?.get(MANUFACTURER_ID_APPLE)?.let { data ->
            if (data.size >= 3) {
                // AirGuard's own priority order when multiple bits could plausibly match: AirTag,
                // then third-party Find My, then AirPods, then a bare Apple device last.
                val statusNibble = (data[2].toInt() and 0x30) shr 4
                when (statusNibble) {
                    1 -> return TrackerType.AIRTAG
                    2 -> return TrackerType.APPLE_FIND_MY
                    3 -> return TrackerType.AIRPODS
                    0 -> return TrackerType.APPLE_DEVICE
                }
            }
        }

        if (serviceUuids.isNullOrEmpty()) return null
        return when {
            GOOGLE_FIND_MY_NETWORK_UUID in serviceUuids -> TrackerType.GOOGLE_FIND_MY_NETWORK
            TILE_UUID in serviceUuids -> TrackerType.TILE
            CHIPOLO_UUID in serviceUuids -> TrackerType.CHIPOLO
            PEBBLEBEE_UUID in serviceUuids -> TrackerType.PEBBLEBEE
            SAMSUNG_SMARTTAG_UUID in serviceUuids -> TrackerType.SAMSUNG_SMARTTAG
            SAMSUNG_FIND_MY_MOBILE_UUID in serviceUuids -> TrackerType.SAMSUNG_FIND_MY_MOBILE
            else -> null
        }
    }
}
