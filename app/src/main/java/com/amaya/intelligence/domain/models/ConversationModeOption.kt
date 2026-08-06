package com.amaya.intelligence.domain.models

/**
 * Generic conversation-mode option rendered by [ChatScreen].
 *
 * Different providers have different mental models for "how should the agent
 * behave" — antigravity uses Planning/Fast, opencode uses Plan/Build, future
 * IDEs/CLIs may introduce their own (Architect, Review, ...). Each provider
 * supplies a list of [ConversationModeOption] so the UI stays generic.
 *
 * [id] is the wire value sent back to the provider when the user picks the
 * option; the same id is used in [ChatUiState.conversationModeId] to remember
 * the current selection.
 */
data class ConversationModeOption(
    val id: String,
    val displayName: String,
    val description: String,
    val legacy: ConversationMode? = null
) {
    companion object {
        /**
         * Legacy presets for providers that still deal in the old [ConversationMode]
         * enum (antigravity). They keep their wire values so the transport side
         * does not need to change.
         */
        val PLANNING = ConversationModeOption(
            id = ConversationMode.PLANNING.wireValue,
            displayName = "Planning",
            description = "Agent can plan before executing tasks. Use for deep research, complex tasks, or collaborative work.",
            legacy = ConversationMode.PLANNING
        )
        val FAST = ConversationModeOption(
            id = ConversationMode.FAST.wireValue,
            displayName = "Fast",
            description = "Agent will execute tasks directly. Use for simple tasks that can be completed faster.",
            legacy = ConversationMode.FAST
        )

        val ANTIGRAVITY: List<ConversationModeOption> = listOf(PLANNING, FAST)
    }
}
