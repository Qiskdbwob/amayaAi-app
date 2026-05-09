package com.amaya.intelligence.ui.components.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState
import com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Small ViewModel that exposes Windows Bridge state for the ChatScreen banner.
 * Does NOT send chat messages — that remains ChatViewModel's job.
 */
@HiltViewModel
class WindowsBridgeChatPanelViewModel @Inject constructor(
    private val controller: WindowsBridgeController
) : ViewModel() {

    private val _state = MutableStateFlow(WindowsBridgeChatUiState())
    val state: StateFlow<WindowsBridgeChatUiState> = _state.asStateFlow()

    init {
        observeController()
    }

    fun toggleAgentControl() {
        controller.setAgentControlEnabled(!_state.value.isAgentControlEnabled)
    }

    fun confirmEnableAgentControl() {
        controller.setAgentControlEnabled(true)
    }

    fun disableAgentControl() {
        controller.setAgentControlEnabled(false)
    }

    fun emergencyStop() {
        controller.emergencyStop()
    }

    fun approveRequest() {
        controller.respondPending(true, "Approved by user")
    }

    fun rejectRequest() {
        controller.respondPending(false, "Rejected by user")
    }

    private fun observeController() {
        viewModelScope.launch {
            controller.agentControlEnabled.collect { enabled ->
                _state.update { it.copy(isAgentControlEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            controller.pendingApproval.collect { approval ->
                _state.update { it.copy(pendingApproval = approval) }
            }
        }
        viewModelScope.launch {
            while (true) {
                val connState = controller.currentConnectionState()
                val sessionId = controller.currentSessionId()
                _state.update {
                    it.copy(
                        isConnected = connState == WindowsBridgeConnectionState.CONNECTED,
                        isPaused = connState == WindowsBridgeConnectionState.PAUSED,
                        sessionId = sessionId
                    )
                }
                delay(500)
            }
        }
    }
}
