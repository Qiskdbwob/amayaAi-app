package com.amaya.intelligence.ui.activities.agent.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.lifecycleScope
import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.repository.AgentMemoryRepository
import com.amaya.intelligence.data.repository.ReferenceDocumentRepository
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.impl.common.mappers.ModelUiMapper
import com.amaya.intelligence.domain.models.AgentCapabilityProfile
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.ui.activities.cronjob.local.LocalCronJobActivity
import com.amaya.intelligence.ui.activities.shared.LocalReferenceListActivity
import com.amaya.intelligence.ui.screens.agent.local.LocalAgentConfigScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LocalAgentConfigActivity : AppCompatActivity() {
    @Inject lateinit var agentDao: AgentDao
    @Inject lateinit var conversationDao: ConversationDao
    @Inject lateinit var referenceDocumentRepository: ReferenceDocumentRepository
    @Inject lateinit var agentMemoryRepository: AgentMemoryRepository
    @Inject lateinit var settingsManager: AiSettingsManager
    private val agentId by lazy { intent.getLongExtra(EXTRA_AGENT_ID, -1L) }
    private var agent by mutableStateOf<com.amaya.intelligence.data.local.entity.AgentEntity?>(null)
    private var group by mutableStateOf<com.amaya.intelligence.data.local.entity.AgentGroupEntity?>(null)
    private var loading by mutableStateOf(true)
    private val referencePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val current = agent ?: return@launch
            referenceDocumentRepository.import("agent", current.id, uri).onSuccess { path ->
                val updated = current.copy(referencePathsJson = referenceDocumentRepository.appendPath(current.referencePathsJson, path), updatedAt = System.currentTimeMillis())
                agent = updated
                agentDao.update(updated)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                LaunchedEffect(agentId) {
                    agent = agentDao.getById(agentId)
                    group = agent?.let { agentDao.getGroupById(it.groupId) }
                    loading = false
                }
                val currentAgent = agent
                val currentGroup = group
                val settings = settingsManager.getSettings()
                val availableModels = settings.connections.flatMap { connection -> connection.visibleModels.map { ModelUiMapper.mapConnectionModel(connection, it) } }
                if (loading) {
                    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else if (currentAgent == null || currentGroup == null) {
                    LaunchedEffect(Unit) { finish() }
                } else LocalAgentConfigScreen(
                    group = currentGroup,
                    agent = currentAgent,
                    snackbarHostState = remember { SnackbarHostState() },
                    onNavigateBack = { finish() },
                    availableModels = availableModels,
                    globalModelKey = settings.activeSelection?.key,
                    onSave = { name, role, instructions, profile, modelKeys ->
                        val updated = currentAgent.copy(name = name, role = role, instructions = instructions, capabilityProfile = profile.encode(), defaultModelKeysJson = org.json.JSONArray(modelKeys.toList()).toString(), updatedAt = System.currentTimeMillis())
                        agent = updated
                        lifecycleScope.launch { agentDao.update(updated) }
                    },
                    onAddReference = { LocalReferenceListActivity.start(this, "agent", currentAgent.id) },
                    onOpenMemory = { LocalAgentMemoryActivity.start(this, currentAgent.id) },
                    onOpenReminders = { LocalCronJobActivity.start(this, currentAgent.id) },
                    onDelete = {
                        lifecycleScope.launch {
                            conversationDao.deleteAgentConversations(currentAgent.id)
                            referenceDocumentRepository.deleteOwner("agent", currentAgent.id)
                            agentMemoryRepository.deleteOwner(currentAgent.id)
                            agentDao.delete(currentAgent)
                            setResult(RESULT_DELETED)
                            finish()
                        }
                    }
                )
            }
        }
    }

    companion object {
        private const val EXTRA_AGENT_ID = "agent_id"
        const val REQUEST_CODE = 1014
        const val RESULT_DELETED = Activity.RESULT_FIRST_USER + 2

        fun startForResult(activity: Activity, agentId: Long) {
            activity.startActivityForResult(Intent(activity, LocalAgentConfigActivity::class.java).putExtra(EXTRA_AGENT_ID, agentId), REQUEST_CODE)
        }
    }
}
