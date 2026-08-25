package com.greyrecon.app.mcp

import com.greyrecon.app.engine.model.Device
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/** name -> one-line description of what that string argument means, in declaration order. */
data class ToolDef(val name: String, val description: String, val parameters: LinkedHashMap<String, String>, val required: List<String>)

/**
 * The tools GreyRecon exposes over MCP -- lets a user's own self-hosted
 * nanobot instance ask "what's on my network" and act on it conversationally,
 * instead of the BYOK free-tier path where the app itself calls an AI
 * provider. Same underlying scan engine either way (see [ScanDataSource]).
 *
 * Tool metadata is plain data here -- [GreyReconMcpServer] converts it to the SDK's `ToolSchema` and
 * registers it via `Server.addTool()`, which also generates the `tools/list` response itself. This
 * class no longer hand-builds any JSON-RPC shape.
 */
object McpTools {

    fun definitions(): List<ToolDef> = listOf(
        ToolDef(
            name = "scan_network",
            description = "Run a fresh discovery scan of the current WiFi network (ARP, mDNS, UPnP, active TCP sweep). Takes up to ~30s. Returns every device found.",
            parameters = linkedMapOf(),
            required = emptyList(),
        ),
        ToolDef(
            name = "list_devices",
            description = "Return the most recent scan's device list without running a new scan. Call scan_network first if nothing has been scanned yet.",
            parameters = linkedMapOf(),
            required = emptyList(),
        ),
        ToolDef(
            name = "scan_ports",
            description = "TCP port-scan a specific device found by a previous scan.",
            parameters = linkedMapOf("ip_address" to "The device's IP address, e.g. 192.168.1.42"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "check_shodan",
            description = "Look up a device's IP on Shodan for known CVEs/exposed services. Only useful for devices with a real public IP -- most LAN devices won't have a record. Requires a Shodan API key configured in GreyRecon's Settings.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to check"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "wake_on_lan",
            description = "Send a Wake-on-LAN magic packet to a device found by a previous scan, to power it on remotely. Only works if the device's MAC address is known and it supports WoL.",
            parameters = linkedMapOf("ip_address" to "The device's IP address, used to look up its MAC from the last scan"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "check_security",
            description = "Check a device's HTTP security headers and, over HTTPS, its TLS certificate details (issuer, expiry, self-signed status, protocol/cipher suite). Connects with certificate verification disabled so invalid/self-signed certs (common on LAN devices) can still be inspected.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to check"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "check_snmp",
            description = "Query a device over SNMP (v2c, default 'public' community) for its system name and description -- often reveals the exact make/model/firmware of managed switches, printers, and IoT gear that other scans can't identify. Returns nothing if the device doesn't run SNMP or uses a non-default community string.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to query"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "check_exposures",
            description = "Probe a device's web server for ~16 common misconfigurations and exposed panels (exposed .git/.env, Spring Boot Actuator, Tomcat Manager, phpinfo, Swagger, debug consoles, etc). Non-destructive: one GET plus a signature match per path. Returns which exposures, if any, were found.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to probe"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "lookup_cve",
            description = "Search the NVD (National Vulnerability Database) for known CVEs matching a vendor/product/service keyword, e.g. 'D-Link', 'OpenSSH', 'Apache'. Matching is by keyword against CVE descriptions, so results are a vulnerability class associated with that product, not a confirmed exact-version match. Returns up to 5 CVEs with severity.",
            parameters = linkedMapOf("keyword" to "The vendor/product/service to search, e.g. 'D-Link' or 'OpenSSH'"),
            required = listOf("keyword"),
        ),
        ToolDef(
            name = "check_kev",
            description = "Check whether a specific CVE is on CISA's Known Exploited Vulnerabilities (KEV) catalog -- CVEs with confirmed active in-the-wild exploitation. The strongest signal that a vulnerability needs urgent attention. Use after lookup_cve to prioritize which CVEs actually matter right now.",
            parameters = linkedMapOf("cve_id" to "The CVE identifier, e.g. CVE-2023-1234"),
            required = listOf("cve_id"),
        ),
        ToolDef(
            name = "check_ssvc",
            description = "Get CISA's SSVC (Stakeholder-Specific Vulnerability Categorization) assessment for a CVE, if one exists: Exploitation status (none/poc/active), Automatable (can exploitation be scripted at scale), and Technical Impact (partial/total). A different prioritization axis than EPSS's probability estimate or KEV's binary flag -- answers 'should this be acted on now', not just 'how likely'. Most CVEs have no SSVC assessment; that's normal, not an error.",
            parameters = linkedMapOf("cve_id" to "The CVE identifier, e.g. CVE-2023-1234"),
            required = listOf("cve_id"),
        ),
        ToolDef(
            name = "dns_lookup",
            description = "Look up DNS records for a domain over DNS-over-HTTPS. Supports A, AAAA, MX, TXT, NS, CNAME, SOA record types.",
            parameters = linkedMapOf(
                "domain" to "The domain to look up, e.g. example.com",
                "record_type" to "The DNS record type: A, AAAA, MX, TXT, NS, CNAME, or SOA. Defaults to A if omitted.",
            ),
            required = listOf("domain"),
        ),
        ToolDef(
            name = "whois_lookup",
            description = "Get WHOIS/RDAP registration info for a domain or public IP address -- registrar, registration/expiry dates, nameservers, and (for IPs) the owning organization. Private/LAN IPs return a canned explanation rather than a real lookup.",
            parameters = linkedMapOf("query" to "The domain or IP address to look up"),
            required = listOf("query"),
        ),
        ToolDef(
            name = "snmp_walk",
            description = "SNMP-walk a device's network-interface table (ifDescr) -- lists the interfaces it exposes (eth0, wlan0, VLANs, etc). Useful for identifying managed switches, routers, and multi-homed devices. Needs the device to answer SNMP on the default 'public' community.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to walk"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "snmp_bruteforce",
            description = "Try ~118 common/default SNMP community strings against a device that didn't answer 'public'. An active credential-guessing test -- use on the user's own network. Returns the working community string and the device's system info if one is found.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to test"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "check_default_creds",
            description = "Test known Tomcat Manager default username/password pairs against a device's web admin panel (/manager/html). An active credential test -- best used after check_exposures finds an exposed Tomcat Manager. Returns the working credential pair if a default still works.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to test"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "subnet_calc",
            description = "Compute IPv4 subnet details for an IP and CIDR prefix: network address, broadcast address, usable host range, host count, and netmask. Pure local math, no network access.",
            parameters = linkedMapOf(
                "ip_address" to "An IPv4 address, e.g. 192.168.1.10",
                "prefix_length" to "The CIDR prefix length 0-32, e.g. 24",
            ),
            required = listOf("ip_address", "prefix_length"),
        ),
        ToolDef(
            name = "ct_log_subdomains",
            description = "Search Certificate Transparency logs (crt.sh) for a domain -- passively discovers subdomains from every TLS cert ever issued for it, with no DNS bruteforcing, plus a list of the most recently issued certs (useful for spotting unexpected/rogue certs). No API key needed.",
            parameters = linkedMapOf("domain" to "The domain to search, e.g. example.com"),
            required = listOf("domain"),
        ),
        ToolDef(
            name = "typosquat_check",
            description = "Generate realistic typo/lookalike variants of a domain (character omission, transposition, lookalike substitution, TLD swap) and check which are actually registered by someone else -- a domain-impersonation / phishing-watch check.",
            parameters = linkedMapOf("domain" to "The domain to check, e.g. example.com"),
            required = listOf("domain"),
        ),
        ToolDef(
            name = "epss_score",
            description = "Get the EPSS score for a CVE -- the probability (0.0-1.0) that it will be exploited in the wild in the next 30 days. Pairs with lookup_cve (which finds CVEs) and check_kev (confirmed exploitation): EPSS estimates likelihood for CVEs not yet on the KEV list. No API key needed.",
            parameters = linkedMapOf("cve_id" to "The CVE identifier, e.g. CVE-2023-1234"),
            required = listOf("cve_id"),
        ),
        ToolDef(
            name = "dns_email_security",
            description = "Audit a domain's email/DNS hardening from public DNS records: SPF and DMARC (can mail be spoofed from it?), DNSSEC (is the zone signed?), CAA (is cert issuance restricted?), and MTA-STS (is inbound mail forced over TLS?). Flags missing or weak records, e.g. DMARC p=none. No API key needed.",
            parameters = linkedMapOf("domain" to "The domain to audit, e.g. example.com"),
            required = listOf("domain"),
        ),
        ToolDef(
            name = "check_greynoise",
            description = "Look up a PUBLIC IP on GreyNoise -- is it known internet background-noise (a mass scanner/crawler) or a benign business service (RIOT), with the actor classification and name. Good for triaging an external IP a device is talking to. Only meaningful for public IPs, not LAN addresses. Works without a key at a low daily limit; a free GreyNoise key in Settings raises it.",
            parameters = linkedMapOf("ip_address" to "The public IP address to look up"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "check_abuseipdb",
            description = "Check a PUBLIC IP's abuse reputation on AbuseIPDB -- a 0-100 abuse-confidence score, total reports, ISP/country/usage type. Useful to check the network's own public IP (a high score can indicate compromise/botnet activity) or an external IP a device contacts. Requires a free AbuseIPDB API key in Settings.",
            parameters = linkedMapOf("ip_address" to "The public IP address to check"),
            required = listOf("ip_address"),
        ),
        ToolDef(
            name = "audit_router_upnp",
            description = "Audit the user's own router over UPnP: list every port-forward UPnP has opened (external port -> internal device/port, protocol, description) plus the router's external IP. Answers 'did an app or malware silently open a hole in my firewall?'. Takes no arguments -- always audits the current network's gateway. No API key needed.",
            parameters = linkedMapOf(),
            required = emptyList(),
        ),
        ToolDef(
            name = "netbios_lookup",
            description = "Query a device over NetBIOS (UDP 137) for its Windows/SMB hostname, workgroup/domain, and MAC address. Recovers identity + MAC (hence vendor) for devices that don't announce via mDNS/UPnP -- useful for naming otherwise-'unknown' devices on the network. No API key needed.",
            parameters = linkedMapOf("ip_address" to "The device's IP address to query"),
            required = listOf("ip_address"),
        ),
    )

    suspend fun call(name: String, arguments: JsonObject?, dataSource: ScanDataSource): Result<String> = runCatching {
        when (name) {
            "scan_network" -> formatDevices(dataSource.scanNetwork())
            "list_devices" -> {
                val devices = dataSource.lastDevices()
                if (devices.isEmpty()) "No devices scanned yet -- call scan_network first." else formatDevices(devices)
            }
            "scan_ports" -> {
                val ip = requireIp(arguments)
                val ports = dataSource.scanPorts(ip)
                if (ports.isEmpty()) "No open ports found on $ip in the scanned range." else
                    ports.joinToString("\n") { "${it.number}/${it.protocol}" + (it.serviceName?.let { n -> " ($n)" } ?: "") }
            }
            "check_shodan" -> {
                val ip = requireIp(arguments)
                val result = dataSource.checkShodan(ip)
                    ?: return@runCatching "No Shodan API key configured -- add one in GreyRecon's Settings first."
                result.fold(
                    onSuccess = { findings ->
                        if (findings.isEmpty()) "No Shodan record for $ip -- expected for most LAN devices unless directly internet-exposed."
                        else findings.joinToString("\n") { "- ${it.summary}" }
                    },
                    onFailure = { e -> throw e },
                )
            }
            "wake_on_lan" -> {
                val ip = requireIp(arguments)
                val result = dataSource.wakeOnLan(ip)
                    ?: return@runCatching "No MAC address known for $ip -- run scan_network first, or this device's MAC wasn't discoverable."
                result.fold(
                    onSuccess = { "Magic packet sent to $ip." },
                    onFailure = { e -> throw e },
                )
            }
            "check_security" -> {
                val ip = requireIp(arguments)
                dataSource.checkSecurity(ip).fold(
                    onSuccess = { result -> formatSecurityResult(result) },
                    onFailure = { e -> throw e },
                )
            }
            "check_snmp" -> {
                val ip = requireIp(arguments)
                dataSource.checkSnmp(ip).fold(
                    onSuccess = { info ->
                        listOfNotNull(
                            info.sysName?.let { "sysName: $it" },
                            info.sysDescr?.let { "sysDescr: $it" },
                        ).ifEmpty { listOf("SNMP responded but returned no sysName/sysDescr.") }.joinToString("\n")
                    },
                    onFailure = { "No SNMP response from $ip (device may not run SNMP, or uses a non-default community string)." },
                )
            }
            "check_exposures" -> {
                val ip = requireIp(arguments)
                dataSource.checkExposures(ip).fold(
                    onSuccess = { findings ->
                        if (findings.isEmpty()) "No common exposures found on $ip (checked ~16 paths)."
                        else findings.joinToString("\n") { "⚠ ${it.path} — ${it.name}" }
                    },
                    onFailure = { e -> throw e },
                )
            }
            "lookup_cve" -> {
                val keyword = requireArg(arguments, "keyword")
                dataSource.lookupCve(keyword).fold(
                    onSuccess = { findings ->
                        if (findings.isEmpty()) "No CVEs found matching '$keyword'."
                        else findings.joinToString("\n\n") { f ->
                            "${f.cveId}" + (f.severity?.let { " [$it]" } ?: "") + "\n${f.summary}"
                        }
                    },
                    onFailure = { e -> throw e },
                )
            }
            "check_kev" -> {
                val cveId = requireArg(arguments, "cve_id")
                dataSource.checkKev(cveId).fold(
                    onSuccess = { entry ->
                        if (entry == null) "$cveId is NOT on CISA's Known Exploited Vulnerabilities catalog."
                        else buildString {
                            appendLine("⚠ ${entry.cveId} IS on CISA's KEV catalog — confirmed active in-the-wild exploitation.")
                            appendLine("Name: ${entry.name}")
                            appendLine("Added: ${entry.dateAdded}")
                            if (entry.knownRansomwareUse) appendLine("Known ransomware campaign use: YES")
                            if (entry.requiredAction.isNotBlank()) append("Required action: ${entry.requiredAction}")
                        }.trim()
                    },
                    onFailure = { e -> throw e },
                )
            }
            "check_ssvc" -> {
                val cveId = requireArg(arguments, "cve_id")
                dataSource.checkSsvc(cveId).fold(
                    onSuccess = { assessment ->
                        if (assessment == null) "No SSVC assessment available for $cveId (CISA hasn't enriched this CVE)."
                        else buildString {
                            appendLine("SSVC assessment for $cveId:")
                            assessment.exploitation?.let { appendLine("Exploitation: $it") }
                            assessment.automatable?.let { appendLine("Automatable: $it") }
                            assessment.technicalImpact?.let { append("Technical Impact: $it") }
                        }.trim()
                    },
                    onFailure = { e -> throw e },
                )
            }
            "dns_lookup" -> {
                val domain = requireArg(arguments, "domain")
                val type = optionalArg(arguments, "record_type")?.uppercase() ?: "A"
                dataSource.dnsLookup(domain, type).fold(
                    onSuccess = { records ->
                        if (records.isEmpty()) "No $type records found for $domain."
                        else records.joinToString("\n") { "${it.type} ${it.name} -> ${it.data} (TTL ${it.ttl})" }
                    },
                    onFailure = { e -> throw e },
                )
            }
            "whois_lookup" -> {
                val query = requireArg(arguments, "query")
                dataSource.whoisLookup(query).fold(
                    onSuccess = { text -> text.trim().take(1500) },
                    onFailure = { e -> throw e },
                )
            }
            "snmp_walk" -> {
                val ip = requireIp(arguments)
                dataSource.snmpWalk(ip).fold(
                    onSuccess = { entries ->
                        if (entries.isEmpty()) "No SNMP interface data from $ip."
                        else entries.entries.joinToString("\n") { (oid, value) -> "$oid = $value" }
                    },
                    onFailure = { "No SNMP walk data from $ip (device may not run SNMP, use a non-default community, or not support GetBulk)." },
                )
            }
            "snmp_bruteforce" -> {
                val ip = requireIp(arguments)
                dataSource.snmpBruteForce(ip).fold(
                    onSuccess = { (community, info) ->
                        "Community string found: \"$community\"\n" +
                            listOfNotNull(info.sysName?.let { "sysName: $it" }, info.sysDescr?.let { "sysDescr: $it" }).joinToString("\n")
                    },
                    onFailure = { "No common SNMP community string worked for $ip." },
                )
            }
            "check_default_creds" -> {
                val ip = requireIp(arguments)
                dataSource.checkDefaultCreds(ip).fold(
                    onSuccess = { hit -> "⚠ ${hit.product} accepted default credentials: ${hit.username.ifEmpty { "<blank>" }} / ${hit.password.ifEmpty { "<blank>" }}" },
                    onFailure = { "No Tomcat default credential worked on $ip (no exposed Tomcat Manager, or defaults were changed)." },
                )
            }
            "subnet_calc" -> {
                val ip = requireArg(arguments, "ip_address")
                val prefix = requireArg(arguments, "prefix_length").trim().toIntOrNull()
                    ?: throw IllegalArgumentException("prefix_length must be a number 0-32")
                dataSource.subnetCalc(ip, prefix).fold(
                    onSuccess = { c ->
                        "Network: ${c.networkAddress}/${c.prefixLength}\nNetmask: ${c.netmask}\n" +
                            "Broadcast: ${c.broadcastAddress}\nUsable range: ${c.firstUsable} - ${c.lastUsable}\nUsable hosts: ${c.usableHostCount}"
                    },
                    onFailure = { e -> throw e },
                )
            }
            "ct_log_subdomains" -> {
                val domain = requireArg(arguments, "domain")
                dataSource.ctLogSearch(domain).fold(
                    onSuccess = { result ->
                        if (result.subdomains.isEmpty()) "No certificates found for $domain in CT logs."
                        else {
                            val subs = result.subdomains.take(50)
                            "Subdomains from CT logs (${result.subdomains.size} total, showing ${subs.size}):\n" +
                                subs.joinToString("\n") + "\n\nMost recent certs:\n" +
                                result.recentCerts.take(5).joinToString("\n") { "${it.commonName} (issued ${it.entryTimestamp}, by ${it.issuerName})" }
                        }
                    },
                    onFailure = { e -> throw e },
                )
            }
            "typosquat_check" -> {
                val domain = requireArg(arguments, "domain")
                dataSource.typosquatCheck(domain).fold(
                    onSuccess = { candidates ->
                        val registered = candidates.filter { it.registered }
                        if (registered.isEmpty()) "No registered typo/lookalike variants found for $domain (checked ${candidates.size} variants)."
                        else "⚠ Registered lookalike domains (potential impersonation) for $domain:\n" +
                            registered.joinToString("\n") { "- ${it.domain}" } +
                            "\n\n(${candidates.size - registered.size} other variants checked were unregistered.)"
                    },
                    onFailure = { e -> throw e },
                )
            }
            "epss_score" -> {
                val cveId = requireArg(arguments, "cve_id")
                dataSource.epssScore(cveId).fold(
                    onSuccess = { score ->
                        if (score == null) "No EPSS score available for $cveId."
                        else "EPSS for $cveId: ${"%.1f".format(score * 100)}% probability of exploitation in the next 30 days."
                    },
                    onFailure = { e -> throw e },
                )
            }
            "dns_email_security" -> {
                val domain = requireArg(arguments, "domain")
                dataSource.dnsHardening(domain).fold(
                    onSuccess = { it },
                    onFailure = { e -> throw e },
                )
            }
            "check_greynoise" -> {
                val ip = requireIp(arguments)
                dataSource.greynoise(ip).fold(
                    onSuccess = { r ->
                        buildString {
                            append("$ip -- ")
                            when {
                                r.riot -> append("known benign service (RIOT)${r.name?.let { ": $it" } ?: ""}")
                                r.noise -> append("known internet noise/scanner${r.classification?.let { " ($it)" } ?: ""}${r.name?.let { " -- $it" } ?: ""}")
                                else -> append("not observed by GreyNoise (not a known mass scanner)")
                            }
                            r.lastSeen?.let { append("; last seen $it") }
                        }
                    },
                    onFailure = { e -> throw e },
                )
            }
            "check_abuseipdb" -> {
                val ip = requireIp(arguments)
                val result = dataSource.abuseipdb(ip)
                    ?: return@runCatching "No AbuseIPDB API key configured -- add a free one in GreyRecon's Settings first."
                result.fold(
                    onSuccess = { r ->
                        "$ip -- abuse confidence ${r.abuseConfidenceScore}/100, ${r.totalReports} report(s)" +
                            listOfNotNull(r.countryCode, r.isp, r.usageType).joinToString(", ").let { if (it.isBlank()) "" else "\n$it" } +
                            (r.lastReportedAt?.let { "\nLast reported: $it" } ?: "")
                    },
                    onFailure = { e -> throw e },
                )
            }
            "audit_router_upnp" -> {
                dataSource.auditRouterUpnp().fold(
                    onSuccess = { r ->
                        buildString {
                            appendLine("Router external IP: ${r.externalIp ?: "unknown"}")
                            if (r.mappings.isEmpty()) append("No UPnP port-forwards are open on the router.")
                            else {
                                appendLine("Open UPnP port-forwards (${r.mappings.size}):")
                                append(r.mappings.joinToString("\n") { m ->
                                    "  ${m.protocol} ext:${m.externalPort} -> ${m.internalClient}:${m.internalPort}" +
                                        (if (m.description.isBlank()) "" else " (${m.description})") +
                                        (if (!m.enabled) " [disabled]" else "")
                                })
                            }
                        }.trim()
                    },
                    onFailure = { e -> throw e },
                )
            }
            "netbios_lookup" -> {
                val ip = requireIp(arguments)
                dataSource.netbios(ip).fold(
                    onSuccess = { info ->
                        listOfNotNull(
                            info.hostname?.let { "Hostname: $it" },
                            info.workgroup?.let { "Workgroup/domain: $it" },
                            info.macAddress?.let { "MAC: $it" },
                            info.allNames.takeIf { it.isNotEmpty() }?.let { "NetBIOS names: ${it.joinToString(", ")}" },
                        ).ifEmpty { listOf("NetBIOS responded but returned no usable names.") }.joinToString("\n")
                    },
                    onFailure = { "No NetBIOS response from $ip (device may not run NetBIOS/SMB)." },
                )
            }
            else -> throw IllegalArgumentException("Unknown tool: $name")
        }
    }

    private fun formatSecurityResult(result: com.greyrecon.app.engine.security.SecurityCheckResult): String = buildString {
        appendLine("${result.url} — HTTP ${result.statusCode}")
        if (result.presentHeaders.isEmpty()) appendLine("No security headers present.")
        else result.presentHeaders.forEach { (k, v) -> appendLine("$k: $v") }
        if (result.missingHeaders.isNotEmpty()) appendLine("Missing: ${result.missingHeaders.joinToString(", ")}")
        result.tls?.let { tls ->
            appendLine("TLS subject: ${tls.subject}")
            appendLine("TLS issuer: ${tls.issuer}")
            appendLine("Expires ${tls.notAfter}" + (if (tls.expired) " (EXPIRED)" else "") + (if (tls.selfSigned) " · self-signed" else ""))
            if (tls.protocol != null || tls.cipherSuite != null) {
                appendLine(
                    listOfNotNull(tls.protocol, tls.cipherSuite).joinToString(" / ") +
                        (if (tls.weakCipherOrProtocol) " (WEAK)" else "")
                )
            }
        }
        if (result.detectedTech.isNotEmpty()) append("Detected: ${result.detectedTech.joinToString(", ")}")
    }

    private fun requireIp(arguments: JsonObject?): String =
        arguments?.get("ip_address")?.jsonPrimitive?.contentOrNull()
            ?: throw IllegalArgumentException("ip_address is required")

    private fun requireArg(arguments: JsonObject?, key: String): String =
        arguments?.get(key)?.jsonPrimitive?.contentOrNull()
            ?: throw IllegalArgumentException("$key is required")

    private fun optionalArg(arguments: JsonObject?, key: String): String? =
        arguments?.get(key)?.jsonPrimitive?.contentOrNull()?.takeIf { it.isNotBlank() }

    private fun JsonPrimitive.contentOrNull(): String? = if (this.isString) content else null

    private fun formatDevices(devices: List<Device>): String {
        if (devices.isEmpty()) return "No devices found."
        return devices.joinToString("\n") { device ->
            buildString {
                append(device.ipAddress)
                device.vendor?.let { append(" — $it") }
                append(" [${device.deviceType.name}]")
                device.hostname?.let { append(" host=$it") }
                if (device.isGateway) append(" (gateway)")
                if (device.openPorts.isNotEmpty()) {
                    append(" ports=${device.openPorts.joinToString(",") { it.number.toString() }}")
                }
            }
        }
    }
}
