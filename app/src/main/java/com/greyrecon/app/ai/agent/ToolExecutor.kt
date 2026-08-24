package com.greyrecon.app.ai.agent

import com.greyrecon.app.mcp.McpTools
import com.greyrecon.app.mcp.ScanDataSource
import com.greyrecon.app.mcp.ToolDef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The bridge between the agent loop and GreyRecon's real scan engine. Deliberately
 * reuses the exact same [McpTools] registry the MCP server exposes to external clients --
 * one source of truth for what the AI can do, whether it's the on-device agent or a
 * remote nanobot instance. Every tool here is read-only recon on the user's own network
 * (scan / port-scan / Shodan lookup / security-header check) or a benign wake-on-LAN
 * packet, so the agent can run them without a per-call confirmation gate.
 */
class ToolExecutor(private val dataSource: ScanDataSource) {

    /** The tools the agent may call, as GreyRecon's provider-neutral [ToolDef] list. */
    fun definitions(): List<ToolDef> = McpTools.definitions()

    /**
     * Run one tool by name with already-parsed [arguments], returning a plain-text result
     * the model can read back. Never throws -- a failure comes back as an "Error: ..." string
     * so the agent can see it and react (retry, pick a different tool, or explain to the user)
     * instead of the whole turn aborting.
     */
    suspend fun execute(name: String, arguments: JsonObject?): String =
        McpTools.call(name, arguments, dataSource).fold(
            onSuccess = { it },
            onFailure = { e -> "Error: ${e.message ?: e::class.simpleName}" },
        )

    /** Parse a raw JSON argument string (OpenAI tool_calls give arguments as a string) into an object, or null. */
    fun parseArgs(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { lenientJson.decodeFromString<JsonObject>(raw) }.getOrNull()
    }

    private companion object {
        val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
