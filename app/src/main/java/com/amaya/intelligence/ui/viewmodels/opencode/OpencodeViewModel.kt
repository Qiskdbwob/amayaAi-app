package com.amaya.intelligence.ui.viewmodels.opencode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amaya.intelligence.impl.ide.opencode.OpencodeClient
import com.amaya.intelligence.impl.ide.opencode.OpencodeMcpSummary
import com.amaya.intelligence.impl.ide.opencode.OpencodeModelSummary
import com.amaya.intelligence.impl.ide.opencode.OpencodeProviderSummary
import com.amaya.intelligence.impl.ide.opencode.OpencodeRuntimeSnapshot
import com.amaya.intelligence.impl.ide.opencode.OpencodeSessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Aggregates opencode runtime snapshots, provider/model/mcp listings, and session
 * inventory into a single state observable by `OpencodeSessionScreen` and
 * `OpencodeSettingsScreen`.
 *
 * The view model is driven purely by [OpencodeClient.events] — it does not
 * perform any networking of its own.
 */
@HiltViewModel
class OpencodeViewModel @Inject constructor(
    private val opencodeClient: OpencodeClient
) : ViewModel() {

    data class UiState(
        val runtime: OpencodeRuntimeSnapshot = OpencodeRuntimeSnapshot.STOPPED,
        val providers: List<OpencodeProviderSummary> = emptyList(),
        val models: List<OpencodeModelSummary> = emptyList(),
        val defaultProviderId: String? = null,
        val defaultModelId: String? = null,
        val mcp: List<OpencodeMcpSummary> = emptyList(),
        val sessions: List<OpencodeSessionSummary> = emptyList(),
        val configJson: String? = null,
        val configPath: String? = null,
        val errorMessage: String? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        opencodeClient.attach(viewModelScope)
        viewModelScope.launch {
            opencodeClient.runtime.collect { snapshot ->
                _state.update { it.copy(runtime = snapshot) }
            }
        }
        viewModelScope.launch {
            opencodeClient.events.collect { event ->
                when (event) {
                    is OpencodeClient.Event.Runtime -> _state.update { it.copy(runtime = event.info) }
                    is OpencodeClient.Event.Providers -> _state.update { it.copy(providers = event.providers) }
                    is OpencodeClient.Event.Models -> _state.update {
                        it.copy(
                            models = event.models,
                            defaultProviderId = event.defaultProviderId,
                            defaultModelId = event.defaultModelId
                        )
                    }
                    is OpencodeClient.Event.Mcp -> _state.update { it.copy(mcp = event.servers) }
                    is OpencodeClient.Event.Sessions -> _state.update { it.copy(sessions = event.sessions) }
                    is OpencodeClient.Event.Config -> _state.update {
                        it.copy(configJson = event.json, configPath = event.configPath)
                    }
                    is OpencodeClient.Event.Error -> _state.update { it.copy(errorMessage = event.message) }
                    else -> Unit
                }
            }
        }
        refreshAll()
    }

    fun refreshAll() {
        opencodeClient.requestRuntimeStatus()
        opencodeClient.requestProviders()
        opencodeClient.requestModels()
        opencodeClient.requestMcp()
        opencodeClient.requestSessions()
    }

    fun loadConfig() = opencodeClient.requestConfig()

    fun startRuntime() = opencodeClient.startRuntime()
    fun stopRuntime() = opencodeClient.stopRuntime()
    fun restartRuntime() = opencodeClient.restartRuntime()

    fun clearError() = _state.update { it.copy(errorMessage = null) }

    fun deleteSession(sessionId: String) = opencodeClient.deleteSession(sessionId)
}
