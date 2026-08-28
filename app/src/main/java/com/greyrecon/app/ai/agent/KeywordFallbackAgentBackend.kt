package com.greyrecon.app.ai.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A keyword-matched [AgentBackend] for when no BYOK key is configured -- so the AI Assistant screen
 * isn't a dead end without one. Inspired by (not ported from -- xpfarm's own code is GPL-3.0, this is
 * an independent reimplementation of the same idea, which is also the user's own design from their
 * xpfarm fork) a "keyword fallback when no API key configured" pattern: no LLM call, no tool-calling
 * loop, just pattern-matching the user's text against a handful of recognized intents and running the
 * matching tool(s) directly through the same [ToolExecutor]/[com.greyrecon.app.mcp.McpTools] registry
 * the real LLM-backed backends use.
 *
 * Deliberately narrow scope -- this is not free-form NLP and doesn't pretend to be. It recognizes:
 * a scan request, a bare IPv4 address (checks that device's security posture), and a bare CVE ID
 * (runs the full KEV/EPSS/SSVC/VulnCheck-KEV prioritization stack against it). Anything else gets a
 * plain-text list of what it does understand, so the user isn't left guessing why their message did
 * nothing.
 */
class KeywordFallbackAgentBackend(private val executor: ToolExecutor) : AgentBackend {

    override suspend fun send(userText: String, onEvent: (AgentEvent) -> Unit) {
        val text = userText.trim()
        val cveMatch = CVE_PATTERN.find(text)
        val ipMatch = IPV4_PATTERN.find(text)

        when {
            cveMatch != null -> checkCve(cveMatch.value, onEvent)
            ipMatch != null -> checkDevice(ipMatch.value, onEvent)
            SCAN_KEYWORDS.any { text.contains(it, ignoreCase = true) } -> scanNetwork(onEvent)
            else -> onEvent(AgentEvent.Answer(HELP_TEXT))
        }
    }

    private suspend fun scanNetwork(onEvent: (AgentEvent) -> Unit) {
        val result = runTool("scan_network", null, onEvent)
        onEvent(AgentEvent.Answer(result))
    }

    private suspend fun checkDevice(ip: String, onEvent: (AgentEvent) -> Unit) {
        val result = runTool("check_security", buildJsonObject { put("ip_address", ip) }, onEvent)
        onEvent(AgentEvent.Answer(result))
    }

    /** Runs every CVE-prioritization tool GreyRecon has, since this mode can't reason about which one to pick -- it just shows all of them. */
    private suspend fun checkCve(cveId: String, onEvent: (AgentEvent) -> Unit) {
        val args = buildJsonObject { put("cve_id", cveId) }
        val results = listOf("check_kev", "check_vulncheck_kev", "epss_score", "check_ssvc")
            .map { tool -> tool to runTool(tool, args, onEvent) }
        onEvent(AgentEvent.Answer(results.joinToString("\n\n") { (_, result) -> result }))
    }

    private suspend fun runTool(name: String, args: JsonObject?, onEvent: (AgentEvent) -> Unit): String {
        onEvent(AgentEvent.ToolStart(name, argsPreview(args)))
        val result = executor.execute(name, args)
        onEvent(AgentEvent.ToolEnd(name, result.lineSequence().firstOrNull()?.take(80).orEmpty()))
        return result
    }

    private fun argsPreview(args: JsonObject?): String =
        args?.entries?.joinToString(", ") { (k, v) -> "$k=${v.toString().trim('"')}" }.orEmpty()

    private companion object {
        val CVE_PATTERN = Regex("CVE-\\d{4}-\\d+", RegexOption.IGNORE_CASE)
        val IPV4_PATTERN = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
        val SCAN_KEYWORDS = listOf("scan", "what's on", "whats on", "devices", "network")

        val HELP_TEXT = "No AI provider key is configured, so I can only respond to a few direct " +
            "commands rather than free-form questions:\n\n" +
            "• Say \"scan\" (or similar) to run a network scan\n" +
            "• Paste a device's IP address to check its security posture\n" +
            "• Paste a CVE ID (e.g. CVE-2021-44228) to check it against KEV, VulnCheck's KEV, EPSS, and SSVC\n\n" +
            "Add an API key in Settings for full conversational answers."
    }
}
