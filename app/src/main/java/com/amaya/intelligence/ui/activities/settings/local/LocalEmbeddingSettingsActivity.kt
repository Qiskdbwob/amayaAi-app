package com.amaya.intelligence.ui.activities.settings.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.amaya.intelligence.data.remote.api.AiSettingsManager
import com.amaya.intelligence.ui.screens.settings.local.EmbeddingSettingsScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class LocalEmbeddingSettingsActivity : AppCompatActivity() {
    @Inject lateinit var settingsManager: AiSettingsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                EmbeddingSettingsScreen(
                    settingsManager = settingsManager,
                    onNavigateBack = { finish() }
                )
            }
        }
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, LocalEmbeddingSettingsActivity::class.java))
        }
    }
}
