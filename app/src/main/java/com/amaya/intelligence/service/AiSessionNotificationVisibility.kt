package com.amaya.intelligence.service

/** Process-local projection of the conversation currently visible to the user. */
object AiSessionNotificationVisibility {
    @Volatile private var resumedConversationId: Long? = null

    fun resumed(conversationId: Long?) {
        resumedConversationId = conversationId
    }

    fun paused(conversationId: Long?) {
        if (resumedConversationId == conversationId) resumedConversationId = null
    }

    fun isVisible(conversationId: Long): Boolean = resumedConversationId == conversationId
}
