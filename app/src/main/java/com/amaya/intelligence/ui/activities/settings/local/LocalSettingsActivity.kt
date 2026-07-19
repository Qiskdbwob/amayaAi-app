package com.amaya.intelligence.ui.activities.settings.local

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.ui.screens.settings.local.LocalSettingsScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import com.amaya.intelligence.ui.activities.models.ManageModelsActivity
import com.amaya.intelligence.ui.activities.mcp.local.LocalMcpActivity
import com.amaya.intelligence.ui.activities.cronjob.local.LocalCronJobActivity
import com.amaya.intelligence.ui.activities.project.local.LocalProjectActivity
import com.amaya.intelligence.ui.activities.amaya.local.LocalContextRecallActivity
import com.amaya.intelligence.ui.activities.amaya.local.LocalMemoryActivity
import com.amaya.intelligence.ui.activities.amaya.local.LocalPrivacySafetyActivity
import com.amaya.intelligence.ui.activities.amaya.local.LocalReviewActivity
import com.amaya.intelligence.ui.activities.amaya.local.LocalSkillsActivity
import com.amaya.intelligence.ui.activities.persona.local.LocalPersonaActivity

@AndroidEntryPoint
class LocalSettingsActivity : AppCompatActivity() {

    @Inject
    lateinit var aiSettingsManager: AiSettingsManager

    private var currentWorkspace: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentWorkspace = intent.getStringExtra("current_workspace")
        enableEdgeToEdge()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == LocalProjectActivity.REQUEST_CODE && resultCode == RESULT_OK) {
            val path = data?.getStringExtra(LocalProjectActivity.RESULT_KEY)
            if (path != null) {
                setResult(RESULT_OK, android.content.Intent().apply {
                    putExtra(LocalProjectActivity.RESULT_KEY, path)
                    putExtra("navigate_to_chat", true)
                })
                finish()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        setContent {
            AmayaTheme {
                LocalSettingsScreen(
                    onNavigateBack = { finish() },
                    currentWorkspace = currentWorkspace,
                    onNavigateToWorkspace = { 
                        LocalProjectActivity.startForResult(this)
                    },
                    aiSettingsManager = aiSettingsManager,
                    onNavigateToModels = {
                        ManageModelsActivity.start(this)
                    },
                    onNavigateToReminders = {
                        LocalCronJobActivity.start(this)
                    },
                    onNavigateToMcp = {
                        LocalMcpActivity.start(this)
                    },
                    onNavigateToPersona = {
                        LocalPersonaActivity.start(this)
                    },
                    onNavigateToMemory = {
                        LocalMemoryActivity.start(this, currentWorkspace)
                    },
                    onNavigateToSkills = {
                        LocalSkillsActivity.start(this)
                    },
                    onNavigateToContextRecall = {
                        LocalContextRecallActivity.start(this)
                    },
                    onNavigateToReview = {
                        LocalReviewActivity.start(this, currentWorkspace)
                    },
                    onNavigateToPrivacy = {
                        LocalPrivacySafetyActivity.start(this)
                    }
                )
            }
        }
    }

    companion object {
        fun start(activity: android.app.Activity, currentWorkspace: String? = null) {
            activity.startActivityForResult(
                android.content.Intent(activity, LocalSettingsActivity::class.java)
                    .putExtra("current_workspace", currentWorkspace),
                REQUEST_CODE
            )
        }

        const val REQUEST_CODE = 1002
    }
}
