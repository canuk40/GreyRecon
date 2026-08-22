package com.greyrecon.app.engine.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

data class TyposquatCandidate(val domain: String, val registered: Boolean)

/**
 * Generates a bounded set of realistic typo/homograph variants of a domain and checks each via
 * [RdapClient] for real registration -- a lightweight subset of `elceef/dnstwist`'s (Apache-2.0)
 * core algorithm class (character omission, adjacent transposition, common-lookalike substitution,
 * popular TLD swap), not a port of the full tool (dnstwist also does keyboard-adjacency typos,
 * bitsquatting, homoglyph Unicode confusables, and DNS/MX/SSDEEP-based similarity scoring against
 * live infrastructure -- genuinely out of scope for a single on-demand mobile check).
 *
 * A **registered** variant is the actionable signal here -- someone else holds a domain a real
 * user could easily mistype into, which is the concrete "org watch" use case this closes. An
 * unregistered variant just means nobody's grabbed it (yet); worth surfacing too, since a security
 * team might want to preemptively register it themselves, but it's not itself a finding.
 */
object TyposquatChecker {

    private val LOOKALIKE_SUBSTITUTIONS = mapOf('o' to '0', 'l' to '1', 'i' to '1', 'e' to '3', 's' to '5')
    private val COMMON_TLDS = listOf("com", "net", "org", "co", "info")

    suspend fun check(domain: String, maxCandidates: Int = 24): Result<List<TyposquatCandidate>> = withContext(Dispatchers.IO) {
        runCatching {
            val candidates = generateVariants(domain).take(maxCandidates)
            candidates.map { variant ->
                async {
                    val registered = RdapClient.lookupDomain(variant).isSuccess
                    TyposquatCandidate(variant, registered)
                }
            }.awaitAll()
        }
    }

    private fun generateVariants(domain: String): List<String> {
        val dotIndex = domain.lastIndexOf('.')
        if (dotIndex <= 0) return emptyList()
        val label = domain.substring(0, dotIndex)
        val tld = domain.substring(dotIndex + 1)
        val variants = linkedSetOf<String>()

        // Character omission -- drop each character once ("exmple.com").
        for (i in label.indices) {
            variants += (label.removeRange(i, i + 1)) + "." + tld
        }

        // Adjacent transposition -- swap each neighboring pair once ("examlpe.com").
        for (i in 0 until label.length - 1) {
            val chars = label.toCharArray()
            val tmp = chars[i]; chars[i] = chars[i + 1]; chars[i + 1] = tmp
            variants += String(chars) + "." + tld
        }

        // Lookalike character substitution ("ex4mple.com" style, one substitution at a time).
        label.forEachIndexed { i, c ->
            val replacement = LOOKALIKE_SUBSTITUTIONS[c.lowercaseChar()] ?: return@forEachIndexed
            variants += (label.substring(0, i) + replacement + label.substring(i + 1)) + "." + tld
        }

        // Common TLD swap ("example.net" instead of "example.com").
        COMMON_TLDS.filter { it != tld }.forEach { altTld -> variants += "$label.$altTld" }

        variants -= domain
        return variants.toList()
    }
}
