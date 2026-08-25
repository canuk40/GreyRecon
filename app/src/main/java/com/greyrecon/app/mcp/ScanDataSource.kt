package com.greyrecon.app.mcp

import android.content.Context
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.engine.discovery.ActiveScanDiscoveryService
import com.greyrecon.app.engine.discovery.ArpTableDiscoveryService
import com.greyrecon.app.engine.discovery.DeviceClassifier
import com.greyrecon.app.engine.discovery.DiscoveryEngine
import com.greyrecon.app.engine.discovery.MdnsDiscoveryService
import com.greyrecon.app.engine.discovery.NetBiosClient
import com.greyrecon.app.engine.discovery.NetBiosInfo
import com.greyrecon.app.engine.discovery.SubnetInfo
import com.greyrecon.app.engine.discovery.UpnpDiscoveryService
import com.greyrecon.app.engine.discovery.VendorLookup
import com.greyrecon.app.engine.cve.CveFinding
import com.greyrecon.app.engine.cve.KevClient
import com.greyrecon.app.engine.cve.KevEntry
import com.greyrecon.app.engine.cve.NvdClient
import com.greyrecon.app.engine.cve.SsvcAssessment
import com.greyrecon.app.engine.cve.VulnrichmentClient
import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.model.Port
import com.greyrecon.app.engine.model.ShodanFinding
import com.greyrecon.app.engine.scan.PortServiceLookup
import com.greyrecon.app.engine.scan.TcpPortScanner
import com.greyrecon.app.engine.cve.EpssClient
import com.greyrecon.app.engine.security.DefaultCredsChecker
import com.greyrecon.app.engine.security.DefaultCredsHit
import com.greyrecon.app.engine.security.ExposureChecker
import com.greyrecon.app.engine.security.ExposureFinding
import com.greyrecon.app.engine.security.SecurityCheckResult
import com.greyrecon.app.engine.security.SecurityChecker
import com.greyrecon.app.engine.shodan.ShodanClient
import com.greyrecon.app.engine.snmp.SnmpClient
import com.greyrecon.app.engine.snmp.SnmpCommunityWordlist
import com.greyrecon.app.engine.snmp.SnmpInfo
import com.greyrecon.app.engine.tools.AbuseIpdbClient
import com.greyrecon.app.engine.tools.AbuseIpdbResult
import com.greyrecon.app.engine.tools.CtLogClient
import com.greyrecon.app.engine.tools.DnsHardeningChecker
import com.greyrecon.app.engine.tools.DnsLookup
import com.greyrecon.app.engine.tools.DnsRecord
import com.greyrecon.app.engine.tools.GreyNoiseClient
import com.greyrecon.app.engine.tools.GreyNoiseResult
import com.greyrecon.app.engine.tools.SubnetCalculation
import com.greyrecon.app.engine.tools.SubnetCalculator
import com.greyrecon.app.engine.tools.TyposquatCandidate
import com.greyrecon.app.engine.tools.TyposquatChecker
import com.greyrecon.app.engine.tools.UpnpIgdClient
import com.greyrecon.app.engine.tools.UpnpIgdResult
import com.greyrecon.app.engine.tools.WhoisLookup
import com.greyrecon.app.engine.wol.WakeOnLan
import com.greyrecon.app.history.DeviceHistoryStore
import java.net.URL
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
    private val kevClient by lazy { KevClient() }

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

    /** SNMP sysName/sysDescr via the default "public" read-only community string. */
    suspend fun checkSnmp(ipAddress: String): Result<SnmpInfo> = SnmpClient.query(ipAddress)

    /** Common exposure/debug-panel checks (non-destructive single GET + signature match per path). */
    suspend fun checkExposures(ipAddress: String): Result<List<ExposureFinding>> = ExposureChecker.check(ipAddress)

    /** NVD keyword CVE search for a vendor/product string. Uses the optional stored NVD key for a higher rate limit. */
    suspend fun lookupCve(keyword: String): Result<List<CveFinding>> = NvdClient(keyStore.nvdKey).search(keyword)

    /** Is this CVE on CISA's Known Exploited Vulnerabilities catalog (confirmed in-the-wild exploitation)? */
    suspend fun checkKev(cveId: String): Result<KevEntry?> = kevClient.lookup(cveId)

    /** CISA's SSVC decision points for a CVE (Exploitation/Automatable/Technical Impact) -- pairs with checkKev/epssScore as a third, org-action-oriented prioritization axis. */
    suspend fun checkSsvc(cveId: String): Result<SsvcAssessment?> = VulnrichmentClient.lookup(cveId)

    /** DNS-over-HTTPS record lookup for a domain (A/AAAA/MX/TXT/NS/CNAME/SOA). */
    suspend fun dnsLookup(domain: String, type: String): Result<List<DnsRecord>> = DnsLookup.lookup(domain, type)

    /** WHOIS/RDAP registration info for a domain or public IP. */
    suspend fun whoisLookup(query: String): Result<String> = WhoisLookup.lookup(query)

    /** SNMP GetBulk walk of a device's interface table (ifDescr) -- names the network interfaces it exposes. */
    suspend fun snmpWalk(ipAddress: String): Result<Map<String, String>> =
        SnmpClient.walk(ipAddress, SnmpClient.IF_DESCR_OID)

    /** Try ~118 common SNMP community strings against a device (on-demand pentest action, user's own network). */
    suspend fun snmpBruteForce(ipAddress: String): Result<Pair<String, SnmpInfo>> =
        SnmpClient.bruteForceCommunity(ipAddress, SnmpCommunityWordlist(context).strings)

    /** Test Tomcat Manager default-credential pairs against a device's web admin panel. */
    suspend fun checkDefaultCreds(ipAddress: String): Result<DefaultCredsHit> {
        val candidates = DefaultCredsChecker.candidatesFor(context, "Tomcat")
        val baseUrl = listOf("https://$ipAddress", "http://$ipAddress").firstOrNull { base ->
            runCatching { URL(base).openConnection().connect() }.isSuccess
        } ?: "http://$ipAddress"
        return DefaultCredsChecker.tryDefaults(baseUrl, "/manager/html", "Tomcat", candidates)
    }

    /** IPv4 subnet math -- network/broadcast/usable range/netmask for an IP + CIDR prefix. Pure local computation. */
    fun subnetCalc(ipAddress: String, prefixLength: Int): Result<SubnetCalculation> =
        SubnetCalculator.calculate(ipAddress, prefixLength)

    /** Certificate Transparency search (crt.sh) -- passive subdomain discovery + recent-cert list for a domain. */
    suspend fun ctLogSearch(domain: String): Result<CtLogClient.CtLogResult> = CtLogClient.search(domain)

    /** Generate typo/homograph variants of a domain and check which are registered by someone else. */
    suspend fun typosquatCheck(domain: String): Result<List<TyposquatCandidate>> = TyposquatChecker.check(domain)

    /** EPSS exploitation-probability (next 30 days, 0.0-1.0) for a CVE -- pairs with lookup_cve/check_kev. */
    suspend fun epssScore(cveId: String): Result<Double?> =
        EpssClient.lookup(listOf(cveId.trim().uppercase())).map { it[cveId.trim().uppercase()] }

    /** Email/DNS hardening posture for a domain: SPF, DMARC, DNSSEC, CAA, MTA-STS. No key. */
    suspend fun dnsHardening(domain: String): Result<String> = DnsHardeningChecker.check(domain)

    /** GreyNoise reputation for a public IP -- is it known internet scanner/noise? Optional key. */
    suspend fun greynoise(ip: String): Result<GreyNoiseResult> = GreyNoiseClient(keyStore.greynoiseKey).lookup(ip)

    /** AbuseIPDB abuse-confidence score for a public IP. Returns null if no key is configured. */
    suspend fun abuseipdb(ip: String): Result<AbuseIpdbResult>? {
        val key = keyStore.abuseipdbKey ?: return null
        return AbuseIpdbClient(key).check(ip)
    }

    /** Audit the user's own router's UPnP port-forwards + external IP. No key. */
    suspend fun auditRouterUpnp(): Result<UpnpIgdResult> = UpnpIgdClient(context).audit()

    /** NetBIOS node-status query -- recovers a device's Windows/SMB name, workgroup, and MAC. No key. */
    suspend fun netbios(ip: String): Result<NetBiosInfo> = NetBiosClient.query(ip)
}
