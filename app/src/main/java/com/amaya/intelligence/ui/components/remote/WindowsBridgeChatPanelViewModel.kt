package com.amaya.intelligence.ui.components.remote

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeClientConfig
import com.amaya.intelligence.impl.bridge.windows.WindowsBridgeConnectionState
import com.amaya.intelligence.impl.bridge.windows.pairing.WindowsBridgeProfileStore
import com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeController
import com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeToolProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-scoped ViewModel for every chat-side Windows Bridge surface:
 *  - the connection banner in Local chat,
 *  - the "Windows connected" pill on the empty welcome screen,
 *  - the session-info sheet opened from the chat top bar (agent control / capture
 *    / disconnect),
 *  - the connection-setup sheet opened from the Remote Session screen.
 *
 * Chat messages themselves stay in [com.amaya.intelligence.ui.viewmodels.ChatViewModel] —
 * this VM only owns bridge controller interactions and the matching UI state.
 */
@HiltViewModel
class WindowsBridgeChatPanelViewModel @Inject constructor(
    private val controller: WindowsBridgeController,
    private val toolProvider: WindowsBridgeToolProvider,
    private val profileStore: WindowsBridgeProfileStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    private val _state = MutableStateFlow(WindowsBridgeChatUiState())
    val state: StateFlow<WindowsBridgeChatUiState> = _state.asStateFlow()

    private val prefs by lazy {
        appContext.getSharedPreferences("amaya_bridge_config", Context.MODE_PRIVATE)
    }

    init {
        loadSavedConfig()
        observeController()
    }

    // ── Form helpers ────────────────────────────────────────────────────────
    fun updateHost(value: String) { _state.update { it.copy(host = value.trim()) } }
    fun updatePort(value: String) { _state.update { it.copy(port = value.trim()) } }
    fun updateToken(value: String) { _state.update { it.copy(token = value) } }

    // ── Connection lifecycle ────────────────────────────────────────────────
    fun connect() {
        val snapshot = _state.value
        val host = snapshot.host.ifBlank { return }
        val port = snapshot.port.toIntOrNull() ?: 17878
        val deviceId = snapshot.deviceId.ifBlank { generateDeviceId() }

        _state.update { it.copy(lastError = null, deviceId = deviceId) }
        saveConfig(host, port.toString(), deviceId)
        profileStore.saveOrUpdate(host = host, port = port, deviceId = deviceId)

        controller.connect(
            WindowsBridgeClientConfig(
                host = host,
                port = port,
                token = snapshot.token.ifBlank { null },
                deviceId = deviceId
            )
        )
    }

    fun disconnect() {
        controller.disconnect()
        _state.update { it.copy(screenCapture = null, pendingApproval = null) }
    }

    // ── Agent Control ───────────────────────────────────────────────────────
    fun toggleAgentControl() {
        controller.setAgentControlEnabled(!_state.value.isAgentControlEnabled)
    }

    fun confirmEnableAgentControl() { controller.setAgentControlEnabled(true) }
    fun disableAgentControl() { controller.setAgentControlEnabled(false) }
    fun emergencyStop() { controller.emergencyStop() }

    // ── Approvals ───────────────────────────────────────────────────────────
    fun approveRequest() { controller.respondPending(true, "Approved by user") }
    fun rejectRequest() { controller.respondPending(false, "Rejected by user") }

    // ── Screen capture preview ──────────────────────────────────────────────
    fun captureScreen() {
        _state.update { it.copy(screenCapture = WindowsBridgeCaptureState(isLoading = true)) }
        viewModelScope.launch {
            val result = toolProvider.executeBridgeTool(
                name = "screen.capture",
                arguments = mapOf(
                    "format" to "jpeg",
                    "quality" to 75,
                    "maxWidth" to 1280
                )
            )
            when (result) {
                is com.amaya.intelligence.tools.ToolResult.Success -> {
                    val output = try { org.json.JSONObject(result.output) } catch (_: Exception) { null }
                    val resultJson = output?.optJSONObject("result")
                    val img = (result.metadata["bridge_image_base64"] as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?: resultJson?.optString("imageBase64")?.takeIf { it.isNotBlank() }
                    val w = resultJson?.optInt("width", 0) ?: 0
                    val h = resultJson?.optInt("height", 0) ?: 0
                    val fmt = (result.metadata["bridge_image_format"] as? String)
                        ?.takeIf { it.isNotBlank() }
                        ?: resultJson?.optString("format", "jpeg")
                        ?: "jpeg"
                    _state.update {
                        it.copy(
                            screenCapture = WindowsBridgeCaptureState(
                                imageBase64 = img,
                                width = w,
                                height = h,
                                format = fmt,
                                isLoading = false,
                                error = if (img == null) "Screen capture did not include image data." else null
                            )
                        )
                    }
                }
                is com.amaya.intelligence.tools.ToolResult.Error -> {
                    _state.update {
                        it.copy(screenCapture = WindowsBridgeCaptureState(isLoading = false, error = result.message))
                    }
                }
                else -> {
                    _state.update {
                        it.copy(screenCapture = WindowsBridgeCaptureState(isLoading = false, error = "Unexpected result"))
                    }
                }
            }
        }
    }

    fun clearCapture() { _state.update { it.copy(screenCapture = null) } }

    fun visibleTools(): List<String> = controller.visibleToolNames().toList()

    // ── Observation ─────────────────────────────────────────────────────────
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
                        connectionState = connState,
                        sessionId = sessionId
                    )
                }
                delay(500)
            }
        }
    }

    // ── Persistence helpers ─────────────────────────────────────────────────
    @SuppressLint("HardwareIds")
    private fun generateDeviceId(): String = try {
        "amaya-" + Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ).take(8)
    } catch (_: Exception) {
        "amaya-" + java.util.UUID.randomUUID().toString().take(8)
    }

    private fun loadSavedConfig() {
        val savedProfile = profileStore.getAll().maxByOrNull { it.lastConnectedAt ?: 0L }
        val host = savedProfile?.host ?: prefs.getString("bridge_host", "") ?: ""
        val port = savedProfile?.port?.toString() ?: prefs.getString("bridge_port", "17878") ?: "17878"
        val deviceId = savedProfile?.deviceId ?: prefs.getString("bridge_device_id", "") ?: ""
        _state.update { it.copy(host = host, port = port, deviceId = deviceId) }
    }

    private fun saveConfig(host: String, port: String, deviceId: String) {
        prefs.edit()
            .putString("bridge_host", host)
            .putString("bridge_port", port)
            .putString("bridge_device_id", deviceId)
            .apply()
    }
}
