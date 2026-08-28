package com.greyrecon.app.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.greyrecon.app.ai.AIProviderConfig
import com.greyrecon.app.ai.AIProviderFactory
import com.greyrecon.app.ai.AIProviderType
import com.greyrecon.app.ai.FindingTriage
import com.greyrecon.app.engine.discovery.ActiveScanDiscoveryService
import com.greyrecon.app.engine.discovery.ArpTableDiscoveryService
import com.greyrecon.app.engine.discovery.DeviceClassifier
import com.greyrecon.app.engine.discovery.DiscoveryEngine
import com.greyrecon.app.engine.discovery.MdnsDiscoveryService
import com.greyrecon.app.engine.discovery.SubnetInfo
import com.greyrecon.app.engine.discovery.UpnpDiscoveryService
import com.greyrecon.app.engine.discovery.VendorLookup
import com.greyrecon.app.engine.cve.EpssClient
import com.greyrecon.app.engine.cve.KevClient
import com.greyrecon.app.engine.cve.NvdClient
import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.engine.scan.PortServiceLookup
import com.greyrecon.app.engine.scan.TcpPortScanner
import com.greyrecon.app.engine.security.DefaultCredsChecker
import com.greyrecon.app.engine.security.ExposureChecker
import com.greyrecon.app.engine.security.SecurityChecker
import com.greyrecon.app.engine.shodan.ShodanClient
import com.greyrecon.app.engine.snmp.SnmpClient
import com.greyrecon.app.engine.snmp.SnmpCommunityWordlist
import com.greyrecon.app.engine.wol.WakeOnLan
import com.greyrecon.app.history.DeviceHistoryStore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch

sealed class ScanState {
    data object Idle : ScanState()
    data object NoWifiSubnet : ScanState()
    data class Scanning(val devices: List<Device>) : ScanState()
    data class Done(val devices: List<Device>) : ScanState()
}

class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<ScanState>(ScanState.Idle)
    val state: StateFlow<ScanState> = _state.asStateFlow()

    private val vendorLookup by lazy { VendorLookup(getApplication()) }
    private val historyStore by lazy { DeviceHistoryStore(getApplication()) }
    private val portServiceLookup by lazy { PortServiceLookup(getApplication()) }
    private val snmpCommunityWordlist by lazy { SnmpCommunityWordlist(getApplication()) }
    private val kevClient by lazy { KevClient() }

    /** Backing store for whatever's currently in [_state]'s device list -- kept so [scanPorts] can merge port results back in and reclassify, not just stash them in [_deviceActions]. */
    private val discoveredDevices = LinkedHashMap<String, Device>()

    private val _deviceActions = MutableStateFlow<Map<String, DeviceActions>>(emptyMap())
    val deviceActions: StateFlow<Map<String, DeviceActions>> = _deviceActions.asStateFlow()

    private fun updateActions(ipAddress: String, update: (DeviceActions) -> DeviceActions) {
        _deviceActions.value = _deviceActions.value.toMutableMap().apply {
            this[ipAddress] = update(this[ipAddress] ?: DeviceActions())
        }
    }

    fun scanPorts(ipAddress: String) {
        updateActions(ipAddress) { it.copy(ports = ActionResult.Loading) }
        viewModelScope.launch {
            try {
                val ports = TcpPortScanner(portServiceLookup).scan(ipAddress).toList()
                updateActions(ipAddress) { it.copy(ports = ActionResult.Success(ports)) }

                // Open ports are a classification signal (camera/printer/RDP), so merge them into
                // the device and reclassify -- e.g. a device that looked UNKNOWN before scanning
                // can resolve to CAMERA once port 554 shows up.
                discoveredDevices[ipAddress]?.let { device ->
                    val withPorts = device.copy(openPorts = ports)
                    val reclassified = withPorts.copy(deviceType = DeviceClassifier.classify(withPorts))
                    discoveredDevices[ipAddress] = reclassified
                    reEmitCurrentState()
                }
            } catch (e: Exception) {
                updateActions(ipAddress) { it.copy(ports = ActionResult.Error(e.message ?: "Port scan failed")) }
            }
        }
    }

    private fun reEmitCurrentState() {
        _state.value = when (val s = _state.value) {
            is ScanState.Scanning -> ScanState.Scanning(discoveredDevices.values.toList())
            is ScanState.Done -> ScanState.Done(discoveredDevices.values.toList())
            else -> s
        }
    }

    fun askAi(
        device: Device,
        apiKey: String,
        providerType: AIProviderType = AIProviderType.DEEPSEEK,
        question: String = DEFAULT_AI_QUESTION,
    ) {
        updateActions(device.ipAddress) { it.copy(aiResponse = ActionResult.Loading) }
        viewModelScope.launch {
            val model = when (providerType) {
                AIProviderType.ANTHROPIC -> "claude-sonnet-5"
                else -> "deepseek-chat"
            }
            val provider = AIProviderFactory.create(
                AIProviderConfig(type = providerType, apiKey = apiKey, model = model)
            )
            val context = buildAiContext(device, _deviceActions.value[device.ipAddress])
            provider.complete(question, context)
                .onSuccess { text -> updateActions(device.ipAddress) { it.copy(aiResponse = ActionResult.Success(text)) } }
                .onFailure { e -> updateActions(device.ipAddress) { it.copy(aiResponse = ActionResult.Error(e.message ?: "AI request failed")) } }
        }
    }

    /** Folds in whatever's already been checked this session (security headers/TLS, Shodan) so remediation advice isn't limited to just vendor/ports. */
    private fun buildAiContext(device: Device, actions: DeviceActions?): String = buildString {
        append("IP: ${device.ipAddress}\n")
        device.vendor?.let { append("Vendor: $it\n") }
        device.hostname?.let { append("Hostname: $it\n") }
        append("Device type (heuristically classified): ${device.deviceType.name}\n")
        append("Discovered by: ${device.discoveredBy.joinToString(", ")}\n")
        if (device.openPorts.isNotEmpty()) {
            append("Open ports: ${device.openPorts.joinToString(", ") { "${it.number}/${it.protocol}" + (it.serviceName?.let { n -> " ($n)" } ?: "") }}\n")
        }
        (actions?.securityCheck as? ActionResult.Success)?.value?.let { sec ->
            append("Security check on ${sec.url}: HTTP ${sec.statusCode}\n")
            if (sec.presentHeaders.isNotEmpty()) append("Present security headers: ${sec.presentHeaders.keys.joinToString(", ")}\n")
            if (sec.missingHeaders.isNotEmpty()) append("Missing security headers: ${sec.missingHeaders.joinToString(", ")}\n")
            sec.tls?.let { tls ->
                append(
                    "TLS cert: subject=${tls.subject}, issuer=${tls.issuer}, expires=${tls.notAfter}" +
                        (if (tls.expired) " (EXPIRED)" else "") + (if (tls.selfSigned) ", self-signed" else "") + "\n"
                )
            }
        }
        (actions?.shodanFindings as? ActionResult.Success)?.value?.takeIf { it.isNotEmpty() }?.let { findings ->
            append("Shodan findings: ${findings.joinToString("; ") { it.summary }}\n")
        }
    }

    companion object {
        const val DEFAULT_AI_QUESTION = "What is this device likely to be, and is anything about it worth flagging?"
        const val REMEDIATION_AI_QUESTION =
            "Based on everything known about this device, give concrete numbered remediation steps for any real " +
                "security risks present (risky open ports, missing security headers, a bad TLS cert, known CVEs). " +
                "Assume default vendor firmware and a non-expert user. If you don't have enough specific information " +
                "to give exact steps, say so plainly rather than guessing at exact menu names."
    }

    fun checkShodan(ipAddress: String, apiKey: String) {
        updateActions(ipAddress) { it.copy(shodanFindings = ActionResult.Loading) }
        viewModelScope.launch {
            ShodanClient(apiKey).lookupHost(ipAddress)
                .onSuccess { findings -> updateActions(ipAddress) { it.copy(shodanFindings = ActionResult.Success(findings)) } }
                .onFailure { e -> updateActions(ipAddress) { it.copy(shodanFindings = ActionResult.Error(e.message ?: "Shodan lookup failed")) } }
        }
    }

    fun checkNvd(device: Device, apiKey: String?) {
        val vendor = device.vendor
        if (vendor == null) {
            updateActions(device.ipAddress) { it.copy(nvdFindings = ActionResult.Error("No vendor identified for this device -- NVD keyword search needs a vendor name to search against")) }
            return
        }
        updateActions(device.ipAddress) { it.copy(nvdFindings = ActionResult.Loading) }
        viewModelScope.launch {
            val serviceHint = device.openPorts.firstOrNull()?.serviceName
            NvdClient(apiKey).search(vendor, serviceHint)
                .onSuccess { findings ->
                    // Best-effort: EPSS/KEV are separate free services with their own failure modes -- an
                    // outage on either shouldn't hide real NVD results the user already has, just leave the
                    // corresponding field at its default. KEV's catalog is fetched once and cached in-process
                    // ([KevClient]), so only the first lookup here pays a network cost.
                    val epssScores = EpssClient.lookup(findings.map { it.cveId }).getOrElse { emptyMap() }
                    val kevHits = findings.map { finding ->
                        async { finding.cveId to kevClient.lookup(finding.cveId).getOrNull() }
                    }.awaitAll().toMap()
                    val enriched = findings.map {
                        it.copy(epssScore = epssScores[it.cveId], inKev = kevHits[it.cveId] != null)
                    }
                    updateActions(device.ipAddress) { it.copy(nvdFindings = ActionResult.Success(enriched)) }
                }
                .onFailure { e -> updateActions(device.ipAddress) { it.copy(nvdFindings = ActionResult.Error(e.message ?: "NVD lookup failed")) } }
        }
    }

    fun checkSnmp(ipAddress: String) {
        updateActions(ipAddress) { it.copy(snmpInfo = ActionResult.Loading) }
        viewModelScope.launch {
            SnmpClient.query(ipAddress)
                .onSuccess { info -> updateActions(ipAddress) { it.copy(snmpInfo = ActionResult.Success(info)) } }
                .onFailure { e -> updateActions(ipAddress) { it.copy(snmpInfo = ActionResult.Error(e.message ?: "SNMP query failed")) } }
        }
    }

    fun checkSnmpWalk(ipAddress: String) {
        updateActions(ipAddress) { it.copy(snmpWalk = ActionResult.Loading) }
        viewModelScope.launch {
            SnmpClient.walk(ipAddress, SnmpClient.IF_DESCR_OID)
                .onSuccess { values -> updateActions(ipAddress) { it.copy(snmpWalk = ActionResult.Success(values)) } }
                .onFailure { e -> updateActions(ipAddress) { it.copy(snmpWalk = ActionResult.Error(e.message ?: "SNMP walk failed")) } }
        }
    }

    fun checkSnmpBruteForce(ipAddress: String) {
        updateActions(ipAddress) { it.copy(snmpBruteForce = ActionResult.Loading) }
        viewModelScope.launch {
            SnmpClient.bruteForceCommunity(ipAddress, snmpCommunityWordlist.strings)
                .onSuccess { hit -> updateActions(ipAddress) { it.copy(snmpBruteForce = ActionResult.Success(hit)) } }
                .onFailure { e -> updateActions(ipAddress) { it.copy(snmpBruteForce = ActionResult.Error(e.message ?: "SNMP community brute-force failed")) } }
        }
    }

    /** [aiConfig] is optional -- when present (an AI provider key is configured), findings get a
     * best-effort LLM false-positive triage pass after the check completes; when absent, findings are
     * shown as-is, same as before triage existed. Never blocks or fails the check itself. */
    fun checkExposures(ipAddress: String, aiConfig: AIProviderConfig? = null) {
        updateActions(ipAddress) { it.copy(exposures = ActionResult.Loading) }
        viewModelScope.launch {
            ExposureChecker.check(ipAddress)
                .onSuccess { findings ->
                    updateActions(ipAddress) { it.copy(exposures = ActionResult.Success(findings)) }
                    if (aiConfig != null && findings.isNotEmpty()) {
                        val triaged = findings.map { finding ->
                            async {
                                FindingTriage.evaluate(aiConfig, finding.name, finding.evidence)
                                    .getOrNull()
                                    ?.let { finding.copy(triage = it) } ?: finding
                            }
                        }.awaitAll()
                        updateActions(ipAddress) { it.copy(exposures = ActionResult.Success(triaged)) }
                    }
                }
                .onFailure { e -> updateActions(ipAddress) { it.copy(exposures = ActionResult.Error(e.message ?: "Exposure check failed")) } }
        }
    }

    /** Follow-up to [checkExposures] finding an exposed Tomcat Manager -- confirming a *default*
     * credential still works is a materially higher-severity finding than just "reachable". [aiConfig]
     * has the same optional best-effort triage behavior as [checkExposures]. */
    fun checkDefaultCreds(ipAddress: String, aiConfig: AIProviderConfig? = null) {
        updateActions(ipAddress) { it.copy(defaultCreds = ActionResult.Loading) }
        viewModelScope.launch {
            val candidates = DefaultCredsChecker.candidatesFor(getApplication(), "Tomcat")
            val baseUrl = listOf("https://$ipAddress", "http://$ipAddress").firstOrNull { base ->
                runCatching { java.net.URL(base).openConnection().connect() }.isSuccess
            } ?: "http://$ipAddress"
            DefaultCredsChecker.tryDefaults(baseUrl, "/manager/html", "Tomcat", candidates)
                .onSuccess { hit ->
                    updateActions(ipAddress) { it.copy(defaultCreds = ActionResult.Success(hit)) }
                    if (aiConfig != null) {
                        FindingTriage.evaluate(aiConfig, "${hit.product} default credentials", hit.evidence)
                            .getOrNull()
                            ?.let { verdict ->
                                updateActions(ipAddress) { it.copy(defaultCreds = ActionResult.Success(hit.copy(triage = verdict))) }
                            }
                    }
                }
                .onFailure { e -> updateActions(ipAddress) { it.copy(defaultCreds = ActionResult.Error(e.message ?: "Default-credential check failed")) } }
        }
    }

    fun wakeOnLan(device: Device) {
        val mac = device.macAddress
        if (mac == null) {
            updateActions(device.ipAddress) { it.copy(wakeOnLan = ActionResult.Error("No MAC address known for this device -- can't send a magic packet")) }
            return
        }
        updateActions(device.ipAddress) { it.copy(wakeOnLan = ActionResult.Loading) }
        viewModelScope.launch {
            WakeOnLan.send(mac)
                .onSuccess { updateActions(device.ipAddress) { it.copy(wakeOnLan = ActionResult.Success("Magic packet sent to $mac")) } }
                .onFailure { e -> updateActions(device.ipAddress) { it.copy(wakeOnLan = ActionResult.Error(e.message ?: "Failed to send magic packet")) } }
        }
    }

    fun checkSecurity(ipAddress: String) {
        updateActions(ipAddress) { it.copy(securityCheck = ActionResult.Loading) }
        viewModelScope.launch {
            SecurityChecker.check(ipAddress)
                .onSuccess { result -> updateActions(ipAddress) { it.copy(securityCheck = ActionResult.Success(result)) } }
                .onFailure { e -> updateActions(ipAddress) { it.copy(securityCheck = ActionResult.Error(e.message ?: "Security check failed")) } }
        }
    }

    fun startScan() {
        val context = getApplication<Application>()
        val subnet = SubnetInfo.fromCurrentConnection(context)
        if (subnet == null) {
            _state.value = ScanState.NoWifiSubnet
            return
        }

        val engine = DiscoveryEngine(
            listOf(
                ArpTableDiscoveryService(),
                MdnsDiscoveryService(context),
                UpnpDiscoveryService(context),
                ActiveScanDiscoveryService(subnet),
            )
        )

        discoveredDevices.clear()
        _state.value = ScanState.Scanning(emptyList())

        viewModelScope.launch {
            engine.discover().collect { device ->
                val enriched = if (device.vendor == null && device.macAddress != null) {
                    device.copy(vendor = vendorLookup.lookup(device.macAddress))
                } else {
                    device
                }
                val withGateway = enriched.copy(isGateway = enriched.ipAddress == subnet.gatewayAddress)
                val classified = withGateway.copy(deviceType = DeviceClassifier.classify(withGateway))
                discoveredDevices[classified.ipAddress] = classified
                _state.value = ScanState.Scanning(discoveredDevices.values.toList())
            }
            val finalDevices = discoveredDevices.values.toList()
            _state.value = ScanState.Done(finalDevices)
            historyStore.recordScanResults(finalDevices)
        }
    }
}
