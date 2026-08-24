package com.greyrecon.app.ai.agent

/**
 * UI-facing stream of what the agent is doing, emitted live by an [AgentBackend]
 * as it runs its tool-calling loop. The chat ViewModel turns these into visible
 * message bubbles / activity chips so the user sees the agent scan, check, and
 * reason in real time rather than staring at a spinner.
 */
sealed interface AgentEvent {
    /** The agent decided to run a tool. [argsPreview] is a short human-readable arg summary (may be blank). */
    data class ToolStart(val tool: String, val argsPreview: String) : AgentEvent

    /** A tool finished. [resultPreview] is a truncated first line of its output, for the activity chip. */
    data class ToolEnd(val tool: String, val resultPreview: String) : AgentEvent

    /** The agent's final natural-language answer for this turn. Terminal event on success. */
    data class Answer(val text: String) : AgentEvent

    /** Something went wrong (network, auth, bad response). Terminal event on failure. */
    data class Failed(val message: String) : AgentEvent
}

/** One rendered line in the chat transcript. Activity is the agent's tool use, shown inline between bubbles. */
sealed interface ChatMessage {
    data class User(val text: String) : ChatMessage
    data class Assistant(val text: String) : ChatMessage
    data class Activity(val label: String) : ChatMessage
}
