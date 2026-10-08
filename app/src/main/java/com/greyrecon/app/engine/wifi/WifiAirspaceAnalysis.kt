package com.greyrecon.app.engine.wifi

/** A finding about the airspace rather than about one access point. */
data class AirspaceFinding(
    val severity: Severity,
    val title: String,
    val detail: String,
) {
    enum class Severity { INFO, WARNING, CRITICAL }
}

/**
 * Turns a flat list of access points into statements worth reading.
 *
 * A channel graph and a signal list are table stakes -- every WiFi analyzer has them, and they
 * answer "why is my WiFi slow". The findings here answer the question this app is actually for,
 * which is "is something wrong here", and they are the part no consumer scanner does properly.
 *
 * The evil-twin check is the one that earns its place. An attacker running a rogue AP clones a
 * network's SSID, and the giveaway is that the clone cannot reproduce the original's credentials,
 * so it either runs open or drops to weaker security. Seeing one SSID advertised by several
 * BSSIDs is completely normal -- that is what a mesh or a multi-AP home kit looks like -- so
 * multiple BSSIDs alone is deliberately *not* reported. A **security mismatch between BSSIDs
 * sharing an SSID** is the thing that is hard to explain innocently, and that is what gets
 * flagged.
 */
object WifiAirspaceAnalysis {

    fun analyse(accessPoints: List<AccessPoint>): List<AirspaceFinding> {
        if (accessPoints.isEmpty()) return emptyList()
        val findings = mutableListOf<AirspaceFinding>()

        findings += evilTwinFindings(accessPoints)
        findings += weakSecurityFindings(accessPoints)
        findings += hiddenNetworkFinding(accessPoints)
        findings += congestionFindings(accessPoints)

        return findings.sortedByDescending { it.severity.ordinal }
    }

    /**
     * Same SSID, different BSSIDs, *different security levels*. A legitimate mesh presents the
     * same security on every node; a clone usually cannot.
     */
    private fun evilTwinFindings(aps: List<AccessPoint>): List<AirspaceFinding> =
        aps.filter { !it.isHidden }
            .groupBy { it.ssid!! }
            .filter { (_, group) -> group.size > 1 }
            .mapNotNull { (ssid, group) ->
                val levels = group.map { it.security }.distinct()
                if (levels.size < 2) return@mapNotNull null

                val weakest = group.minByOrNull { it.security.rank } ?: return@mapNotNull null
                val strongest = group.maxByOrNull { it.security.rank } ?: return@mapNotNull null
                if (weakest.security.rank >= strongest.security.rank) return@mapNotNull null

                AirspaceFinding(
                    severity = if (weakest.security == Security.OPEN) {
                        AirspaceFinding.Severity.CRITICAL
                    } else {
                        AirspaceFinding.Severity.WARNING
                    },
                    title = "Possible rogue access point: $ssid",
                    detail = "Two access points advertise this network with different security. " +
                        "${strongest.bssid} uses ${strongest.security.label}, but ${weakest.bssid} " +
                        "uses ${weakest.security.label}. A mesh presents the same security on every " +
                        "node; a cloned network usually cannot, because it does not have the real " +
                        "credentials. Avoid connecting until you can account for the second one.",
                )
            }

    private fun weakSecurityFindings(aps: List<AccessPoint>): List<AirspaceFinding> {
        val weak = aps.filter { it.security == Security.WEP || it.security == Security.WPA }
        if (weak.isEmpty()) return emptyList()
        val names = weak.mapNotNull { it.ssid }.distinct().take(5).joinToString(", ")
        return listOf(
            AirspaceFinding(
                severity = AirspaceFinding.Severity.WARNING,
                title = "${weak.size} network(s) using outdated encryption",
                detail = "WEP and the original WPA are both broken and can be cracked offline from " +
                    "captured traffic. Affected: $names.",
            )
        )
    }

    private fun hiddenNetworkFinding(aps: List<AccessPoint>): List<AirspaceFinding> {
        val hidden = aps.count { it.isHidden }
        if (hidden == 0) return emptyList()
        return listOf(
            AirspaceFinding(
                severity = AirspaceFinding.Severity.INFO,
                title = "$hidden hidden network(s) nearby",
                detail = "These broadcast no SSID. That is not a security measure -- the name still " +
                    "leaks whenever a client connects -- but it is worth knowing one is here.",
            )
        )
    }

    /**
     * Only 2.4 GHz is worth reporting on. It has three non-overlapping channels in practice
     * (1, 6, 11) and is where congestion actually hurts; 5 and 6 GHz have enough room that a
     * crowded-channel warning there would be noise.
     */
    private fun congestionFindings(aps: List<AccessPoint>): List<AirspaceFinding> {
        val band24 = aps.filter { it.band == Band.GHZ_2_4 && it.channel > 0 }
        if (band24.size < 4) return emptyList()

        val byChannel = band24.groupingBy { it.channel }.eachCount()
        val busiest = byChannel.maxByOrNull { it.value } ?: return emptyList()
        if (busiest.value < 3) return emptyList()

        val best = listOf(1, 6, 11).minByOrNull { candidate -> byChannel[candidate] ?: 0 }
        return listOf(
            AirspaceFinding(
                severity = AirspaceFinding.Severity.INFO,
                title = "2.4 GHz channel ${busiest.key} is crowded",
                detail = "${busiest.value} networks share channel ${busiest.key}. Of the three " +
                    "non-overlapping channels, ${best ?: 1} is currently the quietest.",
            )
        )
    }
}
