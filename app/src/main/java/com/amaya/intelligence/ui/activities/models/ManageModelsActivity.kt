package com.amaya.intelligence.ui.activities.models

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.amaya.intelligence.data.remote.api.CodexAuthManager
import com.amaya.intelligence.ui.screens.models.ManageModelsScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ManageModelsActivity : AppCompatActivity() {
    @Inject lateinit var codexAuthManager: CodexAuthManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                ManageModelsScreen(
                    codexAuthManager = codexAuthManager,
                    onNavigateBack = { finish() },
                    onNavigateToProvider = { id -> ProviderDetailActivity.start(this, id) }
                )
            }
        }
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, ManageModelsActivity::class.java))
        }
    }
}
