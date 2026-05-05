package com.amaya.intelligence.ui.activities.amaya.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.ui.activities.persona.local.LocalPersonaActivity
import com.amaya.intelligence.ui.screens.amaya.AmayaHomeScreen
import com.amaya.intelligence.ui.screens.amaya.AmayaViewModel
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LocalAmayaActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                val viewModel: AmayaViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(state.message) {
                    state.message?.takeIf { it.isNotBlank() }?.let {
                        snackbarHostState.showSnackbar(it)
                        viewModel.clearMessage()
                    }
                }
                AmayaHomeScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onNavigateBack = { finish() },
                    onPersona = { LocalPersonaActivity.start(this) },
                    onMemory = { LocalMemoryActivity.start(this) },
                    onReview = { LocalReviewActivity.start(this) },
                    onSkills = { LocalSkillsActivity.start(this) },
                    onContext = { LocalContextRecallActivity.start(this) },
                    onPrivacy = { LocalPrivacySafetyActivity.start(this) }
                )
            }
        }
    }

    companion object {
        fun start(activity: Activity) {
            activity.startActivity(Intent(activity, LocalAmayaActivity::class.java))
        }
    }
}
