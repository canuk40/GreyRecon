package com.greyrecon.app.ui.agent

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.greyrecon.app.ai.agent.AgentBackend
import com.greyrecon.app.ai.agent.AgentEvent
import com.greyrecon.app.ai.agent.AgentFactory
import com.greyrecon.app.ai.agent.ChatMessage
import com.greyrecon.app.data.SecureKeyStore
import com.greyrecon.app.mcp.ScanDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Drives the AI Assistant chat screen. Owns the transcript and the [AgentBackend] (built lazily
 * from the user's selected provider + key), turning the backend's live [AgentEvent] stream into
 * visible chat bubbles and tool-activity chips. Survives config changes so the conversation isn't
 * lost on rotation.
 */
class AgentChatViewModel(application: Application) : AndroidViewModel(application) {

    private val keyStore = SecureKeyStore(application)
    private val dataSource = ScanDataSource(application)

    private val transcript = mutableListOf<ChatMessage>()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var backend: AgentBackend? = null

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _busy.value) return

        transcript.add(ChatMessage.User(trimmed)); emit()
        _busy.value = true

        viewModelScope.launch {
            val agent = ensureBackend()
            if (agent == null) { _busy.value = false; return@launch }
            agent.send(trimmed) { event -> onEvent(event) }
            _busy.value = false
        }
    }

    private fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.ToolStart -> {
                val args = if (event.argsPreview.isBlank()) "" else "(${event.argsPreview})"
                transcript.add(ChatMessage.Activity("🔧 ${event.tool}$args…"))
            }
            is AgentEvent.ToolEnd -> {
                val idx = transcript.indexOfLast { it is ChatMessage.Activity && it.label.startsWith("🔧") && it.label.contains(event.tool) }
                val resolved = ChatMessage.Activity("✅ ${event.tool}" + if (event.resultPreview.isBlank()) "" else " — ${event.resultPreview}")
                if (idx >= 0) transcript[idx] = resolved else transcript.add(resolved)
            }
            is AgentEvent.Answer -> transcript.add(ChatMessage.Assistant(event.text))
            is AgentEvent.Failed -> transcript.add(ChatMessage.Assistant("⚠️ ${event.message}"))
        }
        emit()
    }

    /** Builds the backend on first use; on failure, drops a one-time assistant bubble explaining what to fix. */
    private fun ensureBackend(): AgentBackend? {
        backend?.let { return it }
        return AgentFactory.create(keyStore, dataSource).fold(
            onSuccess = { it.also { b -> backend = b } },
            onFailure = { e ->
                transcript.add(ChatMessage.Assistant("⚠️ ${e.message ?: "AI Assistant isn't configured."}"))
                emit()
                null
            },
        )
    }

    private fun emit() { _messages.value = transcript.toList() }
}
