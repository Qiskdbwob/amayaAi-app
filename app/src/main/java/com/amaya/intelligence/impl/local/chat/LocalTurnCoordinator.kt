package com.amaya.intelligence.impl.local.chat

import com.amaya.intelligence.data.remote.api.ChatImage
import com.amaya.intelligence.domain.models.ChatUiState
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

internal data class PendingLocalMessage(val content: String, val images: List<ChatImage>)

internal data class LocalTurn(
    val turnId: Long,
    val conversationId: Long,
    val prompt: String,
    val isNewConversation: Boolean,
    var state: ChatUiState,
    val notificationTitle: String,
    val notificationSender: String,
    val notificationThreadKey: String,
    var assistantMessageId: String? = null,
    var job: Job? = null,
    var delegationActive: Boolean = false,
    var lastStatus: String = "Streaming",
    var lastDetail: String = "Waiting for response",
    var lastNotificationAt: Long = 0L,
    var delegateTotal: Int = 0,
    var delegateCompleted: Int = 0,
    var activeDelegateName: String? = null,
    var pendingMessage: PendingLocalMessage? = null,
    val pendingCanonicalText: StringBuilder = StringBuilder()
) {
    fun drainCanonicalText(): String? {
        if (pendingCanonicalText.isEmpty()) return null
        return pendingCanonicalText.toString().also { pendingCanonicalText.setLength(0) }
    }
}

internal class LocalTurnCoordinator {
    val nextTurnId = AtomicLong(0L)
    val targetEpoch = AtomicLong(0L)
    val activeTurns = ConcurrentHashMap<Long, LocalTurn>()
    val startingConversations = ConcurrentHashMap<Long, Long>()
    val startingNewTurnId = AtomicLong(0L)
    val stoppingConversations = ConcurrentHashMap.newKeySet<Long>()
    val turnsById = ConcurrentHashMap<Long, LocalTurn>()
}
