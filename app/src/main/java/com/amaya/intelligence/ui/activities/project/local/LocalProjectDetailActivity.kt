package com.amaya.intelligence.ui.activities.project.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.lifecycle.lifecycleScope
import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.local.dao.ProjectDao
import com.amaya.intelligence.data.local.entity.ProjectEntity
import com.amaya.intelligence.data.repository.MemoryRepository
import com.amaya.intelligence.data.repository.ReferenceDocumentRepository
import com.amaya.intelligence.domain.memory.MemoryType
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.ui.activities.amaya.local.LocalMemoryAreaActivity
import com.amaya.intelligence.ui.activities.shared.LocalReferenceListActivity
import com.amaya.intelligence.ui.screens.amaya.MemoryArea
import com.amaya.intelligence.ui.screens.project.local.LocalProjectDetailScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class LocalProjectDetailActivity : AppCompatActivity() {
    @Inject lateinit var projectDao: ProjectDao
    @Inject lateinit var conversationDao: ConversationDao
    @Inject lateinit var memoryRepository: MemoryRepository
    @Inject lateinit var referenceDocumentRepository: ReferenceDocumentRepository

    private val projectId by lazy { intent.getLongExtra(EXTRA_PROJECT_ID, -1L) }
    private var memoryCount by mutableStateOf(0)
    private var project by mutableStateOf<ProjectEntity?>(null)
    private var loading by mutableStateOf(true)

    private val referencePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@registerForActivityResult
        lifecycleScope.launch {
            val current = projectDao.getById(projectId) ?: return@launch
            referenceDocumentRepository.import("project", current.id, uri).onSuccess { path ->
                projectDao.updateProject(current.copy(referencePathsJson = referenceDocumentRepository.appendPath(current.referencePathsJson, path)))
            }.onFailure { android.widget.Toast.makeText(this@LocalProjectDetailActivity, it.message, android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                LaunchedEffect(projectId) {
                    project = projectDao.getById(projectId)
                    loading = false
                    memoryCount = project?.let { memoryRepository.listMemoryRecords(MemoryType.WORKSPACE_FACT, limit = 100, workspacePath = it.rootPath).size } ?: 0
                }
                val currentProject = project
                when {
                    loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    currentProject == null -> LaunchedEffect(Unit) { finish() }
                    else -> LocalProjectDetailScreen(
                        project = currentProject,
                        memoryCount = memoryCount,
                        snackbarHostState = remember { SnackbarHostState() },
                        onNavigateBack = { finish() },
                        onSaveName = { name ->
                            val updated = currentProject.copy(name = name)
                            project = updated
                            lifecycleScope.launch { projectDao.updateProject(updated) }
                        },
                        onSaveInstructions = { instructions ->
                            val updated = currentProject.copy(instructions = instructions)
                            project = updated
                            lifecycleScope.launch { projectDao.updateProject(updated) }
                        },
                        onOpenMemory = { LocalMemoryAreaActivity.start(this, MemoryArea.PROJECT, currentProject.rootPath) },
                        onAddReference = { LocalReferenceListActivity.start(this, "project", currentProject.id) },
                        onDelete = {
                            lifecycleScope.launch {
                                conversationDao.deleteOwnedConversations(AssistantMode.PROJECT.name, currentProject.id.toString())
                                referenceDocumentRepository.deleteOwner("project", currentProject.id)
                                projectDao.deleteProject(currentProject)
                                setResult(RESULT_DELETED)
                                finish()
                            }
                        }
                    )
                }
            }
        }
    }

    companion object {
        private const val EXTRA_PROJECT_ID = "project_id"
        const val REQUEST_CODE = 1012
        const val RESULT_DELETED = Activity.RESULT_FIRST_USER + 2

        fun startForResult(activity: Activity, projectId: Long) {
            activity.startActivityForResult(Intent(activity, LocalProjectDetailActivity::class.java).putExtra(EXTRA_PROJECT_ID, projectId), REQUEST_CODE)
        }
    }
}
