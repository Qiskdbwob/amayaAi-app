package com.amaya.intelligence.ui.activities.chat.local

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.amaya.intelligence.domain.ai.IntelligenceService
import com.amaya.intelligence.service.AiSessionNotificationVisibility
import com.amaya.intelligence.ui.screens.chat.local.LocalChatScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class LocalChatActivity : AppCompatActivity() {
    @Inject lateinit var intelligenceService: IntelligenceService
    private var visibleConversationId: Long? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            intelligenceService.uiState.collectLatest { state ->
                val previous = visibleConversationId
                visibleConversationId = state.conversationId?.toLongOrNull()
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    AiSessionNotificationVisibility.paused(previous)
                    AiSessionNotificationVisibility.resumed(visibleConversationId)
                }
            }
        }
        setContent {
            AmayaTheme {
                LocalChatScreen(
                    activeReminderCount = intent.getIntExtra("active_reminder_count", -1),
                    onNavigateToSettings = {
                        com.amaya.intelligence.ui.activities.settings.local.LocalSettingsActivity.start(this)
                    },
                    onNavigateToWorkspace = {
                        com.amaya.intelligence.ui.activities.settings.local.LocalSettingsActivity.start(
                            this, com.amaya.intelligence.domain.models.AssistantMode.PROJECT
                        )
                    },
                    onNavigateToAgents = {
                        com.amaya.intelligence.ui.activities.settings.local.LocalSettingsActivity.start(
                            this, com.amaya.intelligence.domain.models.AssistantMode.AGENT
                        )
                    },
                    onNavigateToRemoteSession = {
                        startActivity(android.content.Intent(this, com.amaya.intelligence.ui.activities.antigravity.RemoteSessionActivity::class.java))
                    },
                    onExit = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        AiSessionNotificationVisibility.resumed(visibleConversationId)
    }

    override fun onPause() {
        AiSessionNotificationVisibility.paused(visibleConversationId)
        super.onPause()
    }

    companion object {
        fun start(context: android.content.Context, activeReminderCount: Int = -1) {
            val intent = android.content.Intent(context, LocalChatActivity::class.java).apply {
                putExtra("active_reminder_count", activeReminderCount)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
