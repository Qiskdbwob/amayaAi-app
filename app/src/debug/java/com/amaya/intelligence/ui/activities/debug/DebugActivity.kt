package com.amaya.intelligence.ui.activities.debug

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.amaya.intelligence.data.local.dao.AgentDao
import com.amaya.intelligence.data.local.dao.ConversationDao
import com.amaya.intelligence.data.remote.api.MessageRole
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.impl.local.LocalIntelligenceService
import com.amaya.intelligence.tools.ToolExecutor
import com.amaya.intelligence.tools.ToolResult
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

/** Debug-only ADB/UI harness for stream, tool, persistence, background, and delegation flows. */
@AndroidEntryPoint
class DebugActivity : AppCompatActivity() {
    @Inject lateinit var service: LocalIntelligenceService
    @Inject lateinit var toolExecutor: ToolExecutor
    @Inject lateinit var conversationDao: ConversationDao
    @Inject lateinit var agentDao: AgentDao
    @Inject lateinit var delegationTaskDao: com.amaya.intelligence.data.local.dao.DelegationTaskDao

    private lateinit var output: TextView
    private val rows = mutableListOf<JSONObject>()
    private var running = false
    // Debug runner must outlive Activity stop/destroy during background cycles.
    private val suiteScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mode by lazy { intent.getStringExtra("mode") ?: "all" }
    private val iterations by lazy { intent.getIntExtra("iterations", 100).coerceIn(1, 10_000) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        output = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 12f }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(Button(this).apply { text = "Run $mode"; setOnClickListener { launchSuite() } })
        root.addView(ScrollView(this).apply { addView(output) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        if (intent.getBooleanExtra("autorun", true)) launchSuite()
    }

    override fun onStop() {
        super.onStop()
        emit("LIFECYCLE onStop running=$running mode=$mode")
    }

    override fun onDestroy() {
        // Deliberately retain the debug runner until process death; background tests destroy the host.
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        emit("LIFECYCLE onStart running=$running mode=$mode")
    }

    private fun launchSuite() {
        if (running) return
        running = true
        rows.clear()
        suiteScope.launch {
            try {
                when (mode) {
                    "streaming" -> streamingSuite()
                    "stream-cancel-retry" -> streamCancelRetrySuite()
                    "tools" -> toolSuite()
                    "delegation" -> delegationSuite()
                    "delegation-matrix" -> delegationMatrixSuite()
                    "delegation-async-compact" -> delegationAsyncCompactSuite()
                    "delegation-stream-stress" -> delegationStreamingStressSuite()
                    "delegation-five-trace" -> delegationStreamingStressSuite(traceFive = true)
                    "corruption" -> corruptionSuite()
                    "persistence-deep" -> persistenceDeepSuite()
                    "stress" -> stressSuite()
                    "process-reclaim" -> processReclaimSuite()
                    "cancel-tool" -> cancelActiveToolSuite()
                    "parallel-streaming" -> parallelStreamingSuite()
                    "approval-death" -> approvalDeathSuite()
                    "background" -> backgroundSuite()
                    "background-stream" -> backgroundStreamingSuite()
                    "screen-off" -> screenOffSuite()
                    "screen-off-stream" -> screenOffStreamingSuite()
                    "kill-seed" -> killSeedSuite()
                    "offline" -> offlineSuite()
                    "approval" -> approvalSuite()
                    "soak" -> soakSuite()
                    "restore" -> recoverySuite()
                    "headless" -> {
                        toolSuite()
                        corruptionSuite()
                        stressSuite()
                    }
                    "all-no-stream" -> {
                        toolSuite()
                        corruptionSuite()
                        stressSuite()
                    }
                    else -> {
                        toolSuite()
                        corruptionSuite()
                        streamingSuite()
                        delegationSuite()
                        stressSuite()
                    }
                }
            } finally {
                writeReport()
                running = false
            }
        }
    }

    private suspend fun toolSuite() {
        check("tool-catalog-has-routable-definitions") {
            val registered = toolExecutor.getModelCallableTools().map { it.name }.toSet()
            val defined = toolExecutor.getToolDefinitions(AssistantMode.AGENT).map { it.name }.toSet()
            val wrappers = setOf("workspace_search", "workspace_change", "agent_memory", "skill", "reminder")
            val missing = defined - registered - wrappers
            JSONObject().put("registered", JSONArray(registered.sorted())).put("defined", JSONArray(defined.sorted())).put("missing", JSONArray(missing.sorted())) to (registered.isNotEmpty() && defined.isNotEmpty() && missing.isEmpty())
        }
        check("unknown-tool-fails-closed") {
            val result = toolExecutor.execute("debug_missing_tool", emptyMap(), toolCallId = "debug-unknown", assistantMode = AssistantMode.AGENT)
            JSONObject().put("result", result.toDebugJson()) to (result is ToolResult.Error)
        }
        check("missing-call-id-fails-closed") {
            val result = toolExecutor.execute("web_search", mapOf("query" to "amaya debug"), assistantMode = AssistantMode.CHAT)
            JSONObject().put("result", result.toDebugJson()) to (result is ToolResult.Error && result.message.contains("call ID"))
        }
        check("delegate-rejects-missing-group") {
            val result = toolExecutor.execute("delegate_agent", mapOf("agent_id" to 2, "title" to "debug", "task" to "noop"), toolCallId = "debug-delegate-no-group", assistantMode = AssistantMode.AGENT)
            JSONObject().put("result", result.toDebugJson()) to (result is ToolResult.Error)
        }
        check("all-tool-schema-smoke") {
            val rows = JSONArray()
            var crashes = 0
            toolExecutor.getToolDefinitions(AssistantMode.AGENT).forEachIndexed { index, definition ->
                val result = runCatching {
                    // Missing call ID prevents handlers from running. This probes routing,
                    // normalization, sanitization, workspace policy, and capability gates safely.
                    toolExecutor.execute(definition.name, emptyMap(), toolCallId = null, assistantMode = AssistantMode.AGENT)
                }.getOrElse { error ->
                    crashes++
                    ToolResult.Error("CRASH: ${error.message}")
                }
                rows.put(JSONObject().put("index", index).put("tool", definition.name).put("result", result.toDebugJson()))
            }
            JSONObject().put("tools", rows).put("count", rows.length()).put("crashes", crashes) to (rows.length() > 0 && crashes == 0)
        }
    }

    private suspend fun corruptionSuite() {
        val conversations = conversationDao.getAllConversations().first()
        var invalid = 0
        var orphanCalls = 0
        var large = 0
        conversations.forEach { header ->
            val entity = conversationDao.getConversationById(header.id) ?: return@forEach
            listOf(entity.messagesJson, entity.contextMessagesJson.ifBlank { entity.messagesJson }).forEach { raw ->
                if (raw.length > 2_000_000) large++
                val array = runCatching { JSONArray(raw) }.getOrElse { invalid++; return@forEach }
                for (index in 0 until array.length()) {
                    val message = array.optJSONObject(index) ?: continue
                    val executions = message.optJSONArray("toolExecutions") ?: continue
                    for (toolIndex in 0 until executions.length()) {
                        val execution = executions.optJSONObject(toolIndex) ?: continue
                        val status = execution.optString("status")
                        if (status in setOf("RUNNING", "PENDING") && execution.optString("result").isBlank()) orphanCalls++
                    }
                }
            }
        }
        record("conversation-json", invalid == 0 && orphanCalls == 0, JSONObject().put("conversations", conversations.size).put("invalidColumns", invalid).put("orphanActiveTools", orphanCalls).put("largeColumns", large))
    }

    private suspend fun streamingSuite() {
        val before = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() } ?: service.uiState.value
        if (before.activeModelKey.isBlank()) {
            record("streaming-live", false, JSONObject().put("error", "No active model"))
            return
        }
        service.clearConversation()
        service.setAssistantOwner(AssistantMode.CHAT)
        service.sendMessage("Reply exactly AMAYA_STREAM_OK. Do not call tools.")
        val started = withTimeoutOrNull(30_000) { service.uiState.filter { it.isStreaming && it.conversationId != null }.first() }
        val conversationId = started?.conversationId
        val finished = withTimeoutOrNull(120_000) { service.uiState.filter { !it.isStreaming && !it.isLoading && it.conversationId == conversationId && it.messages.any { message -> message.role == MessageRole.ASSISTANT } }.first() }
        val assistant = finished?.messages?.lastOrNull { it.role == MessageRole.ASSISTANT }
        record("streaming-live", assistant?.content?.contains("AMAYA_STREAM_OK") == true && assistant.metadata["turnStatus"] == "completed", JSONObject().put("started", started != null).put("conversationId", finished?.conversationId ?: conversationId ?: JSONObject.NULL).put("responseChars", assistant?.content?.length ?: 0).put("turnStatus", assistant?.metadata?.get("turnStatus") ?: "missing").put("error", finished?.error ?: JSONObject.NULL))
    }

    private suspend fun streamCancelRetrySuite() {
        val ready = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() } ?: service.uiState.value
        if (ready.activeModelKey.isBlank()) {
            record("stream-cancel-retry", false, JSONObject().put("error", "No active model"))
            return
        }
        service.clearConversation()
        service.setAssistantOwner(AssistantMode.CHAT)
        service.sendMessage("Count slowly from 1 to 500, one number per line. Do not call tools.")
        val started = withTimeoutOrNull(30_000) { service.uiState.filter { it.isStreaming && it.conversationId != null }.first() }
        delay(1_500)
        service.stopGeneration()
        val cancelled = withTimeoutOrNull(30_000) { service.uiState.filter { !it.isStreaming && !it.isLoading }.first() }
        val conversationId = (cancelled ?: started)?.conversationId?.toLongOrNull()
        val firstEntity = if (conversationId != null) withTimeoutOrNull(10_000) {
            var settled: com.amaya.intelligence.data.local.entity.ConversationEntity? = null
            while (settled == null) {
                val entity = conversationDao.getConversationById(conversationId)
                val status = entity?.let { parseStoredMessages(it.messagesJson).lastOrNull()?.optJSONObject("metadata")?.optString("turnStatus") }
                if (!status.isNullOrBlank()) settled = entity else delay(100)
            }
            settled
        } else null
        val firstMessages = firstEntity?.let { parseStoredMessages(it.messagesJson) }.orEmpty()
        val firstContext = firstEntity?.let { parseStoredMessages(it.contextMessagesJson.ifBlank { it.messagesJson }) }.orEmpty()
        val cancelledAssistant = firstMessages.lastOrNull { it.optString("role").equals("ASSISTANT", true) }
        val cancelledContextAssistant = firstContext.lastOrNull { it.optString("role").equals("ASSISTANT", true) }
        val firstAssistantCount = firstMessages.count { it.optString("role").equals("ASSISTANT", true) }
        val retryStarted = conversationId?.let { service.sendMessageToConversation(it, "Reply exactly RETRY_CONTEXT_OK. Do not repeat the count. Do not call tools.") } == true
        val retryFinished = if (conversationId != null && retryStarted) withTimeoutOrNull(120_000) {
            var settled: com.amaya.intelligence.data.local.entity.ConversationEntity? = null
            while (settled == null) {
                val entity = conversationDao.getConversationById(conversationId)
                val messages = entity?.let { parseStoredMessages(it.messagesJson) }.orEmpty()
                val assistants = messages.filter { message -> message.optString("role").equals("ASSISTANT", true) }
                val status = assistants.lastOrNull()?.optJSONObject("metadata")?.optString("turnStatus").orEmpty()
                if (assistants.size > firstAssistantCount && status in setOf("completed", "failed", "cancelled", "interrupted")) settled = entity else delay(250)
            }
            settled
        } else null
        val finalMessages = retryFinished?.let { parseStoredMessages(it.messagesJson) }.orEmpty()
        val finalContext = retryFinished?.let { parseStoredMessages(it.contextMessagesJson.ifBlank { it.messagesJson }) }.orEmpty()
        val finalAssistant = finalMessages.lastOrNull { it.optString("role").equals("ASSISTANT", true) }
        val contextStatuses = finalContext.filter { it.optString("role").equals("ASSISTANT", true) }.map { it.optJSONObject("metadata")?.optString("turnStatus") }
        val passed = conversationId != null && cancelledAssistant?.optJSONObject("metadata")?.optString("turnStatus") in setOf("cancelled", "completed") &&
            cancelledContextAssistant?.optJSONObject("metadata")?.optString("turnStatus") in setOf("cancelled", "completed") && retryStarted &&
            finalAssistant?.optString("content")?.contains("RETRY_CONTEXT_OK") == true &&
            finalAssistant.optJSONObject("metadata")?.optString("turnStatus") == "completed" &&
            contextStatuses.contains("cancelled") && contextStatuses.lastOrNull() == "completed"
        record("stream-cancel-retry", passed, JSONObject()
            .put("conversationId", conversationId ?: JSONObject.NULL)
            .put("cancelledVisibleStatus", cancelledAssistant?.optJSONObject("metadata")?.optString("turnStatus") ?: "missing")
            .put("cancelledContextStatus", cancelledContextAssistant?.optJSONObject("metadata")?.optString("turnStatus") ?: "missing")
            .put("retryStarted", retryStarted)
            .put("finalText", finalAssistant?.optString("content").orEmpty().take(300))
            .put("contextAssistantStatuses", JSONArray(contextStatuses)))
    }

    private suspend fun delegationSuite() {
        val requestedGroupId = intent.getLongExtra("group_id", -1L)
        val groupId = requestedGroupId.takeIf { it > 0 } ?: agentDao.observeGroups().first().firstOrNull()?.id ?: -1L
        val members = if (groupId > 0) agentDao.getByGroup(groupId) else emptyList()
        val sourceLocalId = intent.getLongExtra("source_agent_id", members.getOrNull(0)?.localId ?: 1L)
        val targetLocalId = intent.getLongExtra("target_agent_id", members.getOrNull(1)?.localId ?: 2L)
        if (groupId <= 0) {
            record("delegation-live", false, JSONObject().put("skipped", "No agent group found"))
            return
        }
        val source = members.firstOrNull { it.localId == sourceLocalId }
        val target = members.firstOrNull { it.localId == targetLocalId }
        if (source == null || target == null) {
            record("delegation-live", false, JSONObject().put("error", "Source/target agent missing").put("members", JSONArray(members.map { "${it.localId}:${it.name}" })))
            return
        }
        val events = mutableListOf<String>()
        val task = intent.getStringExtra("delegation_task")
            ?: "Reply exactly DELEGATE_FINAL_OK. Do not call tools."
        val result = withTimeoutOrNull(180_000) {
            toolExecutor.execute(
                toolName = "delegate_agent",
                arguments = mapOf("agent_id" to targetLocalId, "title" to "AI news handoff debug", "task" to task),
                workspacePath = filesDir.absolutePath,
                toolCallId = "debug-delegate-${System.currentTimeMillis()}",
                onEvent = { events += it::class.simpleName.orEmpty() },
                conversationId = "debug-parent-${System.currentTimeMillis()}",
                ownerId = groupId.toString(),
                agentId = source.id,
                assistantMode = AssistantMode.AGENT
            )
        } ?: ToolResult.Error("Delegation timed out after 180 seconds")
        val outputText = (result as? ToolResult.Success)?.output.orEmpty()
        record("delegation-live", result is ToolResult.Success && outputText.isNotBlank() && events.count { it == "SubagentUpdate" } >= 2, JSONObject().put("source", source.name).put("target", target.name).put("result", result.toDebugJson()).put("events", JSONArray(events)).put("expectedMarkerSeen", outputText.contains("DELEGATE_FINAL_OK")))
    }

    private suspend fun delegationAsyncCompactSuite() {
        val ready = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() }
        val groupId = intent.getLongExtra("group_id", -1L).takeIf { it > 0 }
            ?: agentDao.observeGroups().first().firstOrNull()?.id ?: -1L
        val members = if (groupId > 0) agentDao.getByGroup(groupId) else emptyList()
        if (ready == null || members.size < 2) {
            record("delegation-async-compact", false, JSONObject().put("error", "Need active model and two agents"))
            return
        }
        val source = members[0]
        val target = members[1]
        service.setAssistantOwner(AssistantMode.AGENT, groupId.toString(), filesDir.absolutePath, source.id)
        service.sendMessage("Delegate a task to agent ${target.localId} with title async-post. Then continue and reply ASYNC_PARENT_OK. Do not wait for delegate completion.")
        val started = withTimeoutOrNull(30_000) {
            service.uiState.filter { state -> state.isStreaming && state.conversationId != null }.first()
        }
        val sourceConversationId = started?.conversationId?.toLongOrNull()
        val pending = withTimeoutOrNull(30_000) {
            service.uiState.filter { state ->
                state.messages.any { it.toolExecutions.any { tool -> tool.metadata["delegationState"] == "pending" } }
            }.first()
        }
        val parentContinued = withTimeoutOrNull(120_000) {
            service.uiState.filter { state ->
                state.conversationId == sourceConversationId?.toString() &&
                    state.messages.any { it.content.contains("ASYNC_PARENT_OK") }
            }.first()
        }
        val task: com.amaya.intelligence.data.local.entity.DelegationTaskEntity? = withTimeoutOrNull(180_000) {
            while (true) {
                val item = delegationTaskDao.getLatestByGroup(groupId)
                if (item?.status in setOf("COMPLETED", "FAILED")) return@withTimeoutOrNull item
                delay(250)
            }
            null
        }
        val event = sourceConversationId?.let { id -> conversationDao.getConversationById(id)?.messagesJson?.let(::parseStoredMessages)?.lastOrNull { it.optJSONObject("metadata")?.optString("eventType") == "delegation_completed" } }
        record("delegation-async-compact", started != null && pending != null && parentContinued != null && task != null && event != null,
            JSONObject().put("started", started != null).put("pending", pending != null).put("parentContinued", parentContinued != null).put("taskStatus", task?.status ?: "missing").put("event", event ?: JSONObject.NULL))
    }

    /**
     * Real provider-stream regression for completion races. Each pattern asks Agent 1 to dispatch
     * all other group Agents in one streamed turn; persistent context must retain every result in
     * task creation order even if completions land while its response is ending.
     */
    private suspend fun delegationStreamingStressSuite(traceFive: Boolean = false) {
        val ready = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() }
        val groupId = intent.getLongExtra("group_id", -1L).takeIf { it > 0 }
            ?: agentDao.observeGroups().first().firstOrNull()?.id ?: -1L
        val members = if (groupId > 0) agentDao.getByGroup(groupId) else emptyList()
        val source = members.firstOrNull()
        val targets = members.drop(1).take(if (traceFive) 5 else 4)
        val minimumTargets = if (traceFive) 5 else 2
        val checkName = if (traceFive) "delegation-five-trace" else "delegation-stream-stress"
        if (ready == null || source == null || targets.size < minimumTargets) {
            record(checkName, false, JSONObject().put("error", "Need active model, source, and $minimumTargets targets").put("members", members.size))
            return
        }
        val patterns = if (traceFive) listOf(
            "five-way-trace" to "Dispatch all five delegations immediately in one provider response, then finish with a very short answer."
        ) else listOf(
            "parallel-burst" to "Dispatch every requested delegation immediately, then write a detailed 20-line work plan while the results arrive.",
            "terminal-race" to "Dispatch every requested delegation immediately, then give a very short answer and finish immediately.",
            "staggered-stream" to "Dispatch every requested delegation immediately, then write 40 numbered short lines slowly before your final answer."
        )
        val rounds = JSONArray()
        var failures = 0
        for ((pattern, behavior) in patterns) {
            awaitAgentIdle(source.id)
            if (traceFive) {
                // Agent identity owns one persistent conversation, but old debug runs may have
                // left historical rows behind. Remove the complete source/target set, not only
                // the latest row returned by the header query.
                (listOf(source) + targets).forEach { agent ->
                    conversationDao.deleteAgentConversations(agent.id)
                }
            }
            service.setAssistantOwner(AssistantMode.AGENT, groupId.toString(), filesDir.absolutePath, source.id)
            delay(500)
            withTimeoutOrNull(30_000) {
                service.uiState.filter { it.agentId == source.id && !it.isStreaming && !it.isLoadingHistory }.first()
            }
            val startedAt = System.currentTimeMillis()
            val markers = targets.mapIndexed { index, target -> "DELEGATION_${pattern.uppercase().replace('-', '_')}_${index + 1}_${target.localId}_OK" }
            val asks = targets.zip(markers).joinToString("\n") { (target, marker) ->
                "- Delegate to agent ${target.localId}: title stress-${pattern.take(8)}-${target.localId}; task: Reply exactly $marker. Do not call tools."
            }
            service.sendMessage("$asks\n$behavior Do not poll delegated agents. When results are delivered, incorporate all of them and reply STRESS_${pattern.uppercase().replace('-', '_')}_PARENT_OK.")
            val started = withTimeoutOrNull(30_000) { service.uiState.filter { it.isStreaming && it.conversationId != null }.first() }
            val conversationId = started?.conversationId?.toLongOrNull()
            val tasks: List<com.amaya.intelligence.data.local.entity.DelegationTaskEntity> = withTimeoutOrNull(90_000) {
                var created = emptyList<com.amaya.intelligence.data.local.entity.DelegationTaskEntity>()
                while (created.size < targets.size) {
                    created = delegationTaskDao.getByGroupSince(groupId, startedAt).filter { it.agentId in targets.map { target -> target.id } }
                    if (created.size < targets.size) delay(100)
                }
                created
            } ?: emptyList()
            val settledTasks: List<com.amaya.intelligence.data.local.entity.DelegationTaskEntity> = withTimeoutOrNull(240_000) {
                var current = emptyList<com.amaya.intelligence.data.local.entity.DelegationTaskEntity>()
                while (current.size != targets.size || current.any { it.status !in setOf("COMPLETED", "FAILED") }) {
                    current = delegationTaskDao.getByGroupSince(groupId, startedAt).filter { it.id in tasks.map { task -> task.id } }
                    if (current.size != targets.size || current.any { it.status !in setOf("COMPLETED", "FAILED") }) delay(200)
                }
                current
            } ?: emptyList()
            val completedTasks = settledTasks.filter { it.status == "COMPLETED" }.sortedBy { it.updatedAt }
            val expected = completedTasks.mapNotNull { task ->
                markers.getOrNull(targets.indexOfFirst { it.id == task.agentId })
            }
            val persisted: List<JSONObject> = if (conversationId != null) {
                withTimeoutOrNull(240_000) {
                    var context = emptyList<JSONObject>()
                    var deliveredIds = emptySet<String>()
                    while (!completedTasks.all { it.id.toString() in deliveredIds }) {
                        val entity = conversationDao.getConversationById(conversationId)
                        context = entity?.contextMessagesJson?.let(::parseStoredMessages).orEmpty()
                        deliveredIds = context.filter { it.optJSONObject("metadata")?.optString("eventType") == "delegation_completed" }
                            .map { it.optJSONObject("metadata")?.optString("delegationTaskId").orEmpty() }.toSet()
                        if (!completedTasks.all { it.id.toString() in deliveredIds }) delay(200)
                    }
                    context
                } ?: emptyList()
            } else emptyList()
            val taskIds = settledTasks.map { it.id.toString() }.toSet()
            val events = persisted.filter {
                val metadata = it.optJSONObject("metadata")
                metadata?.optString("eventType") == "delegation_completed" && metadata.optString("delegationTaskId") in taskIds
            }
            val eventDetails = events.map { it.optJSONObject("metadata")?.optString("eventDetail").orEmpty() }
            val deliveredTaskIds = events.map { it.optJSONObject("metadata")?.optString("delegationTaskId").orEmpty() }
            val deliveryOrders = events.map { it.optJSONObject("metadata")?.optLong("deliveryOrder", -1L) ?: -1L }
            val detailIndexes = expected.map { marker -> eventDetails.indexOfFirst { marker in it } }
            val completionIndexes = completedTasks.map { task -> deliveredTaskIds.indexOf(task.id.toString()) }
            val terminalToolTaskIds = persisted.flatMap { message ->
                buildList {
                    val executions = message.optJSONArray("toolExecutions")
                    for (index in 0 until (executions?.length() ?: 0)) {
                        val execution = executions?.optJSONObject(index) ?: continue
                        if (execution.optString("status") in setOf("SUCCESS", "ERROR")) {
                            execution.optJSONObject("metadata")?.optString("delegationTaskId")
                                ?.takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                    val steps = message.optJSONArray("steps")
                    for (index in 0 until (steps?.length() ?: 0)) {
                        val execution = steps?.optJSONObject(index)?.optJSONObject("execution") ?: continue
                        if (execution.optString("status") in setOf("SUCCESS", "ERROR")) {
                            execution.optJSONObject("metadata")?.optString("delegationTaskId")
                                ?.takeIf(String::isNotBlank)?.let(::add)
                        }
                    }
                }
            }.toSet()
            val pendingToolTaskIds = persisted.flatMap { message ->
                buildList {
                    val executions = message.optJSONArray("toolExecutions")
                    for (index in 0 until (executions?.length() ?: 0)) {
                        val execution = executions?.optJSONObject(index) ?: continue
                        if (execution.optString("status") in setOf("PENDING", "RUNNING")) {
                            execution.optJSONObject("metadata")?.optString("delegationTaskId")
                                ?.takeIf { it in taskIds }?.let(::add)
                        }
                    }
                }
            }.toSet()
            val duplicateEventTaskIds = deliveredTaskIds.groupingBy { it }.eachCount().filterValues { it != 1 }.keys
            val visible = conversationId?.let { id -> conversationDao.getConversationById(id)?.messagesJson?.let(::parseStoredMessages) }.orEmpty()
            val nestedEventTaskIds = visible.flatMap { message ->
                val steps = message.optJSONArray("steps")
                buildList {
                    for (index in 0 until (steps?.length() ?: 0)) {
                        val metadata = steps?.optJSONObject(index)?.optJSONObject("metadata") ?: continue
                        metadata.optString("delegationTaskId").takeIf { it in taskIds }?.let(::add)
                    }
                }
            }
            val parentAnswerCount = visible.count { message ->
                val content = message.optString("content")
                message.optLong("timestamp", 0L) >= startedAt &&
                    content.contains("STRESS_${pattern.uppercase().replace('-', '_')}_PARENT_OK") &&
                    expected.all(content::contains)
            }
            val parentDone = withTimeoutOrNull(240_000) {
                while (true) {
                    val entity = conversationId?.let { id -> conversationDao.getConversationById(id) }
                    val messages = entity?.messagesJson?.let(::parseStoredMessages).orEmpty()
                    val completed = messages.any { it.optString("content").contains("STRESS_${pattern.uppercase().replace('-', '_')}_PARENT_OK") && it.optJSONObject("metadata")?.optString("turnStatus") == "completed" }
                    if (completed) return@withTimeoutOrNull true
                    delay(200)
                }
                false
            } == true
            val passed = conversationId != null && tasks.size == targets.size && settledTasks.all { it.status == "COMPLETED" } &&
                expected.size == targets.size && detailIndexes.all { it >= 0 } && completionIndexes.all { it >= 0 } &&
                completedTasks.all { it.id.toString() in terminalToolTaskIds } && pendingToolTaskIds.isEmpty() &&
                deliveryOrders == deliveryOrders.sorted() && duplicateEventTaskIds.isEmpty() && nestedEventTaskIds.isEmpty() &&
                parentAnswerCount == 1 && parentDone
            if (!passed) failures++
            rounds.put(JSONObject()
                .put("pattern", pattern)
                .put("conversationId", conversationId ?: JSONObject.NULL)
                .put("tasksCreated", tasks.size)
                .put("taskStatuses", JSONArray(settledTasks.map { it.status }))
                .put("expectedMarkers", JSONArray(expected))
                .put("eventDetailIndexes", JSONArray(detailIndexes))
                .put("completionEventIndexes", JSONArray(completionIndexes))
                .put("deliveredTaskIds", JSONArray(deliveredTaskIds))
                .put("terminalToolTaskIds", JSONArray(terminalToolTaskIds))
                .put("pendingToolTaskIds", JSONArray(pendingToolTaskIds))
                .put("deliveryOrders", JSONArray(deliveryOrders))
                .put("duplicateEventTaskIds", JSONArray(duplicateEventTaskIds))
                .put("nestedEventTaskIds", JSONArray(nestedEventTaskIds))
                .put("parentAnswerCount", parentAnswerCount)
                .put("eventCount", eventDetails.size)
                .put("parentDone", parentDone)
                .put("passed", passed))
        }
        record(checkName, failures == 0, JSONObject().put("rounds", rounds).put("failures", failures))
    }

    private suspend fun awaitAgentIdle(agentId: Long) {
        conversationDao.getAgentConversation(agentId)?.id?.let { id -> service.loadConversation(id.toString()) }
        withTimeoutOrNull(60_000) {
            var idleSnapshots = 0
            while (idleSnapshots < 5) {
                if (service.uiState.value.isStreaming || service.uiState.value.isLoading) idleSnapshots = 0
                else idleSnapshots++
                delay(100)
            }
        }
    }

    private suspend fun delegationMatrixSuite() {
        val groupId = intent.getLongExtra("group_id", -1L).takeIf { it > 0 }
            ?: agentDao.observeGroups().first().firstOrNull()?.id ?: -1L
        val members = if (groupId > 0) agentDao.getByGroup(groupId) else emptyList()
        if (members.size < 2) {
            record("delegation-matrix", false, JSONObject().put("error", "Need at least two agents").put("members", members.size))
            return
        }
        val matrix = JSONArray()
        var failures = 0
        for (source in members) for (target in members) {
            if (source.id == target.id) continue
            val events = mutableListOf<String>()
            val marker = "MATRIX_${source.localId}_${target.localId}_OK"
            var attempt = 0
            var result: ToolResult = ToolResult.Error("Not started")
            while (attempt < 2) {
                attempt++
                result = withTimeoutOrNull(180_000) {
                    toolExecutor.execute(
                        toolName = "delegate_agent",
                        arguments = mapOf("agent_id" to target.localId, "title" to "Matrix ${source.localId}-${target.localId}", "task" to "Reply exactly $marker. Do not call tools."),
                        workspacePath = filesDir.absolutePath,
                        toolCallId = "matrix-${source.localId}-${target.localId}-${System.currentTimeMillis()}",
                        onEvent = { events += it::class.simpleName.orEmpty() },
                        conversationId = "debug-matrix-${System.currentTimeMillis()}",
                        ownerId = groupId.toString(),
                        agentId = source.id,
                        assistantMode = AssistantMode.AGENT
                    )
                } ?: ToolResult.Error("Timed out")
                if (result is ToolResult.Success && marker in result.output) break
                delay(1_000)
            }
            val output = (result as? ToolResult.Success)?.output.orEmpty()
            val passed = result is ToolResult.Success && marker in output && events.count { it == "SubagentUpdate" } >= 2
            if (!passed) failures++
            matrix.put(JSONObject().put("source", source.localId).put("target", target.localId).put("passed", passed).put("attempts", attempt).put("output", output.take(120)).put("events", JSONArray(events)))
        }
        record("delegation-matrix", failures == 0, JSONObject().put("pairs", matrix.length()).put("failures", failures).put("matrix", matrix))
    }

    private suspend fun processReclaimSuite() {
        val before = android.os.Process.myPid()
        withContext(Dispatchers.Main.immediate) { moveTaskToBack(true) }
        delay(2_000)
        val after = android.os.Process.myPid()
        record("process-reclaim", before == after, JSONObject().put("pidBefore", before).put("pidAfter", after).put("survived", before == after))
    }

    private suspend fun cancelActiveToolSuite() {
        val job = suiteScope.launch {
            toolExecutor.execute(
                "run_shell", mapOf("command" to "sleep 120", "timeout_ms" to 300_000),
                workspacePath = null, toolCallId = "cancel-active-${System.currentTimeMillis()}",
                onConfirmationRequired = { true }, assistantMode = AssistantMode.AGENT
            )
        }
        delay(1_000)
        val active = job.isActive
        job.cancel(); job.join()
        delay(500)
        val processGone = withContext(Dispatchers.IO) {
            ProcessBuilder("/system/bin/sh", "-c", "pgrep -f '^sleep 120$'").start().waitFor() != 0
        }
        record("cancel-active-tool", active && job.isCancelled && processGone, JSONObject().put("toolActive", active).put("jobCancelled", job.isCancelled).put("processGone", processGone))
    }

    private suspend fun parallelStreamingSuite() {
        val ready = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() }
        if (ready == null) { record("parallel-streaming", false, JSONObject().put("error", "No active model")); return }
        service.setAssistantOwner(AssistantMode.CHAT)
        val ids = (1..3).map { index -> service.clearConversation(); service.sendMessage("Reply exactly PARALLEL_${index}_OK. Do not call tools."); delay(250); service.uiState.value.conversationId }
        delay(120_000)
        val settled = ids.map { id -> conversationDao.getConversationById(id?.toLongOrNull() ?: -1L) }.mapNotNull { entity -> entity?.let { parseStoredMessages(it.messagesJson).lastOrNull() } }
        val passed = settled.size == 3 && settled.all { it.optString("content").contains("PARALLEL_") && it.optJSONObject("metadata")?.optString("turnStatus") == "completed" }
        record("parallel-streaming", passed, JSONObject().put("conversations", settled.size).put("statuses", JSONArray(settled.map { it.optJSONObject("metadata")?.optString("turnStatus") })))
    }

    private suspend fun approvalDeathSuite() {
        var requested = false
        var executed = false
        val job = suiteScope.launch {
            val result = toolExecutor.execute(
                "run_shell", mapOf("command" to "printf approval-death"),
                workspacePath = null, toolCallId = "approval-death-${System.currentTimeMillis()}",
                onConfirmationRequired = { requested = true; kotlinx.coroutines.awaitCancellation() },
                assistantMode = AssistantMode.AGENT
            )
            executed = result is ToolResult.Success
        }
        withTimeoutOrNull(10_000) { while (!requested) delay(50) }
        job.cancel(); job.join()
        record("approval-death", requested && job.isCancelled && !executed, JSONObject().put("approvalPending", requested).put("jobCancelled", job.isCancelled).put("executed", executed))
    }

    private suspend fun stressSuite() {
        var failures = 0
        repeat(iterations) { index ->
            val result = toolExecutor.execute("debug_missing_tool", mapOf("iteration" to index, "password" to "must-not-log"), toolCallId = "stress-$index", assistantMode = AssistantMode.AGENT)
            if (result !is ToolResult.Error) failures++
        }
        corruptionSuite()
        record("tool-lifecycle-stress", failures == 0, JSONObject().put("iterations", iterations).put("failures", failures).put("pssKb", memoryPssKb()))
    }

    private suspend fun backgroundStreamingSuite() {
        val ready = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() } ?: service.uiState.value
        if (ready.activeModelKey.isBlank()) {
            record("background-stream", false, JSONObject().put("error", "No active model"))
            return
        }
        service.clearConversation()
        service.setAssistantOwner(AssistantMode.CHAT)
        service.sendMessage("Write 80 short numbered lines, then end exactly BACKGROUND_STREAM_OK. Do not call tools.")
        val started = withTimeoutOrNull(30_000) { service.uiState.filter { it.isStreaming && it.conversationId != null }.first() }
        record("background-stream-started", started != null, JSONObject().put("conversationId", started?.conversationId ?: JSONObject.NULL))
        writeReport()
        withContext(Dispatchers.Main.immediate) { moveTaskToBack(true) }
        val finished = withTimeoutOrNull(180_000) { service.uiState.filter { !it.isStreaming && !it.isLoading && it.conversationId == started?.conversationId }.first() }
        val assistant = finished?.messages?.lastOrNull { it.role == MessageRole.ASSISTANT }
        record("background-stream-completed", assistant?.content?.contains("BACKGROUND_STREAM_OK") == true && assistant.metadata["turnStatus"] == "completed", JSONObject().put("responseChars", assistant?.content?.length ?: 0).put("status", assistant?.metadata?.get("turnStatus") ?: "missing"))
    }

    private suspend fun screenOffStreamingSuite() {
        val ready = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() } ?: service.uiState.value
        if (ready.activeModelKey.isBlank()) {
            record("screen-off-stream", false, JSONObject().put("error", "No active model"))
            return
        }
        service.clearConversation()
        service.setAssistantOwner(AssistantMode.CHAT)
        service.sendMessage("Write 120 short numbered lines, then end exactly SCREEN_OFF_STREAM_OK. Do not call tools.")
        val started = withTimeoutOrNull(30_000) { service.uiState.filter { it.isStreaming && it.conversationId != null }.first() }
        record("screen-off-stream-started", started != null, JSONObject().put("conversationId", started?.conversationId ?: JSONObject.NULL).put("instruction", "External runner must issue keyevent 26 now"))
        writeReport()
        delay(15_000)
        val finished = withTimeoutOrNull(180_000) { service.uiState.filter { !it.isStreaming && !it.isLoading && it.conversationId == started?.conversationId }.first() }
        val assistant = finished?.messages?.lastOrNull { it.role == MessageRole.ASSISTANT }
        record("screen-off-stream-completed", assistant?.content?.contains("SCREEN_OFF_STREAM_OK") == true && assistant.metadata["turnStatus"] == "completed", JSONObject().put("responseChars", assistant?.content?.length ?: 0).put("status", assistant?.metadata?.get("turnStatus") ?: "missing"))
    }

    private suspend fun killSeedSuite() {
        val ready = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() } ?: service.uiState.value
        if (ready.activeModelKey.isBlank()) {
            record("kill-seed", false, JSONObject().put("error", "No active model"))
            return
        }
        service.clearConversation()
        service.setAssistantOwner(AssistantMode.CHAT)
        service.sendMessage("Write 1000 numbered lines slowly. Do not call tools.")
        val started = withTimeoutOrNull(30_000) { service.uiState.filter { it.isStreaming && it.conversationId != null }.first() }
        record("kill-seed", started != null, JSONObject().put("conversationId", started?.conversationId ?: JSONObject.NULL))
        writeReport()
        withContext(Dispatchers.Main.immediate) { moveTaskToBack(true) }
        delay(300_000)
    }

    private suspend fun offlineSuite() {
        val ready = withTimeoutOrNull(10_000) { service.uiState.filter { it.activeModelKey.isNotBlank() }.first() } ?: service.uiState.value
        if (ready.activeModelKey.isBlank()) {
            record("offline", false, JSONObject().put("error", "No active model"))
            return
        }
        service.clearConversation()
        service.setAssistantOwner(AssistantMode.CHAT)
        service.sendMessage("Reply exactly OFFLINE_SHOULD_NOT_COMPLETE. Do not call tools.")
        val started = withTimeoutOrNull(30_000) { service.uiState.filter { it.isStreaming && it.conversationId != null }.first() }
        val conversationId = started?.conversationId?.toLongOrNull()
        emit("OFFLINE_REQUEST conversationId=${conversationId ?: "missing"}; external runner must disable Wi-Fi now")
        delay(8_000)
        val settled = if (conversationId != null) withTimeoutOrNull(90_000) {
            var result: com.amaya.intelligence.data.local.entity.ConversationEntity? = null
            while (result == null) {
                val entity = conversationDao.getConversationById(conversationId)
                val assistant = entity?.let { parseStoredMessages(it.messagesJson) }?.lastOrNull { it.optString("role").equals("ASSISTANT", true) }
                if (assistant?.optJSONObject("metadata")?.optString("turnStatus") in setOf("failed", "cancelled", "interrupted")) result = entity else delay(250)
            }
            result
        } else null
        val assistant = settled?.let { parseStoredMessages(it.messagesJson) }?.lastOrNull { it.optString("role").equals("ASSISTANT", true) }
        val status = assistant?.optJSONObject("metadata")?.optString("turnStatus").orEmpty()
        record("offline", started != null && settled != null && status != "completed", JSONObject().put("conversationId", conversationId ?: JSONObject.NULL).put("status", status.ifBlank { "missing" }).put("retryable", assistant?.optJSONObject("metadata")?.optString("retryable") ?: "missing"))
    }

    private suspend fun approvalSuite() {
        var declinedRequests = 0
        val declined = toolExecutor.execute(
            "run_shell",
            mapOf("command" to "printf approval-check"),
            workspacePath = getExternalFilesDir(null)?.absolutePath,
            toolCallId = "approval-decline-${System.currentTimeMillis()}",
            onConfirmationRequired = { declinedRequests++; false },
            assistantMode = AssistantMode.AGENT
        )
        var approvedRequests = 0
        val approved = toolExecutor.execute(
            "run_shell",
            mapOf("command" to "printf approval-check"),
            workspacePath = getExternalFilesDir(null)?.absolutePath,
            toolCallId = "approval-accept-${System.currentTimeMillis()}",
            onConfirmationRequired = { approvedRequests++; true },
            assistantMode = AssistantMode.AGENT
        )
        record("approval-decline-accept", declined is ToolResult.Error && declinedRequests == 1 && approved is ToolResult.Success && approvedRequests == 1, JSONObject().put("declined", declined.toDebugJson()).put("declinedRequests", declinedRequests).put("approved", approved.toDebugJson()).put("approvedRequests", approvedRequests))
    }

    private suspend fun soakSuite() {
        val rounds = iterations.coerceAtMost(10_000)
        var failures = 0
        repeat(rounds) { index ->
            val result = toolExecutor.execute("debug_missing_tool", mapOf("iteration" to index), toolCallId = "soak-$index", assistantMode = AssistantMode.AGENT)
            if (result !is ToolResult.Error) failures++
            if (index % 100 == 0) delay(1)
        }
        corruptionSuite()
        record("soak", failures == 0, JSONObject().put("iterations", rounds).put("failures", failures).put("pssKb", memoryPssKb()))
    }

    private suspend fun persistenceDeepSuite() {
        val conversations = conversationDao.getAllConversations().first()
        var failures = 0
        var assistantTurns = 0
        var terminalTurns = 0
        var replayFailures = 0
        var duplicateToolIds = 0
        var invalidResponseItems = 0
        conversations.forEach { header ->
            val entity = conversationDao.getConversationById(header.id) ?: return@forEach
            val columns = listOf(entity.messagesJson, entity.contextMessagesJson.ifBlank { entity.messagesJson })
            columns.forEach { raw ->
                val messages = runCatching { JSONArray(raw) }.getOrElse { failures++; return@forEach }
                for (index in 0 until messages.length()) {
                    val message = messages.optJSONObject(index)
                    if (message == null) {
                        failures++
                        continue
                    }
                    if (message.optString("role") == "ASSISTANT") {
                        assistantTurns++
                        val metadata = message.optJSONObject("metadata")
                        if (metadata?.optString("turnStatus") in setOf("completed", "failed", "cancelled", "interrupted")) terminalTurns++
                        val items = message.optJSONArray("responseItems") ?: JSONArray()
                        for (itemIndex in 0 until items.length()) if (runCatching { JSONObject(items.optString(itemIndex)) }.isFailure) invalidResponseItems++
                        val executions = message.optJSONArray("toolExecutions") ?: JSONArray()
                        val calls = mutableListOf<String>()
                        val results = mutableSetOf<String>()
                        for (toolIndex in 0 until executions.length()) {
                            val tool = executions.optJSONObject(toolIndex) ?: continue
                            val id = tool.optString("toolCallId")
                            if (id.isBlank()) failures++ else calls += id
                            if (tool.optString("result").isNotBlank()) results += id
                        }
                        if (calls.toSet() != results) replayFailures++
                        duplicateToolIds += calls.size - calls.toSet().size
                    }
                }
            }
        }
        val passed = failures == 0 && replayFailures == 0 && duplicateToolIds == 0 && invalidResponseItems == 0
        record("persistence-deep", passed, JSONObject().put("conversations", conversations.size).put("assistantTurns", assistantTurns).put("terminalTurns", terminalTurns).put("parseFailures", failures).put("replayFailures", replayFailures).put("duplicateToolIds", duplicateToolIds).put("invalidResponseItems", invalidResponseItems))
    }

    private suspend fun screenOffSuite() {
        val before = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        record("screen-off-before", before, JSONObject().put("screenOn", before).put("instruction", "External runner must issue keyevent 26"))
        delay(5_000)
        val after = getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
        record("screen-off-cycle", after, JSONObject().put("screenOnAfterWait", after).put("process", android.os.Process.myPid()).put("note", "Wake verification belongs to external ADB runner"))
    }

    private suspend fun recoverySuite() {
        withTimeoutOrNull(10_000) {
            while (service.runningSessions.value.isNotEmpty()) delay(100)
        }
        corruptionSuite()
        val conversations = conversationDao.getAllConversations().first()
        var interrupted = 0
        var cancelledApprovals = 0
        conversations.forEach { header ->
            val entity = conversationDao.getConversationById(header.id) ?: return@forEach
            listOf(entity.messagesJson, entity.contextMessagesJson.ifBlank { entity.messagesJson }).forEach { raw ->
                parseStoredMessages(raw).forEach { message ->
                    if (message.optJSONObject("metadata")?.optString("turnStatus") == "interrupted") interrupted++
                    val tools = message.optJSONArray("toolExecutions") ?: JSONArray()
                    for (index in 0 until tools.length()) {
                        val tool = tools.optJSONObject(index) ?: continue
                        if (tool.optJSONObject("metadata")?.optString("approvalState") == "cancelled") cancelledApprovals++
                    }
                }
            }
        }
        record("recovery-state", true, JSONObject().put("conversations", conversations.size).put("interruptedMessages", interrupted).put("cancelledApprovals", cancelledApprovals))
    }

    private suspend fun backgroundSuite() {
        record("background-entered", true, JSONObject().put("phase", "before_home").put("pssKb", memoryPssKb()))
        writeReport()
        emit("BACKGROUND moving task to Home; suite waits 15 seconds")
        val moved = withContext(Dispatchers.Main.immediate) { moveTaskToBack(true) }
        emit("BACKGROUND moveTaskToBack result=$moved")
        delay(15_000)
        record("background-survival", !isFinishing, JSONObject().put("isFinishing", isFinishing).put("pssKb", memoryPssKb()).put("moved", moved).put("note", "Process survived automated background wait"))
    }

    private suspend fun check(name: String, block: suspend () -> Pair<JSONObject, Boolean>) {
        val started = System.currentTimeMillis()
        val result = runCatching { block() }
        val pair = result.getOrElse { JSONObject().put("error", it.stackTraceToString()) to false }
        pair.first.put("durationMs", System.currentTimeMillis() - started)
        record(name, pair.second, pair.first)
    }

    private fun record(name: String, passed: Boolean, detail: JSONObject) {
        val row = JSONObject().put("name", name).put("passed", passed).put("detail", detail).put("timestamp", System.currentTimeMillis())
        rows += row
        emit("CHECK name=$name passed=$passed detail=$detail")
    }

    private suspend fun writeReport() = withContext(Dispatchers.IO) {
        val passed = rows.count { it.optBoolean("passed") }
        val report = JSONObject().put("mode", mode).put("summary", JSONObject().put("passed", passed).put("failed", rows.size - passed).put("total", rows.size)).put("checks", JSONArray(rows))
        val file = File(getExternalFilesDir(null), "debug-report-$mode.json")
        file.writeText(report.toString(2))
        emit("SUMMARY ${report.getJSONObject("summary")}")
        emit("REPORT ${file.absolutePath}")
    }

    private fun emit(message: String) {
        Log.i("AmayaDebug", message)
        if (::output.isInitialized) runOnUiThread { output.append("\n$message") }
    }

    private fun memoryPssKb(): Int = android.os.Debug.MemoryInfo().also(android.os.Debug::getMemoryInfo).totalPss

    private fun parseStoredMessages(raw: String): List<JSONObject> {
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull(array::optJSONObject)
    }
}

private fun ToolResult.toDebugJson(): JSONObject = when (this) {
    is ToolResult.Success -> JSONObject().put("type", "success").put("outputChars", output.length).put("output", output.take(1_000))
    is ToolResult.Deferred -> JSONObject().put("type", "deferred").put("taskId", taskId).put("outputChars", output.length).put("output", output.take(1_000))
    is ToolResult.Error -> JSONObject().put("type", "error").put("errorType", errorType.name).put("recoverable", recoverable).put("message", message.take(1_000))
    is ToolResult.RequiresConfirmation -> JSONObject().put("type", "approval").put("reason", reason.take(1_000))
}
