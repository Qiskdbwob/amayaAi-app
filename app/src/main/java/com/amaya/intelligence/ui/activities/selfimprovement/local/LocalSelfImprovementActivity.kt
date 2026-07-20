package com.amaya.intelligence.ui.activities.selfimprovement.local

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.amaya.intelligence.ui.components.shared.SettingsBackButton
import com.amaya.intelligence.ui.screens.selfimprovement.SelfImprovementScreen
import com.amaya.intelligence.ui.screens.selfimprovement.SelfImprovementViewModel
import com.amaya.intelligence.ui.theme.AmayaTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class LocalSelfImprovementActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AmayaTheme {
                val viewModel: SelfImprovementViewModel = hiltViewModel()
                val state by viewModel.uiState.collectAsState()
                val snackbarHostState = remember { SnackbarHostState() }

                LaunchedEffect(state.message) {
                    val message = state.message
                    if (!message.isNullOrBlank()) {
                        snackbarHostState.showSnackbar(message)
                        viewModel.clearMessage()
                    }
                }

                val initialSection = intent.getStringExtra(EXTRA_SECTION) ?: "overview"

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = { Text("Amaya Brain") },
                            navigationIcon = { SettingsBackButton(onClick = { finish() }) },
                            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                            modifier = Modifier.statusBarsPadding(),
                            windowInsets = WindowInsets(0.dp)
                        )
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        SelfImprovementScreen(
                            pendingProposals = state.pendingProposals,
                            lastMaintenanceRun = state.lastMaintenanceRun,
                            promptPreview = state.promptPreview,
                            lastApplyResults = state.lastApplyResults,
                            onApprove = viewModel::approve,
                            onReject = viewModel::reject,
                            onApply = viewModel::applyApproved,
                            onApplyApproved = viewModel::applyAllApproved,
                            initialSection = initialSection
                        )
                    }
                }
            }
        }
    }

    companion object {
        fun start(activity: Activity, initialSection: String = "overview") {
            activity.startActivity(
                Intent(activity, LocalSelfImprovementActivity::class.java)
                    .putExtra(EXTRA_SECTION, initialSection)
            )
        }

        private const val EXTRA_SECTION = "initial_section"
    }
}
