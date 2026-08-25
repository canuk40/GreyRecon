package com.greyrecon.app.engine.discovery

import android.os.ParcelUuid
import android.util.SparseArray
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * No real AirTag/Tile/etc. was available to physically test against, so this exercises
 * [TrackerDetector] and [BleSpamDetector] with crafted advertisement bytes matching the exact
 * signatures verified against seemoo-lab/AirGuard and simondankelmann/Bluetooth-LE-Spam's real
 * source -- proof the parsing logic itself is correct, independent of what any specific nearby
 * device happens to be broadcasting.
 */
@RunWith(AndroidJUnit4::class)
class TrackerDetectorTest {

    private fun appleManufacturerData(statusByte: Int): SparseArray<ByteArray> {
        val array = SparseArray<ByteArray>()
        array.put(0x004C, byteArrayOf(0x00, 0x00, statusByte.toByte()))
        return array
    }

    @Test
    fun detectsAirTagFromStatusNibbleOne() {
        // statusNibble = (byte and 0x30) shr 4 == 1  ->  byte[2] = 0x10
        assertEquals(TrackerType.AIRTAG, TrackerDetector.detect(appleManufacturerData(0x10), null))
    }

    @Test
    fun detectsThirdPartyFindMyFromStatusNibbleTwo() {
        assertEquals(TrackerType.APPLE_FIND_MY, TrackerDetector.detect(appleManufacturerData(0x20), null))
    }

    @Test
    fun detectsAirPodsFromStatusNibbleThree() {
        assertEquals(TrackerType.AIRPODS, TrackerDetector.detect(appleManufacturerData(0x30), null))
    }

    @Test
    fun detectsTileFromServiceUuid() {
        val uuids = listOf(ParcelUuid.fromString("0000FEED-0000-1000-8000-00805F9B34FB"))
        assertEquals(TrackerType.TILE, TrackerDetector.detect(null, uuids))
    }

    @Test
    fun detectsSamsungSmartTagFromServiceUuid() {
        val uuids = listOf(ParcelUuid.fromString("0000FD5A-0000-1000-8000-00805F9B34FB"))
        assertEquals(TrackerType.SAMSUNG_SMARTTAG, TrackerDetector.detect(null, uuids))
    }

    @Test
    fun unrelatedAdvertisementIsNotATracker() {
        val uuids = listOf(ParcelUuid.fromString("0000180F-0000-1000-8000-00805F9B34FB")) // Battery Service
        assertNull(TrackerDetector.detect(null, uuids))
    }

    @Test
    fun detectsFastPairSpamFromServiceUuid() {
        val uuids = listOf(ParcelUuid.fromString("0000FE2C-0000-1000-8000-00805F9B34FB"))
        assertEquals(SpamPackageType.FAST_PAIRING, BleSpamDetector.classify(null, uuids))
    }

    @Test
    fun detectsSwiftPairSpamFromManufacturerData() {
        val array = SparseArray<ByteArray>()
        array.put(0x0006, byteArrayOf(0x03.toByte(), 0x00.toByte(), 0x80.toByte()))
        assertEquals(SpamPackageType.SWIFT_PAIRING, BleSpamDetector.classify(array, null))
    }

    @Test
    fun detectsNewAirtagContinuitySpamFromManufacturerData() {
        val array = SparseArray<ByteArray>()
        // Real example prefix from the reference detector: 071905...
        array.put(0x004C, byteArrayOf(0x07, 0x19, 0x05, 0x00, 0x30))
        assertEquals(SpamPackageType.CONTINUITY_NEW_AIRTAG, BleSpamDetector.classify(array, null))
    }

    @Test
    fun benignAppleManufacturerDataIsNotSpam() {
        assertNull(BleSpamDetector.classify(appleManufacturerData(0x10), null))
    }
}
