package com.greyrecon.app.engine.tools

/**
 * Evaluates a domain's email/DNS security posture from public DNS records, over the same
 * DNS-over-HTTPS client [DnsLookup] already uses -- no key, no new dependency, purely a defensive
 * OSINT check on a domain the user owns or is auditing. Complements the WHOIS/RDAP/CT modules on the
 * same target: those say who owns it and what certs exist; this says whether its mail can be spoofed
 * and whether its DNS is hardened.
 *
 * Checks: SPF and DMARC (can someone forge mail from this domain?), DNSSEC (is the zone signed?),
 * CAA (is cert issuance restricted to specific CAs?), and MTA-STS (is inbound mail forced over TLS?).
 * A missing/weak record is the finding -- e.g. no SPF, or DMARC at `p=none` (monitoring only, not
 * actually rejecting spoofed mail).
 */
object DnsHardeningChecker {

    suspend fun check(domain: String): Result<String> = runCatching {
        val d = domain.trim().removePrefix("https://").removePrefix("http://").substringBefore("/")
        val out = StringBuilder()

        val txt = DnsLookup.lookup(d, "TXT").getOrDefault(emptyList()).map { it.data.trim('"') }
        val spf = txt.firstOrNull { it.startsWith("v=spf1") }
        out.appendLine("SPF: " + (spf?.let { "present -- $it" } ?: "MISSING -- mail can be spoofed from this domain"))

        val dmarcTxt = DnsLookup.lookup("_dmarc.$d", "TXT").getOrDefault(emptyList()).map { it.data.trim('"') }
        val dmarc = dmarcTxt.firstOrNull { it.startsWith("v=DMARC1") }
        if (dmarc == null) {
            out.appendLine("DMARC: MISSING -- no policy against spoofed mail")
        } else {
            val policy = Regex("p=(\\w+)").find(dmarc)?.groupValues?.getOrNull(1) ?: "?"
            val note = if (policy.equals("none", ignoreCase = true)) "p=none (monitoring only -- NOT rejecting spoofed mail)" else "p=$policy"
            out.appendLine("DMARC: present -- $note")
        }

        val dnskey = DnsLookup.lookup(d, "DNSKEY").getOrDefault(emptyList())
        out.appendLine("DNSSEC: " + if (dnskey.isNotEmpty()) "enabled (zone is signed)" else "not detected -- DNS responses aren't cryptographically signed")

        val caa = DnsLookup.lookup(d, "CAA").getOrDefault(emptyList())
        out.appendLine("CAA: " + if (caa.isNotEmpty()) "present -- ${caa.size} record(s) restricting which CAs may issue certs" else "none -- any CA can issue certs for this domain")

        val mtaSts = DnsLookup.lookup("_mta-sts.$d", "TXT").getOrDefault(emptyList()).map { it.data.trim('"') }.firstOrNull { it.contains("v=STSv1") }
        out.appendLine("MTA-STS: " + if (mtaSts != null) "present -- inbound mail is forced over TLS" else "none")

        out.toString().trim()
    }
}
