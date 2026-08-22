package com.greyrecon.app.engine.cve

data class CveFinding(
    val cveId: String,
    val summary: String,
    val severity: String?,
    /** EPSS (first.org) 0.0-1.0 probability of real-world exploitation in the next 30 days -- null if the EPSS lookup failed or hasn't run. Independent of [severity]: a CVSS-critical CVE can have a low EPSS score, and vice versa. */
    val epssScore: Double? = null,
)
