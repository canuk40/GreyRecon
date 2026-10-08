package com.greyrecon.app.engine.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.ActivityCompat

/** One nearby access point, normalised out of the platform's [ScanResult]. */
data class AccessPoint(
    val ssid: String?,
    val bssid: String,
    val signalDbm: Int,
    val frequencyMhz: Int,
    val channel: Int,
    val band: Band,
    val security: Security,
    /** Raw capability string, kept for the detail view and for anything the parser does not model. */
    val capabilities: String,
    val channelWidthMhz: Int?,
    val vendor: String?,
) {
    val isHidden: Boolean get() = ssid.isNullOrBlank()

    /** Rough signal bars, 0-4, from the usual RSSI breakpoints. */
    val signalLevel: Int
        get() = when {
            signalDbm >= -50 -> 4
            signalDbm >= -60 -> 3
            signalDbm >= -70 -> 2
            signalDbm >= -80 -> 1
            else -> 0
        }
}

enum class Band(val label: String) { GHZ_2_4("2.4 GHz"), GHZ_5("5 GHz"), GHZ_6("6 GHz"), UNKNOWN("?") }

/**
 * Ordered weakest to strongest so a rogue-AP downgrade is a simple comparison.
 */
enum class Security(val label: String, val rank: Int) {
    OPEN("Open", 0),
    WEP("WEP", 1),
    WPA("WPA", 2),
    WPA2("WPA2", 3),
    WPA2_WPA3("WPA2/WPA3", 4),
    WPA3("WPA3", 5),
    OWE("Enhanced Open (OWE)", 3),
    UNKNOWN("Unknown", 0),
}

/**
 * Enumerates nearby access points.
 *
 * GreyRecon has shipped as "WiFi & Network Scan" without ever calling `getScanResults()` -- it
 * scanned the LAN it was attached to and nothing else. This is the missing half, and it is also
 * what makes the store title honest.
 *
 * Permission handling is the interesting part. On API 33+ this uses NEARBY_WIFI_DEVICES declared
 * with `neverForLocation`, so the AP list costs no location permission at all; below 33 that
 * permission does not exist and the platform gates scan results on ACCESS_FINE_LOCATION, which is
 * already declared for BLE on old releases.
 *
 * [getScanResults] returns the system's most recent cache, which is usually fine and costs
 * nothing. [requestScan] asks for a fresh sweep, but `startScan()` is deprecated and hard-throttled
 * (roughly four calls per two minutes in the foreground, far less in the background), so callers
 * are expected to show cached results immediately and treat a refresh as best-effort rather than
 * blocking on it.
 */
class WifiApScanner(private val context: Context) {

    private val wifiManager by lazy {
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    }

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }

    fun hasPermission(): Boolean =
        ActivityCompat.checkSelfPermission(context, requiredPermission()) == PackageManager.PERMISSION_GRANTED

    fun isWifiEnabled(): Boolean = runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)

    /** Best-effort refresh. Returns false when the platform throttled or refused the request. */
    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = runCatching { wifiManager.startScan() }.getOrDefault(false)

    fun accessPoints(vendorFor: (String) -> String?): List<AccessPoint> {
        if (!hasPermission()) return emptyList()
        val results = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
        return results.mapNotNull { it.toAccessPoint(vendorFor) }
            .sortedByDescending { it.signalDbm }
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.toAccessPoint(vendorFor: (String) -> String?): AccessPoint? {
        val bssid = BSSID ?: return null
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.trim('"')
        } else {
            SSID
        }
        return AccessPoint(
            ssid = name?.takeIf { it.isNotBlank() },
            bssid = bssid,
            signalDbm = level,
            frequencyMhz = frequency,
            channel = channelFor(frequency),
            band = bandFor(frequency),
            security = securityFor(capabilities),
            capabilities = capabilities ?: "",
            channelWidthMhz = widthFor(channelWidth),
            vendor = vendorFor(bssid),
        )
    }

    companion object {

        fun bandFor(frequencyMhz: Int): Band = when (frequencyMhz) {
            in 2400..2500 -> Band.GHZ_2_4
            in 4900..5900 -> Band.GHZ_5
            in 5925..7125 -> Band.GHZ_6
            else -> Band.UNKNOWN
        }

        /**
         * Channel numbering differs per band: 2.4 GHz centres on 2412 in 5 MHz steps (with 14 as
         * a special case), 5 GHz is (f - 5000) / 5, and 6 GHz is (f - 5950) / 5.
         */
        fun channelFor(frequencyMhz: Int): Int = when {
            frequencyMhz == 2484 -> 14
            frequencyMhz in 2400..2483 -> (frequencyMhz - 2407) / 5
            frequencyMhz in 4900..5899 -> (frequencyMhz - 5000) / 5
            frequencyMhz in 5925..7125 -> (frequencyMhz - 5950) / 5
            else -> 0
        }

        /**
         * Parsed from the capability string rather than the newer typed APIs so one code path
         * covers every supported release. Order matters: WPA3 markers must be checked before
         * WPA2, because a transition-mode AP advertises both.
         */
        fun securityFor(capabilities: String?): Security {
            val caps = capabilities?.uppercase() ?: return Security.UNKNOWN
            val hasSae = "SAE" in caps
            val hasPsk = "WPA2-PSK" in caps || "RSN-PSK" in caps || "PSK" in caps
            return when {
                hasSae && hasPsk -> Security.WPA2_WPA3
                hasSae -> Security.WPA3
                "OWE" in caps -> Security.OWE
                "RSN" in caps || "WPA2" in caps -> Security.WPA2
                "WPA" in caps -> Security.WPA
                "WEP" in caps -> Security.WEP
                caps.isBlank() || "ESS" == caps.trim() || "[ESS]" in caps -> Security.OPEN
                else -> Security.UNKNOWN
            }
        }

        private fun widthFor(channelWidth: Int): Int? = when (channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> 20
            ScanResult.CHANNEL_WIDTH_40MHZ -> 40
            ScanResult.CHANNEL_WIDTH_80MHZ -> 80
            ScanResult.CHANNEL_WIDTH_160MHZ -> 160
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 160
            else -> null
        }
    }
}
