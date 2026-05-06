package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.local.dao.AgentProfileDao
import com.amaya.intelligence.data.local.dao.ProviderConnectionDao
import com.amaya.intelligence.data.local.entity.AgentProfileEntity
import com.amaya.intelligence.data.local.entity.ProviderConnectionEntity
import com.amaya.intelligence.data.remote.api.AgentConfig
import com.amaya.intelligence.data.remote.api.AmayaProviderRegistry
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Bridges legacy AgentConfig settings into the new provider-connection and
 * agent-profile tables. This keeps the app runtime backward compatible while
 * the UI and resolver migrate to providerId/model catalog semantics.
 */
@Singleton
class ProviderConnectionRepository @Inject constructor(
    private val providerConnectionDao: ProviderConnectionDao,
    private val agentProfileDao: AgentProfileDao
) {
    suspend fun mirrorLegacyAgents(agents: List<AgentConfig>) {
        val activeConnectionIds = agents.map { legacyConnectionId(it.id) }
        val activeProfileIds = agents.map { it.id }
        if (activeConnectionIds.isEmpty()) {
            providerConnectionDao.deleteAllLegacyConnections()
        } else {
            providerConnectionDao.deleteLegacyConnectionsNotIn(activeConnectionIds)
        }
        if (activeProfileIds.isEmpty()) {
            agentProfileDao.deleteAllMirroredProfiles()
        } else {
            agentProfileDao.deleteMirroredProfilesNotIn(activeProfileIds)
        }

        agents.forEach { agent ->
            val connectionId = legacyConnectionId(agent.id)
            val provider = AmayaProviderRegistry.find(agent.providerId)
            providerConnectionDao.upsert(
                ProviderConnectionEntity(
                    id = connectionId,
                    providerId = agent.providerId.ifBlank { "openai" },
                    displayName = agent.name.ifBlank { provider?.displayName ?: "Unnamed Agent" },
                    baseUrl = agent.baseUrl,
                    enabled = agent.enabled,
                    configJson = JSONObject().apply {
                        put("legacyAgentId", agent.id)
                        put("providerType", agent.providerType)
                        put("modelId", agent.modelId)
                    }.toString(),
                    updatedAt = System.currentTimeMillis()
                )
            )
            agentProfileDao.upsert(
                AgentProfileEntity(
                    id = agent.id,
                    name = agent.name.ifBlank { "Unnamed Agent" },
                    providerConnectionId = connectionId,
                    defaultModelId = agent.modelId,
                    enabled = agent.enabled,
                    maxTokens = agent.maxTokens,
                    maxIterations = agent.maxIterations,
                    capabilityOverridesJson = JSONObject().apply {
                        put("toolCalling", agent.toolCalling)
                        put("vision", agent.vision)
                        put("reasoning", agent.reasoning)
                        put("structuredOutput", agent.structuredOutput)
                        put("embeddings", agent.embeddings)
                        put("jsonMode", agent.jsonMode)
                        put("streaming", agent.streaming)
                    }.toString(),
                    legacyAgentConfigJson = JSONObject().apply {
                        put("providerId", agent.providerId)
                        put("providerType", agent.providerType)
                        put("baseUrl", agent.baseUrl)
                        put("modelId", agent.modelId)
                    }.toString(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }

    private fun legacyConnectionId(agentId: String): String = "legacy_agent_connection_$agentId"
}
