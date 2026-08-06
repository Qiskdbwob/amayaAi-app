package com.amaya.intelligence.impl.ide.antigravity.services.event

import com.amaya.intelligence.domain.models.*

import com.amaya.intelligence.impl.common.mappers.ModelUiMapper
import com.amaya.intelligence.impl.ide.antigravity.client.*
import com.amaya.intelligence.impl.ide.antigravity.event.*
import com.amaya.intelligence.impl.ide.antigravity.event.RemoteWorkspace as ClientRemoteWorkspace
import com.amaya.intelligence.impl.ide.antigravity.services.streaming.StreamingStateManager
import com.amaya.intelligence.data.local.entity.ConversationEntity
import java.text.NumberFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles workspace and conversation management events from Antigravity.
 */
class WorkspaceEventHandler(
    private val onUiStateUpdate: ((ChatUiState) -> ChatUiState) -> Unit,
    private val onConversationsUpdate: (List<ConversationEntity>) -> Unit,
    private val onProjectFilesUpdate: (List<ProjectFileEntry>, String) -> Unit,
    private val onWorkspacesUpdate: (List<RemoteWorkspace>) -> Unit,
    private val client: RemoteSessionClient
) {
    private val conversationIdMap = ConcurrentHashMap<Long, String>()
    private var lastConversationsRefreshAt = 0L
    private var _conversationsSnapshot = emptyList<ConversationEntity>()

    fun handleConversationsList(event: RemoteEvent.ConversationsList) {
        val entities = event.conversations.map { meta ->
            val pseudoId = meta.id.hashCode().toLong()
            conversationIdMap[pseudoId] = meta.id
            ConversationEntity(
                id = pseudoId,
                title = meta.title,
                messagesJson = "[]",
                createdAt = meta.lastModified,
                updatedAt = meta.lastModified,
                workspacePath = meta.workspacePath
            )
        }
        _conversationsSnapshot = entities
        onConversationsUpdate(entities)
    }

    fun handleModelSelected(event: RemoteEvent.ModelSelected) {
        onUiStateUpdate { state -> state.copy(
            selectedModel = event.modelId,
            activeModelKey = event.modelId
        )}
    }

    fun handleActiveConversation(event: RemoteEvent.ActiveConversation, currentConversationId: String?, stateManager: StreamingStateManager) {
        com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerNote("ACTIVE_CONVERSATION", "event=${event.conversationId} current=${currentConversationId ?: "-"}")

        if (currentConversationId != event.conversationId) {
            com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerNote("ACTIVE_CONVERSATION", "switch clear stateManager current=${currentConversationId ?: "-"} next=${event.conversationId}")
            stateManager.clearAll()
            onUiStateUpdate { state -> state.copy(
                conversationId = event.conversationId,
                isLoading = true,
                isStreaming = state.isStreaming,
                error = null,
                serverIp = event.serverIp ?: state.serverIp
            ) }
            // Do not immediately echo load_conversation back to the extension here.
            // active_conversation is commonly sent right before state_sync during
            // reconnect/get_state. Sending a nested load can race and replace the
            // in-flight streaming turn with an older trajectory snapshot.
            refreshConversationsList("active_conversation", force = true)
        }
    }

    fun handleTitleGenerated(event: RemoteEvent.TitleGenerated) {
        val targetId = event.conversationId?.hashCode()?.toLong() ?: return
        val patched = _conversationsSnapshot.map { entity ->
            if (entity.id == targetId) entity.copy(title = event.title.take(60)) else entity
        }
        _conversationsSnapshot = patched
        onConversationsUpdate(patched)
        refreshConversationsList("title_generated")
    }

    fun handleCurrentWorkspace(event: RemoteEvent.CurrentWorkspace) {
        onUiStateUpdate { state -> state.copy(workspacePath = event.path) }
        if (event.path.isNotBlank()) {
            client.getProjectFiles(event.path)
        }
    }

    fun handleNewConversation(event: RemoteEvent.NewConversation, stateManager: StreamingStateManager) {
        if (!event.conversationId.isNullOrBlank()) {
            onUiStateUpdate { state -> state.copy(conversationId = event.conversationId) }
        }
        com.amaya.intelligence.impl.ide.antigravity.services.AntigravityRemoteDebugLog.handlerNote("NEW_CONVERSATION", "cid=${event.conversationId ?: "-"} clear messages")
        stateManager.clearAll()
        onUiStateUpdate { state -> state.copy(
            messages = emptyList(),
            isLoading = false,
            isStreaming = false,
            error = null,
            serverIp = event.serverIp ?: state.serverIp
        ) }
    }

    fun handleProjectFiles(event: RemoteEvent.ProjectFiles) {
        val files = event.files.map { it.toProjectFileEntry() }
        onProjectFilesUpdate(files, event.path)
    }

    fun handleWorkspacesList(event: RemoteEvent.WorkspacesList) {
        val workspaces = event.workspaces.map { (it as ClientRemoteWorkspace).toDomainWorkspace() }
        onWorkspacesUpdate(workspaces)
        val currentPath = event.workspaces.firstOrNull { it.isCurrent }?.path
        if (!currentPath.isNullOrBlank()) {
            onUiStateUpdate { state -> state.copy(workspacePath = currentPath) }
            client.getProjectFiles(currentPath)
        }
    }

    private fun refreshConversationsList(reason: String, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastConversationsRefreshAt < 1200L) return
        lastConversationsRefreshAt = now
        client.getConversations()
    }

    fun handleModelsList(event: RemoteEvent.ModelsList) {

        val selectorItems = event.models.map { m ->
            ModelUiMapper.mapRemoteModel(
                id = m.id,
                name = m.label,
                modelId = m.id,
                tagTitle = m.tagTitle,
                quotaLabel = m.quotaLabel ?: formatQuotaLabel(m.quota),
                resetTime = m.resetTime,
                supportsImages = m.supportsImages
            )
        }
        onUiStateUpdate { state -> state.copy(
            modelOptions = selectorItems,
            activeModelKey = event.selectedModelId,
            selectedModel = event.selectedModelId
        )}
    }

    fun resolveConversationId(id: String): String {
        val asLong = id.toLongOrNull()
        if (asLong != null) {
            return conversationIdMap[asLong] ?: id
        }
        return id
    }

    // Extension functions for remote model mapping
    private fun RemoteFileEntry.toProjectFileEntry(): ProjectFileEntry {
        return ProjectFileEntry(
            name = name,
            path = path,
            type = type,
            size = size
        )
    }

    private fun formatQuotaLabel(remainingFraction: Double): String? {
        if (remainingFraction <= 0.0) return "0%"

        return NumberFormat.getPercentInstance().apply {
            maximumFractionDigits = 0
        }.format(remainingFraction)
    }

    private fun ClientRemoteWorkspace.toDomainWorkspace(): RemoteWorkspace {
        return RemoteWorkspace(
            name = name,
            path = path,
            isCurrent = isCurrent
        )
    }
}
