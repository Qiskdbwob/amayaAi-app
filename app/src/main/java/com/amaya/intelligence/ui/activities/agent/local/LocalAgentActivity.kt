package com.amaya.intelligence.ui.activities.agent.local

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.data.remote.api.CodexAuthManager
import com.amaya.intelligence.data.repository.ModelCatalogRepository
import com.amaya.intelligence.ui.screens.agent.local.LocalAgentScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LocalAgentActivity : AppCompatActivity() {

    @Inject
    lateinit var aiSettingsManager: AiSettingsManager

    @Inject
    lateinit var codexAuthManager: CodexAuthManager

    @Inject
    lateinit var modelCatalogRepository: ModelCatalogRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                LocalAgentScreen(
                    onNavigateBack = { finish() },
                    aiSettingsManager = aiSettingsManager,
                    codexAuthManager = codexAuthManager,
                    modelCatalogRepository = modelCatalogRepository
                )
            }
        }
    }

    companion object {
        fun start(activity: android.app.Activity) {
            activity.startActivity(android.content.Intent(activity, LocalAgentActivity::class.java))
        }
    }
}
