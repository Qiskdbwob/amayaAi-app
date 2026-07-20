package com.amaya.intelligence.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import com.amaya.intelligence.domain.ai.IntelligenceService
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Turn-bound notification actions; stale approval IDs fail closed in the service registry. */
@AndroidEntryPoint
class AiSessionNotificationActionReceiver : BroadcastReceiver() {
    @Inject lateinit var intelligenceService: IntelligenceService

    override fun onReceive(context: Context, intent: Intent) {
        val conversationId = intent.getLongExtra(EXTRA_CONVERSATION_ID, -1L)
        when (intent.action) {
            ACTION_REPLY -> handleReply(context, intent, conversationId)
            ACTION_APPROVE, ACTION_DECLINE -> handleApproval(context, intent, conversationId)
            ACTION_DISMISS -> if (conversationId > 0) {
                AiSessionNotificationStore.remove(context, conversationId)
                AiSessionNotificationStore.markPromotionDismissed(context, conversationId)
                AiSessionNotificationService.refreshCompletedSummary(context)
            }
            ACTION_CLEAR_COMPLETED -> {
                val entries = AiSessionNotificationStore.read(context)
                AiSessionNotificationStore.clear(context)
                NotificationManagerCompat.from(context).apply {
                    entries.forEach { cancel(AiSessionNotificationService.messageId(it.threadKey)) }
                }
            }
        }
    }

    private fun handleReply(context: Context, intent: Intent, conversationId: Long) {
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
        if (conversationId <= 0 || reply.isBlank()) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intelligenceService.sendMessageToConversation(conversationId, reply)) {
                    AiSessionNotificationStore.addReply(context, conversationId, reply)?.let { entry ->
                        AiSessionNotificationService.refreshMessage(context, entry)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun handleApproval(context: Context, intent: Intent, conversationId: Long) {
        val approvalId = intent.getStringExtra(EXTRA_APPROVAL_ID)?.takeIf(String::isNotBlank) ?: return
        val confirmed = intent.action == ACTION_APPROVE
        intelligenceService.respondToToolInteraction(approvalId, confirmed)
        NotificationManagerCompat.from(context).cancel(AiSessionNotificationService.approvalId(approvalId))
    }

    companion object {
        const val ACTION_APPROVE = "com.amaya.intelligence.action.APPROVE_AI_TOOL"
        const val ACTION_DECLINE = "com.amaya.intelligence.action.DECLINE_AI_TOOL"
        const val ACTION_REPLY = "com.amaya.intelligence.action.REPLY_AI_SESSION"
        const val ACTION_DISMISS = "com.amaya.intelligence.action.DISMISS_AI_SESSION"
        const val ACTION_CLEAR_COMPLETED = "com.amaya.intelligence.action.CLEAR_AI_SESSIONS"
        const val KEY_REPLY = "reply_text"
        const val EXTRA_APPROVAL_ID = "approval_id"
        const val EXTRA_CONVERSATION_ID = "conversation_id"
    }
}
