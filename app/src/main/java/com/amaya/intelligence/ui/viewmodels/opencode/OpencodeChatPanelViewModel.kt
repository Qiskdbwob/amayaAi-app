package com.amaya.intelligence.ui.viewmodels.opencode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amaya.intelligence.domain.bridge.AgentModes
import com.amaya.intelligence.impl.ide.opencode.OpencodeClient
import com.amaya.intelligence.impl.ide.opencode.OpencodePermissionRequest
import com.amaya.intelligence.impl.ide.opencode.services.OpencodeIntelligenceService
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Chat-panel state for the Opencode chat screen. Owns the pending permission
 * indicator + active mode (plan / build) so the Compose layer stays declarative.
 */
@HiltViewModel
class OpencodeChatPanelViewModel @Inject constructor(
    private val opencodeClient: OpencodeClient,
    private val opencodeService: OpencodeIntelligenceService
) : ViewModel() {

    data class UiState(
        val mode: String = AgentModes.BUILD,
        val pendingPermission: OpencodePermissionRequest? = null,
        val isRuntimeReady: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                opencodeService.mode,
                opencodeClient.pendingPermission,
                opencodeClient.runtime
            ) { mode, pending, runtime ->
                UiState(
                    mode = mode,
                    pendingPermission = pending,
                    isRuntimeReady = runtime.isReady
                )
            }.collect { snapshot ->
                _state.update { snapshot }
            }
        }
    }

    fun setMode(newMode: String) {
        opencodeService.setMode(newMode)
    }

    fun approvePermission() {
        opencodeClient.respondCurrentPermission("once")
    }

    fun approvePermissionAlways() {
        opencodeClient.respondCurrentPermission("always")
    }

    fun rejectPermission() {
        opencodeClient.respondCurrentPermission("reject")
    }
}
