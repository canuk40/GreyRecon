package com.greyrecon.app.engine.discovery

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [BleServiceUuidLookup] against the real bundled `ble_service_uuids.json` asset --
 * standard-services short-UUID normalization and vendor-specific full-UUID passthrough both need
 * checking since [BleScanner] only ever calls [BleServiceUuidLookup.lookup] with the full 36-char
 * lowercase form `ParcelUuid.getUuid().toString()` returns, never the short form the JSON itself
 * stores for SIG-assigned services.
 */
@RunWith(AndroidJUnit4::class)
class BleServiceUuidLookupTest {

    private val lookup = BleServiceUuidLookup(InstrumentationRegistry.getInstrumentation().targetContext)

    @Test
    fun standardServiceShortUuidResolvesViaFullForm() {
        assertEquals("Battery Service", lookup.lookup("0000180f-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun vendorSpecificFullUuidResolvesDirectly() {
        assertEquals("Nordic UART Service", lookup.lookup("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
    }

    @Test
    fun lookupIsCaseInsensitive() {
        assertEquals("Battery Service", lookup.lookup("0000180F-0000-1000-8000-00805F9B34FB"))
    }

    @Test
    fun unknownUuidReturnsNull() {
        assertNull(lookup.lookup("deadbeef-0000-1000-8000-00805f9b34fb"))
    }
}
