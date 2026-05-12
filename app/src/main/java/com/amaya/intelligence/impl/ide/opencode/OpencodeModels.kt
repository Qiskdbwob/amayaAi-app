package com.amaya.intelligence.impl.ide.opencode

/**
 * Plain data classes used by the Opencode runtime on Android. These mirror the
 * neutral shapes declared in `domain/bridge/AgentRuntime.kt` but are kept local
 * to the Opencode module so consumers don't have to decode nested maps repeatedly.
 */

data class OpencodeRuntimeSnapshot(
    val status: String,
    val version: String? = null,
    val baseUrl: String? = null,
    val binaryPath: String? = null,
    val configPath: String? = null,
    val lastError: String? = null,
    val updatedAt: Long = System.currentTimeMillis()
) {
    val isReady: Boolean get() = status == "ready"
    val isStarting: Boolean get() = status == "starting"
    val isError: Boolean get() = status == "error"

    companion object {
        val STOPPED = OpencodeRuntimeSnapshot(status = "stopped")
    }
}

data class OpencodeProviderSummary(
    val providerId: String,
    val displayName: String,
    val authenticated: Boolean,
    val defaultModelId: String?,
    val modelCount: Int
)

data class OpencodeModelSummary(
    val modelId: String,
    val providerId: String,
    val displayName: String,
    val contextWindowTokens: Int? = null,
    val maxOutputTokens: Int? = null,
    val supportsImages: Boolean = false
)

data class OpencodeMcpSummary(
    val name: String,
    val type: String,
    val enabled: Boolean,
    val connected: Boolean,
    val toolCount: Int
)

data class OpencodeSessionSummary(
    val sessionId: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val agent: String? = null,
    val modelId: String? = null,
    val providerId: String? = null
)

data class OpencodePermissionRequest(
    val sessionId: String,
    val permissionId: String,
    val title: String,
    val kind: String?,
    val description: String?
)

data class OpencodeMessagePartUpdate(
    val sessionId: String,
    val messageId: String?,
    val partId: String?,
    val partType: PartType,
    val text: String = "",
    val toolName: String? = null,
    val toolState: String? = null
) {
    enum class PartType { TEXT, THOUGHT, TOOL, OTHER }
}
