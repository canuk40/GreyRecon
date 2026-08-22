package com.greyrecon.app.mcp

import android.content.Context
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.engine.discovery.ActiveScanDiscoveryService
import com.greyrecon.app.engine.discovery.ArpTableDiscoveryService
import com.greyrecon.app.engine.discovery.DeviceClassifier
import com.greyrecon.app.engine.discovery.DiscoveryEngine
import com.greyrecon.app.engine.discovery.MdnsDiscoveryService
import com.greyrecon.app.engine.discovery.SubnetInfo
import com.greyrecon.app.engine.discovery.UpnpDiscoveryService
import com.greyrecon.app.engine.discovery.VendorLookup
import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.model.Port
import com.greyrecon.app.engine.model.ShodanFinding
import com.greyrecon.app.engine.scan.PortServiceLookup
import com.greyrecon.app.engine.scan.TcpPortScanner
import com.greyrecon.app.engine.security.SecurityCheckResult
import com.greyrecon.app.engine.security.SecurityChecker
import com.greyrecon.app.engine.shodan.ShodanClient
import com.greyrecon.app.engine.wol.WakeOnLan
import com.greyrecon.app.history.DeviceHistoryStore
import kotlinx.coroutines.flow.toList

/**
 * Runs the same discovery/port-scan/Shodan engine ScanViewModel uses, but
 * standalone -- so the MCP server (running in a foreground Service) can serve
 * real scan data to an external MCP client even when MainActivity isn't open.
 * Keeps its own last-scan cache since it has no UI state to read from.
 */
class ScanDataSource(private val context: Context) {

    private val vendorLookup by lazy { VendorLookup(context) }
    private val keyStore by lazy { SecureKeyStore(context) }
    private val historyStore by lazy { DeviceHistoryStore(context) }
    private val portServiceLookup by lazy { PortServiceLookup(context) }

    @Volatile
    private var lastScan: List<Device> = emptyList()

    fun lastDevices(): List<Device> = lastScan

    suspend fun scanNetwork(): List<Device> {
        val subnet = SubnetInfo.fromCurrentConnection(context) ?: return emptyList()

        val engine = DiscoveryEngine(
            listOf(
                ArpTableDiscoveryService(),
                MdnsDiscoveryService(context),
                UpnpDiscoveryService(context),
                ActiveScanDiscoveryService(subnet),
            )
        )

        val found = LinkedHashMap<String, Device>()
        engine.discover().collect { device ->
            val enriched = if (device.vendor == null && device.macAddress != null) {
                device.copy(vendor = vendorLookup.lookup(device.macAddress))
            } else {
                device
            }
            val withGateway = enriched.copy(isGateway = enriched.ipAddress == subnet.gatewayAddress)
            val classified = withGateway.copy(deviceType = DeviceClassifier.classify(withGateway))
            found[classified.ipAddress] = classified
        }

        lastScan = found.values.toList()
        historyStore.recordScanResults(lastScan)
        return lastScan
    }

    suspend fun scanPorts(ipAddress: String): List<Port> {
        val ports = TcpPortScanner(portServiceLookup).scan(ipAddress).toList()
        lastScan = lastScan.map { device ->
            if (device.ipAddress == ipAddress) {
                val withPorts = device.copy(openPorts = ports)
                withPorts.copy(deviceType = DeviceClassifier.classify(withPorts))
            } else {
                device
            }
        }
        return ports
    }

    /** Returns null if no Shodan key is configured -- distinct from a real lookup failure. */
    suspend fun checkShodan(ipAddress: String): Result<List<ShodanFinding>>? {
        val key = keyStore.shodanKey ?: return null
        return ShodanClient(key).lookupHost(ipAddress)
    }

    /** Returns null if the device's MAC is unknown -- distinct from a real send failure. */
    suspend fun wakeOnLan(ipAddress: String): Result<Unit>? {
        val mac = lastScan.firstOrNull { it.ipAddress == ipAddress }?.macAddress ?: return null
        return WakeOnLan.send(mac)
    }

    suspend fun checkSecurity(ipAddress: String): Result<SecurityCheckResult> = SecurityChecker.check(ipAddress)
}
