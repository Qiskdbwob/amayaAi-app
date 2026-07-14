package com.amaya.intelligence.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue

import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.amaya.intelligence.domain.ai.IntelligenceSessionManager
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.ui.screens.chat.shared.ChatScreen
import com.amaya.intelligence.ui.viewmodels.ChatViewModel
import com.amaya.intelligence.ui.viewmodels.AppViewModel
import com.amaya.intelligence.ui.activities.settings.local.LocalSettingsActivity
import com.amaya.intelligence.ui.activities.project.local.LocalProjectActivity
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main entry point for the Amaya app.
 *
 * Hosts the root NavHost and provides global state (AppViewModel) that
 * survives conversation switches — e.g. active reminder count badge.
 */
@AndroidEntryPoint
class MainActivity : androidx.appcompat.app.AppCompatActivity() {

    @Inject
    lateinit var aiSettingsManager: AiSettingsManager

    /** Scoped to Activity process — survives all conversation switches. */
    private val appViewModel: AppViewModel by viewModels()

    private var chatViewModel: ChatViewModel? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Observe theme OUTSIDE Compose -- safe on UI thread via lifecycleScope.
        lifecycleScope.launch {
            aiSettingsManager.settingsFlow
                .map { it.theme }
                .distinctUntilChanged()
                .collect { theme ->
                    val mode = when (theme) {
                        "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        "dark"  -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                        else    -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                    }
                    if (androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode() != mode) {
                        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(mode)
                    }
                }
        }

        setContent {
            LaunchedEffect(Unit) {
                val settings = aiSettingsManager.settingsFlow.first()
                val fixedJson = aiSettingsManager.loadMcpConfigFromFixedPath()
                if (!fixedJson.isNullOrBlank() && fixedJson != settings.mcpConfigJson) {
                    aiSettingsManager.setMcpConfigJson(fixedJson)
                }
            }

            AmayaTheme {
                AppContent(
                    appViewModel = appViewModel,
                    initialIntent = intent,
                    onChatViewModelReady = { vm -> chatViewModel = vm },
                    onNavigateToSettings = { workspacePath ->
                        LocalSettingsActivity.start(this@MainActivity, workspacePath)
                    },
                    onNavigateToWorkspace = {
                        @Suppress("DEPRECATION")
                        LocalProjectActivity.startForResult(this@MainActivity)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val id = intent.getLongExtra("open_conversation_id", -1L)
        if (id > 0) chatViewModel?.loadConversation(id)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LocalProjectActivity.REQUEST_CODE && resultCode == RESULT_OK) {
            data?.getStringExtra(LocalProjectActivity.RESULT_KEY)?.let { path ->
                chatViewModel?.setWorkspace(path)
            }
        } else if (requestCode == LocalSettingsActivity.REQUEST_CODE && resultCode == RESULT_OK) {
            data?.getStringExtra(LocalProjectActivity.RESULT_KEY)?.let { path ->
                chatViewModel?.setWorkspace(path)
            }
        }
    }

}

// ── Root composable ──────────────────────────────────────────────────────────

@Composable
private fun AppContent(
    appViewModel: AppViewModel,
    initialIntent: Intent?,
    onChatViewModelReady: (ChatViewModel) -> Unit,
    onNavigateToSettings: (workspacePath: String?) -> Unit,
    onNavigateToWorkspace: () -> Unit
) {
    val navController = rememberNavController()
    val viewModel: ChatViewModel = hiltViewModel()
    val activeReminderCount by appViewModel.activeReminderCount.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.switchMode(IntelligenceSessionManager.SessionMode.LOCAL)
    }

    LaunchedEffect(viewModel) { onChatViewModelReady(viewModel) }

    LaunchedEffect(initialIntent) {
        val id = initialIntent?.getLongExtra("open_conversation_id", -1L) ?: -1L
        if (id > 0) viewModel.loadConversation(id)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavHost(
            navController = navController,
            startDestination = "chat"
        ) {
            composable("chat") {
                val context = androidx.compose.ui.platform.LocalContext.current
                val config = com.amaya.intelligence.ui.screens.chat.shared.localChatScreenConfig(
                    onClearConversation = { viewModel.clearConversation() },
                    onNavigateToSettings = { onNavigateToSettings(viewModel.uiState.value.workspacePath) },
                    onNavigateToRemoteSession = {
                        context.startActivity(android.content.Intent(context, com.amaya.intelligence.ui.activities.remote.RemoteSessionActivity::class.java))
                    }
                )
                ChatScreen(
                    viewModel = viewModel,
                    activeReminderCount = activeReminderCount,
                    config = config,
                    onNavigateToSettings = { onNavigateToSettings(viewModel.uiState.value.workspacePath) },
                    onNavigateToWorkspace = onNavigateToWorkspace,
                    onNavigateToRemoteSession = {
                        context.startActivity(android.content.Intent(context, com.amaya.intelligence.ui.activities.remote.RemoteSessionActivity::class.java))
                    }
                )
            }
        }

    }
}
