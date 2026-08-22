package com.greyrecon.app.engine.score

import com.greyrecon.app.engine.model.Device
import com.greyrecon.app.ui.main.ActionResult
import com.greyrecon.app.ui.main.DeviceActions

data class ScoreFinding(
    val ipAddress: String,
    val label: String,
    val detail: String,
    val pointsDeducted: Int,
)

data class NetworkScoreResult(
    val score: Int,
    val grade: String,
    val totalDevices: Int,
    val devicesPortScanned: Int,
    val devicesShodanChecked: Int,
    val devicesSecurityChecked: Int,
    val devicesNvdChecked: Int,
    val findings: List<ScoreFinding>,
)

/**
 * Scores only what's actually been inspected this session -- port scans, Shodan
 * lookups, and security checks are all on-demand per device, so most devices in
 * a fresh scan carry no signal either way. A high score on a mostly-unchecked
 * network means "nothing bad found yet", not "nothing bad exists" -- the
 * coverage counts in [NetworkScoreResult] exist so the UI can say that plainly
 * instead of implying more confidence than the data supports.
 */
object NetworkScore {

    private val HIGH_RISK_PORTS = mapOf(
        23 to "Telnet (unencrypted remote access)",
        21 to "FTP (unencrypted file transfer)",
        3389 to "RDP (remote desktop, common ransomware entry point)",
        5900 to "VNC (remote desktop, often no auth)",
        445 to "SMB (frequent exploit target -- EternalBlue and successors)",
    )

    private const val HIGH_RISK_PORT_BUDGET = 45
    private const val OTHER_PORT_BUDGET = 20
    private const val SHODAN_BUDGET = 40
    private const val HEADER_BUDGET = 20
    private const val TLS_BUDGET = 30
    private const val NVD_BUDGET = 25

    fun compute(devices: List<Device>, actions: Map<String, DeviceActions>): NetworkScoreResult {
        var score = 100
        val findings = mutableListOf<ScoreFinding>()

        var highRiskPortBudget = HIGH_RISK_PORT_BUDGET
        var otherPortBudget = OTHER_PORT_BUDGET
        var shodanBudget = SHODAN_BUDGET
        var headerBudget = HEADER_BUDGET
        var tlsBudget = TLS_BUDGET
        var nvdBudget = NVD_BUDGET

        var portScannedCount = 0
        var shodanCheckedCount = 0
        var securityCheckedCount = 0
        var nvdCheckedCount = 0

        devices.forEach { device ->
            val action = actions[device.ipAddress]

            val portsChecked = action?.ports as? ActionResult.Success
            if (portsChecked != null) {
                portScannedCount++
                var otherCount = 0
                portsChecked.value.forEach { port ->
                    val risk = HIGH_RISK_PORTS[port.number]
                    if (risk != null) {
                        val deduct = minOf(15, highRiskPortBudget)
                        if (deduct > 0) {
                            highRiskPortBudget -= deduct
                            score -= deduct
                            findings += ScoreFinding(device.ipAddress, "High-risk port open", "${port.number}/${port.protocol} -- $risk", deduct)
                        }
                    } else {
                        otherCount++
                    }
                }
                if (otherCount > 2) {
                    val deduct = minOf(3 * (otherCount - 2), otherPortBudget)
                    if (deduct > 0) {
                        otherPortBudget -= deduct
                        score -= deduct
                        findings += ScoreFinding(device.ipAddress, "Multiple open ports", "$otherCount non-standard ports open beyond the first 2", deduct)
                    }
                }
            }

            val shodanChecked = action?.shodanFindings as? ActionResult.Success
            if (shodanChecked != null) {
                shodanCheckedCount++
                shodanChecked.value.forEach { finding ->
                    val severe = finding.severity?.lowercase()?.let { "critical" in it || "high" in it } == true
                    val deduct = minOf(if (severe) 10 else 5, shodanBudget)
                    if (deduct > 0) {
                        shodanBudget -= deduct
                        score -= deduct
                        findings += ScoreFinding(device.ipAddress, "Shodan exposure", finding.summary, deduct)
                    }
                }
            }

            val securityChecked = (action?.securityCheck as? ActionResult.Success)?.value
            if (securityChecked != null) {
                securityCheckedCount++
                if ("Strict-Transport-Security" in securityChecked.missingHeaders) {
                    val deduct = minOf(5, headerBudget)
                    if (deduct > 0) {
                        headerBudget -= deduct
                        score -= deduct
                        findings += ScoreFinding(device.ipAddress, "Missing HSTS", "${securityChecked.url} doesn't send Strict-Transport-Security", deduct)
                    }
                }
                securityChecked.tls?.let { tls ->
                    if (tls.selfSigned || tls.expired) {
                        val deduct = minOf(15, tlsBudget)
                        if (deduct > 0) {
                            tlsBudget -= deduct
                            score -= deduct
                            val why = listOfNotNull("self-signed".takeIf { tls.selfSigned }, "expired".takeIf { tls.expired })
                                .joinToString(", ")
                            findings += ScoreFinding(device.ipAddress, "TLS certificate issue", "${securityChecked.url} -- $why", deduct)
                        }
                    }
                    if (tls.weakCipherOrProtocol) {
                        val deduct = minOf(10, tlsBudget)
                        if (deduct > 0) {
                            tlsBudget -= deduct
                            score -= deduct
                            val why = listOfNotNull(tls.protocol, tls.cipherSuite).joinToString(" / ")
                            findings += ScoreFinding(device.ipAddress, "Weak TLS cipher/protocol", "${securityChecked.url} -- $why", deduct)
                        }
                    }
                }
            }

            val nvdChecked = action?.nvdFindings as? ActionResult.Success
            if (nvdChecked != null) {
                nvdCheckedCount++
                // EPSS, not CVSS severity -- a critical-severity CVE nobody exploits shouldn't cost more
                // than a medium-severity one actively being exploited in the wild. Findings with no EPSS
                // score (lookup failed/unavailable) are skipped rather than guessed at.
                nvdChecked.value.forEach { finding ->
                    val epss = finding.epssScore ?: return@forEach
                    val deduct = when {
                        epss >= 0.5 -> minOf(12, nvdBudget)
                        epss >= 0.1 -> minOf(6, nvdBudget)
                        else -> 0
                    }
                    if (deduct > 0) {
                        nvdBudget -= deduct
                        score -= deduct
                        findings += ScoreFinding(device.ipAddress, "High EPSS-risk CVE", "${finding.cveId} -- ${"%.1f".format(epss * 100)}% predicted exploitation probability", deduct)
                    }
                }
            }
        }

        val clamped = score.coerceIn(0, 100)
        val grade = when {
            clamped >= 90 -> "A"
            clamped >= 75 -> "B"
            clamped >= 60 -> "C"
            clamped >= 40 -> "D"
            else -> "F"
        }

        return NetworkScoreResult(
            score = clamped,
            grade = grade,
            totalDevices = devices.size,
            devicesPortScanned = portScannedCount,
            devicesShodanChecked = shodanCheckedCount,
            devicesSecurityChecked = securityCheckedCount,
            devicesNvdChecked = nvdCheckedCount,
            findings = findings.sortedByDescending { it.pointsDeducted },
        )
    }
}
