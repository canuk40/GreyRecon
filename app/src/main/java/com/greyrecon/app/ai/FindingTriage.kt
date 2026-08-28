package com.greyrecon.app.ai

data class TriageVerdict(val truePositive: Boolean, val reason: String)

/**
 * LLM false-positive triage for signature/status-code-based security findings -- inspired by (not
 * ported from -- xpfarm's own code is GPL-3.0, this is an independent reimplementation of the same
 * idea, which is also the user's own design from their xpfarm fork) xpfarm's Nuclei-finding triage:
 * hand the raw evidence to the configured LLM and ask for a true/false-positive verdict plus a
 * one-sentence reason.
 *
 * [com.greyrecon.app.engine.security.ExposureChecker] and
 * [com.greyrecon.app.engine.security.DefaultCredsChecker] both work by matching a signature string or
 * a 2xx status code -- neither can tell a real exposure from a generic catch-all page that happens to
 * contain the signature text, or a "default credential" hit from a panel that has no real auth check
 * and returns 2xx for anything. A signature match can't reason about that; an LLM can.
 *
 * Optional and best-effort, same fallback posture as
 * [com.greyrecon.app.ai.agent.KeywordFallbackAgentBackend]: callers should only invoke this when an AI
 * provider key is configured, and treat any failure (network, parse, no key) as "couldn't triage" --
 * never suppress a real finding just because triage itself failed.
 */
object FindingTriage {

    suspend fun evaluate(config: AIProviderConfig, findingName: String, evidence: String): Result<TriageVerdict> {
        val prompt = buildString {
            appendLine("You are a security analyst reviewing an automated scanner finding for false positives.")
            appendLine("The scanner matched a signature or status code; it cannot reason about context. You can.")
            appendLine("Determine whether this finding is a TRUE POSITIVE (a real security issue) or a FALSE")
            appendLine("POSITIVE (e.g. a generic page that happens to contain the matched text, or a panel with")
            appendLine("no real authentication that would return success for any input).")
            appendLine()
            appendLine("Finding: $findingName")
            appendLine("Evidence: ${evidence.take(500)}")
            appendLine()
            appendLine("Respond with exactly one line, no other text:")
            appendLine("VERDICT: true_positive|false_positive -- <one sentence reason>")
        }
        return AIProviderFactory.create(config).complete(prompt, "").mapCatching { parseVerdict(it) }
    }

    private fun parseVerdict(raw: String): TriageVerdict {
        val line = raw.lineSequence().firstOrNull { "VERDICT" in it.uppercase() } ?: raw
        // Default to true-positive on any ambiguity -- a missed false-positive label just leaves a
        // real finding showing (today's behavior); a wrongly-suppressed true positive hides real risk.
        val truePositive = "false_positive" !in line.lowercase()
        val reason = line.substringAfter("--", "").trim().ifEmpty { raw.trim().take(200) }
        return TriageVerdict(truePositive, reason)
    }
}
