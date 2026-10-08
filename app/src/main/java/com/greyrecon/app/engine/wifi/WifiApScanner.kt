package com.greyrecon.app.engine.wifi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.location.LocationManagerCompat

/** Why a scan produced nothing, so the UI can say something true instead of guessing. */
sealed interface ApScanOutcome {
    data class Success(val accessPoints: List<AccessPoint>) : ApScanOutcome
    data object MissingPermission : ApScanOutcome
    data object LocationServicesOff : ApScanOutcome
    data object WifiOff : ApScanOutcome
}

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
 * Permission handling turned out to be the hard part, and the obvious design was wrong.
 *
 * NEARBY_WIFI_DEVICES with `neverForLocation` is documented as the API 33+ replacement for a
 * location permission, and that is how this was first written. On a real Android 15 device it is
 * not sufficient: with NEARBY_WIFI_DEVICES granted and ACCESS_FINE_LOCATION denied, the platform
 * refuses with `SecurityException: UID ... has no location permission`, and with location
 * permission held but the device Location toggle off it refuses with `Location mode is disabled
 * for the device`. Verified in logcat against WifiService on Android 15.
 *
 * So `getScanResults()` in practice needs ACCESS_FINE_LOCATION *and* Location services switched
 * on, and NEARBY_WIFI_DEVICES is requested as well because it is the correct forward-looking
 * declaration and is accepted on some builds. Both failure modes are reported distinctly rather
 * than collapsing into an empty list, because an empty list with no explanation is exactly how
 * this bug hid in the first place: the screen opened, looked fine, and silently did nothing.
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

    /**
     * Everything worth asking for. ACCESS_FINE_LOCATION is the one the platform actually enforces
     * today; NEARBY_WIFI_DEVICES is requested alongside it on API 33+ because it is the correct
     * declaration going forward and some builds do accept it on its own.
     */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES, Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    /**
     * Gated on ACCESS_FINE_LOCATION specifically, not on "any of the requested permissions".
     *
     * Measured behaviour on Android 15: with NEARBY_WIFI_DEVICES granted and location denied, the
     * platform logs `Permission violation - getScanResults not allowed ... has no location
     * permission` and returns an **empty list** rather than throwing. An `any {}` check therefore
     * passed, the empty list was taken at face value, and the screen cheerfully reported "no
     * access points nearby" on a device surrounded by them. Checking the permission the platform
     * actually enforces is the only way to tell the two apart, because the return value cannot.
     */
    fun hasPermission(): Boolean =
        ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The platform refuses scan results outright when the device Location toggle is off. */
    fun isLocationServicesEnabled(): Boolean = runCatching {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        LocationManagerCompat.isLocationEnabled(lm)
    }.getOrDefault(false)

    fun isWifiEnabled(): Boolean = runCatching { wifiManager.isWifiEnabled }.getOrDefault(false)

    /** Best-effort refresh. Returns false when the platform throttled or refused the request. */
    @Suppress("DEPRECATION")
    fun requestScan(): Boolean = runCatching { wifiManager.startScan() }.getOrDefault(false)

    /**
     * Distinguishes "nothing is nearby" from "the platform refused", because they look identical
     * from an empty list and only one of them is the user's problem to fix.
     */
    fun accessPoints(vendorFor: (String) -> String?): ApScanOutcome {
        if (!isWifiEnabled()) return ApScanOutcome.WifiOff
        if (!hasPermission()) return ApScanOutcome.MissingPermission
        if (!isLocationServicesEnabled()) return ApScanOutcome.LocationServicesOff

        val results = try {
            wifiManager.scanResults
        } catch (e: SecurityException) {
            // The message is the only way to tell the two refusals apart.
            return if (e.message?.contains("Location mode", ignoreCase = true) == true) {
                ApScanOutcome.LocationServicesOff
            } else {
                ApScanOutcome.MissingPermission
            }
        }
        return ApScanOutcome.Success(
            results.mapNotNull { it.toAccessPoint(vendorFor) }.sortedByDescending { it.signalDbm }
        )
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
