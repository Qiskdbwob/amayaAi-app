package com.amaya.intelligence.impl.common.mappers

import com.amaya.intelligence.data.remote.api.AgentConfig
import com.amaya.intelligence.data.remote.api.AmayaProviderRegistry
import com.amaya.intelligence.data.remote.api.KnownModelCatalog
import com.amaya.intelligence.domain.models.AgentSelectorItem

object AgentUiMapper {
    fun mapToSelectorItem(
        agent: AgentConfig,
        isRemote: Boolean = false,
        tagTitle: String? = null,
        quotaStr: String? = null,
        quotaLabel: String? = null,
        resetTime: String? = null
    ): AgentSelectorItem {
        var iconType = AgentMapper.getIconTypeForProvider(agent.providerId) ?: AgentMapper.getIconType(agent.modelId)
        
        // Fallback for remote agents: if modelId is unrecognized, try matching the agent name.
        if (iconType == null && isRemote) {
            iconType = AgentMapper.getIconType(agent.name)
        }
        
        val finalIconType = iconType ?: "default"
        val modelInfo = KnownModelCatalog.infer(agent.modelId, agent.providerId)
        val providerName = AmayaProviderRegistry.displayName(agent.providerId)
        
        return AgentSelectorItem(
            id = agent.id,
            name = agent.name.ifBlank { "Unnamed Agent" },
            modelId = agent.modelId,
            tagTitle = tagTitle,
            quotaStr = quotaStr,
            quotaLabel = quotaLabel,
            resetTime = resetTime,
            isRemote = isRemote,
            iconType = finalIconType,
            providerId = agent.providerId,
            providerName = providerName,
            statusLabel = if (agent.enabled) "Enabled" else "Disabled",
            capabilityLabels = modelInfo.capabilities.map { it.label }.take(4),
            contextWindowLabel = modelInfo.contextWindow?.let { formatTokenCount(it).uppercase() },
            sourceLabel = modelInfo.sourceLabel,
            contextWindowTokens = modelInfo.contextWindow,
            maxOutputTokens = modelInfo.maxOutputTokens,
            inputPricePerMillionTokens = modelInfo.inputPricePerMillionTokens,
            outputPricePerMillionTokens = modelInfo.outputPricePerMillionTokens
        )
    }

    private fun formatTokenCount(count: Int): String = when {
        count >= 1_000_000 -> if (count % 1_000_000 == 0) "${count / 1_000_000}M" else String.format("%.1fM", count / 1_000_000.0)
        count >= 1_000 -> if (count % 1_000 == 0) "${count / 1_000}k" else String.format("%.1fk", count / 1_000.0)
        else -> count.toString()
    }
}
