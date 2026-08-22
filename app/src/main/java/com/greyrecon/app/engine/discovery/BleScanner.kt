package com.greyrecon.app.engine.discovery

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

data class BleDevice(
    val address: String,
    val name: String?,
    val rssi: Int,
    val companyId: Int?,
    val vendor: String?,
)

/**
 * BLE device discovery -- a genuinely new capability, deliberately kept separate from
 * [DeviceDiscoveryService]/[Device] rather than shoehorned into that WiFi-network, IP-keyed
 * pipeline: a BLE advertising address isn't an IP or a stable WiFi-NIC MAC (many real devices
 * rotate it per-connection for privacy), so it doesn't fit that model's dedup key at all. Own
 * simple screen, own simple data class, same "Android's own API is enough" pattern as
 * [MdnsDiscoveryService] using `NsdManager` instead of hand-rolling multicast DNS.
 *
 * `targetSdk` is 36 (see `AndroidManifest.xml`), so the real runtime `BLUETOOTH_SCAN` permission
 * applies (requested by `BleScanScreen.kt` before calling [scan]) rather than the legacy install-time
 * `BLUETOOTH`/`BLUETOOTH_ADMIN` model. Deliberately does *not* request `BLUETOOTH_CONNECT` -- the one
 * call that needs it (`BluetoothDevice.getName()`) is permission-guarded below instead, so this
 * passive-scan feature doesn't ask for a permission scoped toward pairing/connecting.
 *
 * Self-bounded by [scanDurationMs], same reasoning as `MdnsDiscoveryService.listenWindowMs`: BLE
 * advertising has no "done" signal, so an unbounded scan would never let the caller's UI settle.
 */
class BleScanner(private val context: Context) {

    private val companyLookup = BleCompanyLookup(context)

    fun scan(scanDurationMs: Long = 8_000): Flow<BleDevice> = callbackFlow {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            close() // no adapter, adapter disabled, or no BLE hardware -- natural empty completion
            return@callbackFlow
        }

        val seen = HashSet<String>()
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val address = result.device.address ?: return
                if (!seen.add(address)) return // BLE devices advertise repeatedly during the window

                val manufacturerData = result.scanRecord?.manufacturerSpecificData
                val companyId = manufacturerData?.let { if (it.size() > 0) it.keyAt(0) else null }

                trySend(
                    BleDevice(
                        address = address,
                        name = result.scanRecord?.deviceName ?: deviceNameIfPermitted(result),
                        rssi = result.rssi,
                        companyId = companyId,
                        vendor = companyId?.let { companyLookup.lookup(it) },
                    )
                )
            }

            override fun onScanFailed(errorCode: Int) { close() }
        }

        scanner.startScan(callback)

        launch {
            delay(scanDurationMs)
            close() // natural completion after the scan window -- not an error
        }

        awaitClose { scanner.stopScan(callback) }
    }

    // BluetoothDevice.getName() requires BLUETOOTH_CONNECT on API 31+, which this scan-only
    // feature deliberately doesn't request -- falls back to null rather than crashing when the
    // advertisement itself didn't carry a name (scanRecord.deviceName, checked by the caller first).
    private fun deviceNameIfPermitted(result: ScanResult): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return result.device.name
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        return if (granted) result.device.name else null
    }
}
