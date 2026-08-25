package com.greyrecon.app.engine.discovery

import android.os.ParcelUuid
import android.util.SparseArray

/**
 * Identifies BLE advertisements matching known "BLE spam" attack payloads -- the crafted packets
 * tools like the Flipper Zero's (custom-firmware-only) "BLE Spam" app broadcast to pop fake
 * pairing/action prompts on nearby phones (Apple Continuity action-sheet spoofs and the iOS 17
 * Bluetooth-stack crash payload, Android Fast Pair and Windows Swift Pair spoofs, Samsung Easy Setup
 * spoofs for Buds/Watch). These are hex-prefix signatures for payloads that legitimate devices
 * essentially never emit, so a match is itself the signal -- verified against the real detector in
 * simondankelmann/Bluetooth-LE-Spam (GPL-3.0, readable-not-reusable per that license: signatures
 * reimplemented from scratch here, not copied) rather than guessed.
 */
enum class SpamPackageType(val displayName: String) {
    FAST_PAIRING("Android Fast Pair spoof"),
    SWIFT_PAIRING("Windows Swift Pair spoof"),
    CONTINUITY_ACTION_MODAL("Apple Continuity action-sheet spoof"),
    CONTINUITY_IOS_17_CRASH("Apple Continuity iOS 17 crash payload"),
    CONTINUITY_NEW_AIRTAG("Apple Continuity fake \"New AirTag\" prompt"),
    CONTINUITY_NEW_DEVICE("Apple Continuity fake \"New Device\" prompt"),
    CONTINUITY_NOT_YOUR_DEVICE("Apple Continuity fake \"Not Your Device\" prompt"),
    EASY_SETUP_BUDS("Samsung Easy Setup (Buds) spoof"),
    EASY_SETUP_WATCH("Samsung Easy Setup (Watch) spoof"),
}

object BleSpamDetector {

    private const val MANUFACTURER_ID_APPLE = 0x004C
    private const val MANUFACTURER_ID_MICROSOFT = 0x0006
    private const val MANUFACTURER_ID_SAMSUNG = 0x0075
    private val FAST_PAIR_UUID = ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB")

    /** Null if this advertisement doesn't match any known spam-payload signature. */
    fun classify(manufacturerData: SparseArray<ByteArray>?, serviceUuids: List<ParcelUuid>?): SpamPackageType? {
        if (serviceUuids?.contains(FAST_PAIR_UUID) == true) return SpamPackageType.FAST_PAIRING

        manufacturerData?.get(MANUFACTURER_ID_APPLE)?.let { data ->
            val hex = data.toHex()
            if (hex.startsWith("0f05c0") || hex.startsWith("0f0540")) {
                return if (hex.contains("000010")) SpamPackageType.CONTINUITY_IOS_17_CRASH
                else SpamPackageType.CONTINUITY_ACTION_MODAL
            }
            if (hex.startsWith("071905")) return SpamPackageType.CONTINUITY_NEW_AIRTAG
            if (hex.startsWith("071907")) return SpamPackageType.CONTINUITY_NEW_DEVICE
            if (hex.startsWith("071901")) return SpamPackageType.CONTINUITY_NOT_YOUR_DEVICE
        }

        manufacturerData?.get(MANUFACTURER_ID_MICROSOFT)?.let { data ->
            if (data.toHex().startsWith("030080")) return SpamPackageType.SWIFT_PAIRING
        }

        manufacturerData?.get(MANUFACTURER_ID_SAMSUNG)?.let { data ->
            val hex = data.toHex()
            if (hex.startsWith("42098102141503210109") && hex.endsWith("063c948e00000000c700")) {
                return SpamPackageType.EASY_SETUP_BUDS
            }
            if (hex.startsWith("010002000101ff000043")) return SpamPackageType.EASY_SETUP_WATCH
        }

        return null
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
