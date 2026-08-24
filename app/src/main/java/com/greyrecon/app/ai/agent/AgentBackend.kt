package com.greyrecon.app.ai.agent

import com.greyrecon.app.ai.AIProviderType
import com.greyrecon.app.ai.OpenAICompatibleProvider
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.mcp.ScanDataSource

/**
 * A single provider's tool-calling agent loop. Each backend keeps its own conversation
 * history in that provider's native wire format (OpenAI flat messages vs. Anthropic
 * content blocks), runs the full "call model -> maybe run tools -> call model again"
 * loop internally, and streams [AgentEvent]s out as it goes.
 */
interface AgentBackend {
    /**
     * Send one user turn and drive the tool loop to completion, emitting events via [onEvent].
     * Appends to this backend's own history so multi-turn context is preserved across calls.
     */
    suspend fun send(userText: String, onEvent: (AgentEvent) -> Unit)
}

/** Why an agent couldn't be built for the current settings -- surfaced to the user, not thrown blindly. */
sealed interface AgentUnavailable {
    val message: String

    data class NoKey(override val message: String) : AgentUnavailable
}

object AgentFactory {

    /**
     * Build an agent backend for the provider the user has selected in Settings, using their
     * stored key. Returns null with a reason if no usable key is configured, so the chat screen
     * can tell the user exactly what to add rather than failing silently on first message.
     */
    fun create(
        keyStore: SecureKeyStore,
        dataSource: ScanDataSource,
    ): Result<AgentBackend> {
        val executor = ToolExecutor(dataSource)
        return when (keyStore.aiProvider) {
            AIProviderType.ANTHROPIC -> {
                val key = keyStore.anthropicKey
                    ?: return Result.failure(NoKeyException("Add your Anthropic API key in Settings to use the AI Assistant."))
                Result.success(AnthropicAgentBackend(apiKey = key, executor = executor))
            }
            // Every other provider is OpenAI-compatible. The only OpenAI-compatible key the app
            // stores is the DeepSeek one, so that's what the agent uses, pointed at the selected
            // provider's base URL (DeepSeek by default).
            else -> {
                val provider = keyStore.aiProvider
                val key = keyStore.deepseekKey
                    ?: return Result.failure(NoKeyException("Add your ${provider.name.lowercase().replaceFirstChar { it.uppercase() }} API key in Settings to use the AI Assistant."))
                val baseUrl = OpenAICompatibleProvider.baseUrlFor(provider)
                val model = defaultModelFor(provider)
                Result.success(OpenAiAgentBackend(apiKey = key, baseUrl = baseUrl, model = model, executor = executor))
            }
        }
    }

    private fun defaultModelFor(type: AIProviderType): String = when (type) {
        AIProviderType.DEEPSEEK -> "deepseek-chat"
        AIProviderType.OPENAI -> "gpt-4o-mini"
        AIProviderType.GROQ -> "llama-3.3-70b-versatile"
        AIProviderType.OPENROUTER -> "openai/gpt-4o-mini"
        AIProviderType.OLLAMA -> "llama3.1"
        AIProviderType.ANTHROPIC -> "claude-sonnet-5" // unreachable here, kept exhaustive
    }
}

class NoKeyException(message: String) : Exception(message)

/** Shared system prompt: makes the model a proactive recon operator that uses the tools rather than guessing. */
internal const val AGENT_SYSTEM_PROMPT =
    "You are GreyRecon's on-device network assistant. You help the user understand and audit " +
    "the WiFi network they are on and the devices on it. You have tools that run real scans on " +
    "the user's own network -- prefer calling a tool to get real data over guessing or asking the " +
    "user for information a tool can find. When the user asks what's on their network, run a scan. " +
    "When they ask about a specific device, use its IP with the port/security/Shodan tools. Keep " +
    "answers concise and concrete; cite the actual IPs, ports, and findings the tools return. If a " +
    "tool reports no data yet, run scan_network first. Never claim to have done something a tool " +
    "did not actually return."

internal const val AGENT_MAX_TOOL_ROUNDS = 6
