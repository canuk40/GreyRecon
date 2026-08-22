package com.greyrecon.app.ai

/**
 * BYOK provider abstraction, same shape as ObsidianBox Modern's AI provider layer:
 * the user supplies their own API key, we never see or pay for their usage.
 *
 * Free tier: this interface is called directly from the app to explain scan
 * results in plain language (which device looks risky, what a CVE means, etc).
 *
 * Pro tier (later): instead of calling a provider directly, scan data gets
 * exposed as an MCP tool that the user's own self-hosted nanobot instance
 * can query conversationally. That's a separate integration, not a 6th
 * AIProviderType -- MCP exposure lives in its own module.
 */
enum class AIProviderType {
    OPENAI,
    ANTHROPIC,
    GROQ,
    OPENROUTER,
    OLLAMA,
    DEEPSEEK,
}

data class AIProviderConfig(
    val type: AIProviderType,
    val apiKey: String,
    /** Only meaningful for OLLAMA (local endpoint) or OPENROUTER-style custom base URLs. */
    val baseUrl: String? = null,
    val model: String,
)

interface AIProvider {
    val type: AIProviderType

    /** Sends [prompt] plus [context] (e.g. serialized scan results) and returns the reply. */
    suspend fun complete(prompt: String, context: String): Result<String>
}
