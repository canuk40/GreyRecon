package com.greyrecon.app.engine.discovery

import android.content.Context
import com.greyrecon.app.engine.model.Device

/**
 * The one definition of "run a discovery scan of the current network".
 *
 * ScanViewModel (streaming, for the UI), ScanDataSource (headless, for the MCP server) and
 * NetworkWatchWorker (headless, in the background) all need identical results. They previously
 * each carried their own copy of the same engine setup and enrichment chain; a third copy was the
 * point at which that stopped being acceptable, because the background watcher diffs its results
 * against a baseline the foreground scan wrote. Any divergence between the two -- a discovery
 * service present in one and not the other, a different classification order -- shows up as
 * phantom "new device" and "went offline" alerts rather than as an obvious bug.
 *
 * ScanViewModel still drives its own collect loop so it can publish partial results as devices
 * arrive, but it shares [engineFor] and [enrich] so the output is the same set either way.
 */
object NetworkScan {

    /** The discovery services, in the order the UI has always run them. */
    fun engineFor(context: Context, subnet: SubnetInfo): DiscoveryEngine = DiscoveryEngine(
        listOf(
            ArpTableDiscoveryService(),
            MdnsDiscoveryService(context),
            UpnpDiscoveryService(context),
            ActiveScanDiscoveryService(subnet),
        )
    )

    /** Vendor lookup, gateway flagging and classification -- applied identically on every surface. */
    fun enrich(device: Device, subnet: SubnetInfo, vendorLookup: VendorLookup): Device {
        val withVendor = if (device.vendor == null && device.macAddress != null) {
            device.copy(vendor = vendorLookup.lookup(device.macAddress))
        } else {
            device
        }
        val withGateway = withVendor.copy(isGateway = withVendor.ipAddress == subnet.gatewayAddress)
        return withGateway.copy(deviceType = DeviceClassifier.classify(withGateway))
    }

    /**
     * A complete headless scan. Returns an empty list when not on a WiFi network, matching the
     * existing behaviour of every caller rather than throwing.
     */
    suspend fun run(context: Context, vendorLookup: VendorLookup): List<Device> {
        val subnet = SubnetInfo.fromCurrentConnection(context) ?: return emptyList()
        val found = LinkedHashMap<String, Device>()
        engineFor(context, subnet).discover().collect { device ->
            val enriched = enrich(device, subnet, vendorLookup)
            found[enriched.ipAddress] = enriched
        }
        return found.values.toList()
    }
}
