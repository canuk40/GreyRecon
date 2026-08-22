package com.greyrecon.app.ai

object AIProviderFactory {
    fun create(config: AIProviderConfig): AIProvider = when (config.type) {
        AIProviderType.ANTHROPIC -> AnthropicProvider(apiKey = config.apiKey, model = config.model)
        else -> OpenAICompatibleProvider(
            type = config.type,
            apiKey = config.apiKey,
            baseUrl = config.baseUrl ?: OpenAICompatibleProvider.baseUrlFor(config.type),
            model = config.model,
        )
    }
}
