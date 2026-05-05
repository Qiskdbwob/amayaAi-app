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
import com.amaya.intelligence.ui.screens.amaya.AmayaViewModel
import com.amaya.intelligence.ui.screens.amaya.PrivacySafetyScreen
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LocalPrivacySafetyActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                val viewModel: AmayaViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }
                LaunchedEffect(state.message) { state.message?.let { snackbarHostState.showSnackbar(it); viewModel.clearMessage() } }
                PrivacySafetyScreen(
                    state = state,
                    snackbarHostState = snackbarHostState,
                    onNavigateBack = { finish() }
                )
            }
        }
    }

    companion object {
        fun start(activity: Activity) { activity.startActivity(Intent(activity, LocalPrivacySafetyActivity::class.java)) }
    }
}
