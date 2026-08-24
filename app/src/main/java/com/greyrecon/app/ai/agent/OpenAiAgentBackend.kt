package com.greyrecon.app.ai.agent

import com.greyrecon.app.mcp.ToolDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Agent loop for OpenAI-compatible function-calling providers (DeepSeek, OpenAI, Groq,
 * OpenRouter, Ollama). Holds the conversation as flat OpenAI chat messages, sends the tool
 * schema on every turn, executes any `tool_calls` the model returns, feeds the results back,
 * and loops until the model answers in plain text -- bounded by [AGENT_MAX_TOOL_ROUNDS] so a
 * confused model can't loop forever on the user's dime.
 */
class OpenAiAgentBackend(
    private val apiKey: String,
    private val baseUrl: String,
    private val model: String,
    private val executor: ToolExecutor,
    private val client: OkHttpClient = defaultClient,
) : AgentBackend {

    private val messages = mutableListOf(OAMessage(role = "system", content = AGENT_SYSTEM_PROMPT))
    private val tools = executor.definitions().map { it.toOpenAiTool() }

    override suspend fun send(userText: String, onEvent: (AgentEvent) -> Unit) = withContext(Dispatchers.IO) {
        messages.add(OAMessage(role = "user", content = userText))

        repeat(AGENT_MAX_TOOL_ROUNDS) {
            val message = try {
                callModel()
            } catch (e: Exception) {
                onEvent(AgentEvent.Failed(e.message ?: "Request failed"))
                return@withContext
            }

            val calls = message.toolCalls
            if (calls.isNullOrEmpty()) {
                val text = message.content?.trim().orEmpty()
                messages.add(OAMessage(role = "assistant", content = text))
                onEvent(AgentEvent.Answer(text.ifEmpty { "(no answer)" }))
                return@withContext
            }

            // Record the assistant's tool-call turn exactly as returned, then run each tool and
            // feed one tool result back per call id (the API requires all of them before continuing).
            messages.add(OAMessage(role = "assistant", content = message.content, toolCalls = calls))
            for (call in calls) {
                val args = executor.parseArgs(call.function.arguments)
                onEvent(AgentEvent.ToolStart(call.function.name, argsPreview(call.function.arguments)))
                val result = executor.execute(call.function.name, args)
                onEvent(AgentEvent.ToolEnd(call.function.name, result.lineOne()))
                messages.add(OAMessage(role = "tool", toolCallId = call.id, content = result))
            }
        }

        onEvent(AgentEvent.Failed("Stopped after $AGENT_MAX_TOOL_ROUNDS tool rounds without a final answer."))
    }

    private fun callModel(): OAMessage {
        val body = json.encodeToString(
            ChatRequest(model = model, messages = messages.toList(), tools = tools)
        )
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/chat/completions")
            .apply { if (apiKey.isNotBlank()) addHeader("Authorization", "Bearer $apiKey") }
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string()
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code}: ${raw?.take(300) ?: "no body"}")
            }
            if (raw.isNullOrBlank()) throw IOException("Empty response")
            val parsed = json.decodeFromString<ChatResponse>(raw)
            return parsed.choices.firstOrNull()?.message
                ?: throw IOException("No message in response")
        }
    }

    private fun argsPreview(rawArgs: String?): String {
        val obj = executor.parseArgs(rawArgs) ?: return ""
        return obj.entries.joinToString(", ") { (k, v) -> "$k=${v.toString().trim('"')}" }
    }

    private fun String.lineOne(): String = trim().lineSequence().firstOrNull()?.take(80).orEmpty()

    private fun ToolDef.toOpenAiTool(): OATool {
        val props = buildJsonObject {
            parameters.forEach { (param, desc) ->
                put(param, buildJsonObject {
                    put("type", "string")
                    put("description", desc)
                })
            }
        }
        val schema = buildJsonObject {
            put("type", "object")
            put("properties", props)
            put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        }
        return OATool(function = OAFunctionDef(name = name, description = description, parameters = schema))
    }

    @Serializable
    private data class ChatRequest(
        val model: String,
        val messages: List<OAMessage>,
        val tools: List<OATool>? = null,
        val temperature: Double = 0.3,
        @SerialName("max_tokens") val maxTokens: Int = 1024,
    )

    @Serializable
    private data class OAMessage(
        val role: String,
        val content: String? = null,
        @SerialName("tool_calls") val toolCalls: List<OAToolCall>? = null,
        @SerialName("tool_call_id") val toolCallId: String? = null,
    )

    @Serializable
    private data class OAToolCall(
        val id: String,
        val type: String = "function",
        val function: OAFunctionCall,
    )

    @Serializable
    private data class OAFunctionCall(val name: String, val arguments: String)

    @Serializable
    private data class OATool(val type: String = "function", val function: OAFunctionDef)

    @Serializable
    private data class OAFunctionDef(val name: String, val description: String, val parameters: JsonObject)

    @Serializable
    private data class ChatResponse(val choices: List<OAChoice>)

    @Serializable
    private data class OAChoice(val message: OAMessage, @SerialName("finish_reason") val finishReason: String? = null)

    private companion object {
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
