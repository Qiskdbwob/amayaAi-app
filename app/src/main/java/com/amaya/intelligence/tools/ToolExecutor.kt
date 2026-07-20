package com.amaya.intelligence.tools

import com.amaya.intelligence.data.repository.TerminalSettingsRepository
import com.amaya.intelligence.domain.models.AssistantMode
import com.amaya.intelligence.domain.security.CommandValidator
import com.amaya.intelligence.domain.security.RiskLevel
import com.amaya.intelligence.domain.security.ValidationResult
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central executor for all AI tools.
 *
 * This class:
 * 1. Routes tool calls to the appropriate handler
 * 2. Applies security validation
 * 3. Handles confirmation flows
 * 4. Provides tool definitions for AI prompts
 */
@Singleton
class ToolExecutor @Inject constructor(
    private val listFilesTool: ListFilesTool,
    private val readFileTool: ReadFileTool,
    private val writeFileTool: WriteFileTool,
    private val createDirectoryTool: CreateDirectoryTool,
    private val deleteFileTool: DeleteFileTool,
    private val runShellTool: RunShellTool,
    private val editFileTool: EditFileTool,           // now includes apply_diff
    private val findFilesTool: FindFilesTool,         // now includes search_files
    // Memory, skills, session recall & reminder tools
    private val createReminderTool: CreateReminderTool,
    private val updateMemoryTool: UpdateMemoryTool,
    private val memoryManageTool: MemoryManageTool,
    private val agentMemoryTool: AgentMemoryTool,
    private val skillViewTool: SkillViewTool,
    private val skillManageTool: SkillManageTool,
    private val sessionSearchTool: SessionSearchTool,
    // Todo tool
    private val updateTodoTool: UpdateTodoTool,
    // Subagent tool
    private val invokeSubagentsTool: InvokeSubagentsTool,
    private val delegateAgentTool: DelegateAgentTool,
    // Web search / browser tools
    private val webSearchTool: WebSearchTool,
    private val browserUseToolset: BrowserUseToolset,
    private val commandValidator: CommandValidator,
    private val terminalSettingsRepository: TerminalSettingsRepository
) {

    private val tools: Map<String, Tool> by lazy {
        mapOf(
            listFilesTool.name      to listFilesTool,
            readFileTool.name       to readFileTool,
            writeFileTool.name      to writeFileTool,
            createDirectoryTool.name to createDirectoryTool,
            deleteFileTool.name     to deleteFileTool,
            runShellTool.name       to runShellTool,
            editFileTool.name       to editFileTool,
            findFilesTool.name      to findFilesTool,
            createReminderTool.name to createReminderTool,
            updateMemoryTool.name   to updateMemoryTool,
            memoryManageTool.name   to memoryManageTool,
            agentMemoryTool.name    to agentMemoryTool,
            skillViewTool.name      to skillViewTool,
            skillManageTool.name    to skillManageTool,
            sessionSearchTool.name  to sessionSearchTool,
            updateTodoTool.name     to updateTodoTool,
            invokeSubagentsTool.name to invokeSubagentsTool,
            delegateAgentTool.name to delegateAgentTool,
            webSearchTool.name      to webSearchTool
        ) + browserUseToolset.tools.associateBy { it.name }
    }

    /**
     * Execute a tool by name with the given arguments.
     *
     * @param toolName Name of the tool to execute
     * @param arguments Map of argument name to value
     * @param workspacePath Optional workspace path to use as default working directory
     * @param onConfirmationRequired Callback when user confirmation is needed
     * @return Result of the tool execution
     */
    suspend fun execute(
        toolName: String,
        arguments: Map<String, Any?>,
        workspacePath: String? = null,
        toolCallId: String? = null,
        onEvent: (suspend (Any) -> Unit)? = null,
        onConfirmationRequired: suspend (ConfirmationRequest) -> Boolean = { false },
        providerConnection: com.amaya.intelligence.data.remote.api.ProviderConnection? = null,
        selectedModelId: String? = null,
        readOnly: Boolean = false,
        conversationId: String? = null,
        ownerId: String? = null,
        agentId: Long? = null,
        assistantMode: AssistantMode = AssistantMode.PROJECT,
        agentCapabilityProfile: com.amaya.intelligence.domain.models.AgentCapabilityProfile? = null
    ): ToolResult {
        if (!assistantModeAllowsCapability(toolName, assistantMode, agentCapabilityProfile)) {
            return ToolResult.Error("Tool '$toolName' is unavailable in ${assistantMode.name.lowercase()} mode.", ErrorType.PERMISSION_ERROR)
        }
        val normalizedArguments = normalizeIntegerArguments(toolName, arguments)
        val capabilityCall = CapabilityToolMapper.map(toolName, normalizedArguments)
        val handlerName = if (toolName == "agent_memory") agentMemoryTool.name else capabilityCall?.handlerName ?: toolName
        val handlerArguments = if (toolName == "agent_memory") normalizedArguments else capabilityCall?.arguments ?: normalizedArguments
        val tool = tools[handlerName]
            ?: return ToolResult.Error(
                "Unknown tool: $toolName. Available: ${getModelCallableTools().map { it.name }.joinToString()}",
                ErrorType.VALIDATION_ERROR
            )
        if (tool.visibility != ToolVisibility.MODEL) {
            return ToolResult.Error(
                "Tool '$toolName' is ${tool.visibility.name.lowercase()} and is not callable from the normal model tool loop.",
                ErrorType.PERMISSION_ERROR
            )
        }

        val safeArguments = sanitizeModelArguments(handlerArguments).getOrElse { error ->
            return ToolResult.Error(error.message.orEmpty(), ErrorType.VALIDATION_ERROR)
        }
        val resolvedArguments = WorkspacePathResolver.resolve(handlerName, safeArguments, workspacePath).getOrElse { error ->
            return ToolResult.Error(error.message.orEmpty(), ErrorType.SECURITY_VIOLATION, recoverable = true)
        }
        val modelArguments = applyHostExecutionContext(handlerName, resolvedArguments, workspacePath)
        if (readOnly && !isAllowedInReadOnlyMode(handlerName)) {
            return ToolResult.Error("Tool '$toolName' is unavailable to read-only subagents.", ErrorType.PERMISSION_ERROR)
        }
        val callIdentity = toolCallId ?: return ToolResult.Error(
            "Tool call '$toolName' is missing a call ID; approval cannot be bound safely.",
            ErrorType.VALIDATION_ERROR
        )
        val executionContext = ToolExecutionContext(
            toolCallId = callIdentity,
            workspacePath = workspacePath,
            onEvent = onEvent,
            providerConnection = providerConnection,
            selectedModelId = selectedModelId,
            conversationId = conversationId,
            ownerId = ownerId,
            agentId = agentId,
            assistantMode = assistantMode,
            readOnly = readOnly
        )

        // Pre-validate model-owned arguments only.
        val validation = commandValidator.validateToolCall(
            handlerName,
            modelArguments,
            terminalSettingsRepository.getSettings()
        )

        when (validation) {
            is ValidationResult.Denied -> {
                return ToolResult.Error(
                    validation.reason,
                    ErrorType.SECURITY_VIOLATION
                )
            }

            is ValidationResult.RequiresConfirmation -> {
                val confirmed = onConfirmationRequired(
                    ConfirmationRequest(
                        toolName = CapabilityToolMapper.displayName(toolName, arguments),
                        reason = validation.reason,
                        details = modelArguments.toString(),
                        riskLevel = validation.riskLevel,
                        toolCallId = callIdentity
                    )
                )

                if (!confirmed) {
                    return ToolResult.Error(
                        "User declined: ${validation.reason}",
                        ErrorType.PERMISSION_ERROR
                    )
                }
            }

            is ValidationResult.Allowed -> { /* proceed */ }
        }

        suspend fun run(context: ToolExecutionContext): ToolResult =
            if (tool is ContextAwareTool) tool.execute(modelArguments, context)
            else tool.execute(modelArguments)

        val approvedContext = executionContext.copy(
            confirmed = validation is ValidationResult.RequiresConfirmation
        )
        val result = run(approvedContext)

        // Handle nested confirmation requests from tools.
        if (result is ToolResult.RequiresConfirmation) {
            val confirmed = onConfirmationRequired(
                ConfirmationRequest(
                    toolName = CapabilityToolMapper.displayName(toolName, arguments),
                    reason = result.reason,
                    details = result.details,
                    riskLevel = RiskLevel.MEDIUM,
                    toolCallId = callIdentity
                )
            )

            if (!confirmed) {
                return ToolResult.Error(
                    "User declined: ${result.reason}",
                    ErrorType.PERMISSION_ERROR
                )
            }

            return run(approvedContext.copy(confirmed = true))
        }

        return result
    }

    private fun normalizeIntegerArguments(toolName: String, arguments: Map<String, Any?>): Map<String, Any?> {
        val integerNames = getToolDefinitions().firstOrNull { it.name == toolName }
            ?.parameters
            ?.filter { it.type.equals("integer", ignoreCase = true) }
            ?.mapTo(mutableSetOf()) { it.name }
            .orEmpty()
        if (integerNames.isEmpty()) return arguments
        return arguments.mapValues { (name, value) ->
            if (name in integerNames) com.amaya.intelligence.data.repository.normalizeIntegerArgument(value) else value
        }
    }

    /**
     * Get all available tools.
     */
    fun getModelCallableTools(): List<Tool> = tools.values.filter { it.visibility == ToolVisibility.MODEL }

    fun getReadOnlyToolDefinitions(): List<ToolDefinition> = getToolDefinitions()
        .filter { it.name in setOf("read_file", "workspace_search", "web_search", "memory", "skill") }
        .map { definition ->
            when (definition.name) {
                "memory" -> definition.copy(parameters = listOf(
                    ToolParameter("operation", "string", "recall_sessions", enum = listOf("recall_sessions")),
                    ToolParameter("query", "string", "Session recall query")
                ))
                "skill" -> definition.copy(parameters = listOf(
                    ToolParameter("operation", "string", "view", enum = listOf("view")),
                    ToolParameter("skill_id", "string", "Skill id/name")
                ))
                else -> definition
            }
        }

    /**
     * Get model-callable tool definitions for AI prompts (JSON Schema format).
     */
    fun getToolDefinitions(
        mode: AssistantMode = AssistantMode.PROJECT,
        agentCapabilityProfile: com.amaya.intelligence.domain.models.AgentCapabilityProfile? = null,
        delegationAgentIds: List<Long> = emptyList()
    ): List<ToolDefinition> {
        val definitions = listOf(
            ToolDefinition(
                name = "workspace_search",
                description = "Search the active workspace. Use list to list a directory (omit path for root), find_name for filename patterns, and find_text for content.",
                parameters = listOf(
                    ToolParameter("operation", "string", "list, find_name, or find_text", enum = listOf("list", "find_name", "find_text")),
                    ToolParameter("path", "string", "Optional path relative to the active workspace", required = false),
                    ToolParameter("query", "string", "Required for find_name/find_text", required = false),
                    ToolParameter("max_depth", "integer", "Maximum depth", required = false),
                    ToolParameter("max_results", "integer", "Maximum results", required = false)
                )
            ),
            ToolDefinition(
                name = "workspace_change",
                description = "Change files in the active workspace. Use write, append, replace, patch, mkdir, or delete.",
                parameters = listOf(
                    ToolParameter("operation", "string", "write, append, replace, patch, mkdir, or delete", enum = listOf("write", "append", "replace", "patch", "mkdir", "delete")),
                    ToolParameter("path", "string", "Path relative to the active workspace", required = true),
                    ToolParameter("content", "string", "Content for write/append", required = false),
                    ToolParameter("old_text", "string", "Exact text for replace", required = false),
                    ToolParameter("new_text", "string", "Replacement text", required = false),
                    ToolParameter("diff", "string", "Unified diff for patch", required = false)
                )
            ),
            ToolDefinition(
                name = "list_files",
                description = "List files and directories in the active workspace using native APIs for high performance.",
                parameters = listOf(
                    ToolParameter("path", "string", "Optional path relative to the active workspace; omit for its root", required = false),
                    ToolParameter("pattern", "string", "Regex pattern to filter results", required = false),
                    ToolParameter("max_depth", "integer", "Maximum depth to recurse (default: 1)", required = false),
                    ToolParameter("include_hidden", "boolean", "Include hidden files (default: false)", required = false)
                )
            ),
            ToolDefinition(
                name = "read_file",
                description = "Read text files and document formats (DOCX, XLSX, PPTX, ODT, ODS, RTF). Single: pass 'path'. Batch: pass 'paths' array (max 10). Info-only: pass 'info_only=true' for file metadata. Documents are automatically extracted to plain text. Note: PDF currently unavailable.",
                parameters = listOf(
                    ToolParameter("path", "string", "Path relative to the active workspace", required = false),
                    ToolParameter("paths", "array", "Array of paths relative to the active workspace (max 10)", required = false, items = "string"),
                    ToolParameter("start_line", "integer", "Start reading from this line (1-indexed)", required = false),
                    ToolParameter("end_line", "integer", "Stop reading at this line (inclusive)", required = false),
                    ToolParameter("info_only", "boolean", "Return only metadata (size, modified, permissions) instead of content", required = false),
                    ToolParameter("max_lines", "integer", "Max lines per file in batch mode (default: 100)", required = false)
                )
            ),
            ToolDefinition(
                name = "write_file",
                description = "Write content using best-effort temp-file replacement. Supports text files and document formats (DOCX, XLSX, PPTX, ODT, ODS, ODP). " +
                    "Automatically creates parent directories if they don't exist. Use append=true to add content without overwriting. For documents: creates new document with plain text content.",
                parameters = listOf(
                    ToolParameter("path", "string", "Path relative to the active workspace. Parent directories are created automatically if missing.", required = true),
                    ToolParameter("content", "string", "Content to write (plain text for documents)", required = true),
                    ToolParameter("validate_syntax", "boolean", "Validate code syntax (default: false)", required = false),
                    ToolParameter("create_dirs", "boolean", "Create parent directories if they don't exist (default: true)", required = false),
                    ToolParameter("append", "boolean", "Append to existing content instead of overwrite (default: false)", required = false)
                )
            ),
            ToolDefinition(
                name = "create_directory",
                description = "Create a directory and any necessary parent directories.",
                parameters = listOf(
                    ToolParameter("path", "string", "Path relative to the active workspace", required = true)
                )
            ),
            ToolDefinition(
                name = "delete_file",
                description = "Safely delete a file or directory by moving it to trash. Can be recovered from .trash directory.",
                parameters = listOf(
                    ToolParameter("path", "string", "Path relative to the active workspace", required = true),
                    ToolParameter("permanent", "boolean", "Permanently delete (dangerous, default: false)", required = false)
                )
            ),
            ToolDefinition(
                name = "run_shell",
                description = """Run a shell command. Supports:
                    - Git operations (git status, git diff, git commit)
                    - Complex text search (grep with regex)
                    - Build tools (gradle, make)

                    Do NOT use for basic file operations - use native tools instead.""".trimIndent(),
                parameters = listOf(
                    ToolParameter("command", "string", "The shell command to run", required = true),
                    ToolParameter("timeout_ms", "integer", "Timeout in milliseconds (default: 30000, max: 300000)", required = false)
                )
            ),
            ToolDefinition(
                name = "edit_file",
                description = "Edit a workspace file by replacing text or applying a unified diff. Supports text files and document formats (DOCX, XLSX, PPTX, ODT, ODS, ODP). Use 'old_content'+'new_content' for text replacement, or 'diff' for patch mode (text files only).",
                parameters = listOf(
                    ToolParameter("path", "string", "Path relative to the active workspace", required = true),
                    ToolParameter("old_content", "string", "Exact text to find and replace", required = false),
                    ToolParameter("new_content", "string", "Text to replace with", required = false),
                    ToolParameter("diff", "string", "Unified diff content (@@ hunks) to apply as patch (text files only)", required = false),
                    ToolParameter("all_occurrences", "boolean", "Replace all occurrences (default: false)", required = false),
                    ToolParameter("dry_run", "boolean", "Preview changes without saving (default: false)", required = false)
                )
            ),
            ToolDefinition(
                name = "find_files",
                description = "Find files by name pattern (glob) or search content within files. Use 'pattern' for filename glob, or 'content' for grep-style search.",
                parameters = listOf(
                    ToolParameter("path", "string", "Directory path to search in", required = true),
                    ToolParameter("pattern", "string", "Glob pattern to match filenames (e.g., *.kt)", required = false),
                    ToolParameter("content", "string", "Text to search for inside files (grep mode)", required = false),
                    ToolParameter("case_sensitive", "boolean", "Case-sensitive content search (default: false)", required = false),
                    ToolParameter("type", "string", "Filter: 'file', 'directory', or 'all' (default: all)", required = false),
                    ToolParameter("max_depth", "integer", "Maximum search depth (default: 10)", required = false),
                    ToolParameter("max_results", "integer", "Maximum results (default: 50)", required = false)
                )
            ),
            // ── Subagent tool ──────────────────────────────────────────────────────
            ToolDefinition(
                name = "delegate_agent",
                description = "Delegate one focused task to a named persistent Agent in this group. Use the group-local agent_id from the host directory. This is different from invoke_subagents, which creates temporary parallel read-only workers.",
                parameters = listOf(
                    ToolParameter("title", "string", "Short delegation title, 2-5 words; do not repeat the task", required = true),
                    ToolParameter(
                        "agent_id",
                        "integer",
                        "Group-local member ID from the active agent directory; IDs restart at 1 per group${delegationAgentIds.takeIf(List<Long>::isNotEmpty)?.joinToString(prefix = ": ").orEmpty()}",
                        required = true
                    ),
                    ToolParameter("task", "string", "Focused task with all needed context", required = true)
                )
            ),
            ToolDefinition(
                name = "invoke_subagents",
                description = "Spawn up to 4 independent AI subagents running IN PARALLEL. " +
                    "Each subagent has its own task and read-only workspace research tools. " +
                    "Use for independent sub-tasks (reading multiple folders, auditing different layers). " +
                    "Subagents do NOT see conversation history — include ALL context in task. " +
                    "Returns combined summary from all subagents.",
                parameters = listOf(
                    ToolParameter(
                        name = "title",
                        type = "string",
                        description = "Short title for this parallel work shown in UI header (e.g. 'Auditing codebase'). Required.",
                        required = true
                    ),
                    ToolParameter(
                        name = "subagents",
                        type = "array",
                        description = "List of subagent tasks. Each: {task_name: string (≤5 words), task: string (full self-contained prompt)}",
                        required = true,
                        items = "object"
                    )
                )
            ),
            ToolDefinition(
                name = "web_search",
                description = "Search the web and fetch result pages as extracted readable text only. Safe for deep research: the AI may call many web_search tools in parallel. Returns one JSON object with search_results and pages[].text; raw DOM, HTML, screenshots, and browser state are omitted. Use urls[] to fetch known links directly, or query to discover links first.",
                parameters = listOf(
                    ToolParameter("query", "string", "Search query. Optional when urls is provided.", required = false),
                    ToolParameter("urls", "array", "Known URLs to fetch directly. Can be combined with query results.", required = false, items = "string"),
                    ToolParameter("max_results", "integer", "Maximum search results to collect (default: 5, max: 10).", required = false),
                    ToolParameter("max_pages", "integer", "Maximum result/URL pages to fetch and extract (default: max_results, max: 10).", required = false),
                    ToolParameter("max_chars_per_page", "integer", "Maximum extracted text characters per page (default: 5000, max: 12000).", required = false)
                )
            ),
            // ── Todo / Task list tool ──────────────────────────────────────────────
            ToolDefinition(
                name = "update_todo",
                description = "Update the live task list shown above the chat input. " +
                    "Call at START of multi-step task with merge=false to set full plan, then merge=true to update progress. " +
                    "Status: 'pending', 'in_progress', 'completed'.",
                parameters = listOf(
                    ToolParameter("merge", "boolean",
                        "true=merge by id into existing list, false=replace all items.", required = true),
                    ToolParameter("todos", "array",
                        "Todo items. Each: {id: int, status: string, content: string, active_form: string (optional)}",
                        required = true, items = "object")
                )
            ),
            // ── Memory & Reminder tools ────────────────────────────────────────────
            ToolDefinition(
                name = "memory",
                description = "Manage active saved memory or recall previous sessions. Update requires the version returned by list/search.",
                parameters = listOf(
                    ToolParameter("operation", "string", "save, list, search, update, or recall_sessions", enum = listOf("save", "list", "search", "update", "recall_sessions")),
                    ToolParameter("id", "string", "Memory id for update", required = false),
                    ToolParameter("expected_version", "integer", "Required for update", required = false),
                    ToolParameter("content", "string", "Memory content", required = false),
                    ToolParameter("query", "string", "Search or recall query", required = false),
                    ToolParameter("title", "string", "Short memory title or list/search header", required = false),
                    ToolParameter("type", "string", "user_profile or workspace_fact", required = false,
                        enum = listOf("user_profile", "workspace_fact")),
                    ToolParameter("reason", "string", "Specific reason this memory is durable", required = false),
                    ToolParameter("confidence", "number", "0.0-1.0 confidence", required = false),
                    ToolParameter("limit", "integer", "Max list/search results", required = false)
                )
            ),
            ToolDefinition(
                name = "skill",
                description = "View or manage reusable skills.",
                parameters = listOf(
                    ToolParameter("operation", "string", "view, create, update, patch, archive, or delete", enum = listOf("view", "create", "update", "patch", "archive", "delete")),
                    ToolParameter("name", "string", "Skill name", required = false),
                    ToolParameter("skill_id", "string", "Skill id/name for view", required = false),
                    ToolParameter("content", "string", "Skill content or patch", required = false)
                )
            ),
            ToolDefinition(
                name = "reminder",
                description = "Schedule a reminder.",
                parameters = listOf(
                    ToolParameter("operation", "string", "create", enum = listOf("create")),
                    ToolParameter("title", "string", "Reminder title"),
                    ToolParameter("message", "string", "Reminder message"),
                    ToolParameter("datetime", "string", "ISO local datetime")
                )
            ),
            ToolDefinition(
                name = "create_reminder",
                description = "Schedule a reminder via Android notification at a specific time. " +
                    "Use when user asks to be reminded about something.",
                parameters = listOf(
                    ToolParameter("title", "string", "Short reminder title", required = true),
                    ToolParameter("message", "string", "Full reminder message for the notification", required = true),
                    ToolParameter("datetime", "string", "ISO format YYYY-MM-DDTHH:MM (e.g. 2026-02-27T17:00)", required = true),
                    ToolParameter("repeat", "string", "Recurrence: 'once' (default), 'daily', or 'weekly'", required = false,
                        enum = listOf("once", "daily", "weekly")),
                    ToolParameter("conversation_id", "integer",
                        "Current conversation ID so reply appears in this chat when reminder fires.", required = false),
                    ToolParameter("session_mode", "string",
                        "'continue' (default) = append reply to existing conversation; 'new' = create fresh conversation each firing.",
                        required = false, enum = listOf("continue", "new"))
                )
            ),
            ToolDefinition(
                name = "update_memory",
                description = "Store an explicit durable user preference/profile fact or active-workspace fact. Classifies and dedupes before writing. Never store secrets; use session recall for history and create_reminder for reminders.",
                parameters = listOf(
                    ToolParameter("title", "string", "Short professional memory title/header, 2-7 words", required = false),
                    ToolParameter("content", "string", "Final durable memory summary. Do not copy raw user commands or include phrases like remember/tolong ingat/user asked", required = true),
                    ToolParameter("type", "string", "user_profile or workspace_fact", required = false,
                        enum = listOf("user_profile", "workspace_fact")),
                    ToolParameter("action", "string", "add, replace, or ignore", required = false,
                        enum = listOf("add", "replace", "ignore")),
                    ToolParameter("reason", "string", "Specific reason this memory is durable", required = false),
                    ToolParameter("confidence", "number", "0.0-1.0 confidence; low-confidence proposals are ignored", required = false)
                )
            ),
            ToolDefinition(
                name = "memory_manage",
                description = "List/search active saved memory or update one memory by stable id. For list/search, include a concise title explaining why memory is opened.",
                parameters = listOf(
                    ToolParameter("title", "string", "List/search header, 3-5 words explaining why memory is opened (e.g. Review saved preferences)", required = false),
                    ToolParameter("action", "string", "list, search, update", required = true,
                        enum = listOf("list", "search", "update")),
                    ToolParameter("id", "string", "Memory id for update", required = false),
                    ToolParameter("expected_version", "integer", "Version returned by list/search; required for update to prevent overwriting newer memory", required = false),
                    ToolParameter("query", "string", "Search query for list/search", required = false),
                    ToolParameter("type", "string", "user_profile or workspace_fact", required = false,
                        enum = listOf("user_profile", "workspace_fact")),
                    ToolParameter("content", "string", "Replacement content for update", required = false),
                    ToolParameter("limit", "integer", "Max results, default 20", required = false)
                )
            ),
            ToolDefinition(
                name = "skill_view",
                description = "Load full content and metadata for one relevant reusable skill. Use when the skill index says a skill may apply.",
                parameters = listOf(ToolParameter("name", "string", "Skill name", required = true))
            ),
            ToolDefinition(
                name = "skill_manage",
                description = "Create, update, patch, archive, or delete reusable procedural skills only when the user explicitly asks to manage or save a skill/workflow. Do not create trivial or duplicate skills; never store credentials.",
                parameters = listOf(
                    ToolParameter("action", "string", "create, update, patch, archive, delete", required = true,
                        enum = listOf("create", "update", "patch", "archive", "delete")),
                    ToolParameter("name", "string", "Skill name", required = true),
                    ToolParameter("content", "string", "SKILL.md content or patch text", required = false),
                    ToolParameter("description", "string", "Short skill description for create", required = false),
                    ToolParameter("reason", "string", "Why this skill is being created or changed", required = false),
                    ToolParameter("summary", "string", "What was added or changed", required = false),
                    ToolParameter("tags", "array", "Skill tags", required = false, items = "string")
                )
            ),
            ToolDefinition(
                name = "session_search",
                description = "Search previous sessions by query. Use this for recall instead of injecting old sessions in the prompt.",
                parameters = listOf(
                    ToolParameter("query", "string", "Search query", required = true),
                    ToolParameter("limit", "integer", "Max results, default 10", required = false)
                )
            )
        ) + browserUseToolset.getToolDefinitions()
        val advertisedToolNames = setOf(
            "workspace_search", "workspace_change", "read_file", "run_shell", "web_search", "browser",
            "memory", "skill", "reminder", "update_todo", "invoke_subagents", "delegate_agent"
        )
        return definitions.mapNotNull { definition ->
            val exposed = if (mode == AssistantMode.AGENT && definition.name == "memory") {
                definition.copy(
                    name = "agent_memory",
                    description = "Manage memory private to the active agent. Update requires the version returned by list/search."
                )
            } else definition
            exposed.takeIf {
                definition.name in advertisedToolNames && assistantModeAllowsCapability(exposed.name, mode, agentCapabilityProfile)
            }
        }
    }
}

private val CHAT_CAPABILITIES = setOf("web_search", "memory", "skill")
private val PROJECT_DISABLED_CAPABILITIES = setOf("browser", "delegate_agent", "reminder", "create_reminder", "update_todo")

internal fun assistantModeAllowsCapability(
    name: String,
    mode: AssistantMode,
    agentCapabilityProfile: com.amaya.intelligence.domain.models.AgentCapabilityProfile? = null
): Boolean = when (mode) {
    AssistantMode.CHAT -> name in CHAT_CAPABILITIES
    AssistantMode.PROJECT -> name !in PROJECT_DISABLED_CAPABILITIES
    AssistantMode.AGENT -> name == "agent_memory" || agentCapabilityProfile?.allows(name) ?: true
}

/**
 * Request for user confirmation.
 */
internal fun isAllowedInReadOnlyMode(handlerName: String): Boolean =
    handlerName in setOf("read_file", "list_files", "find_files", "web_search", "session_search", "skill_view")

data class ConfirmationRequest(
    val toolName: String,
    val reason: String,
    val details: String,
    val riskLevel: RiskLevel,
    val toolCallId: String? = null
)

/**
 * Tool definition for AI prompts.
 */
internal fun applyHostExecutionContext(
    handlerName: String,
    arguments: Map<String, Any?>,
    workspacePath: String?
): MutableMap<String, Any?> = arguments.toMutableMap().apply {
    remove("cwd")
    remove("working_dir")
    if (handlerName == "run_shell" && !workspacePath.isNullOrBlank()) put("working_dir", workspacePath)
}

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: List<ToolParameter>
)

/**
 * Parameter definition for a tool.
 */
data class ToolParameter(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true,
    // FIX 1.7: Removed `default` field — no AI provider serializes it in tool definitions.
    // It was misleading dead data. Re-add only if a provider explicitly supports it.
    val enum: List<String>? = null,
    val items: String? = null  // For array types: item type (e.g., "string")
)

// FIX 4.3: Shared extension to convert ToolDefinition → AiToolDefinition.
// Eliminates identical mapping code duplicated in AiRepository.buildToolDefinitions()
// and SubagentRunner.runInternal(). Single source of truth for this conversion.
//
// FIX: OpenAI rejects tool names that don't match ^[a-zA-Z0-9_-]+$.
// Bridge tool names use dots (e.g. "screen.capture", "mouse.click") which are invalid.
// sanitizeBridgeToolName() replaces '.' with '__' before sending to the API.
// desanitizeBridgeToolName() reverses this when routing the model's response back.
fun sanitizeBridgeToolName(name: String): String = name.replace('.', '_').replace('-', '_')
fun desanitizeBridgeToolName(name: String): String = name // wire name is already the canonical form

// Build a reverse lookup: sanitized → original wire name.
// Used by McpToolExecutor to map the model's response back to the bridge wire name.
private val bridgeToolSanitizedToWire: Map<String, String> by lazy {
    com.amaya.intelligence.impl.bridge.windows.tools.WindowsBridgeToolDefinitions.all
        .associate { sanitizeBridgeToolName(it.name) to it.name }
}

/** Reverse a sanitized bridge tool name back to its wire name, or return [sanitized] unchanged. */
fun resolveBridgeToolWireName(sanitized: String): String =
    bridgeToolSanitizedToWire[sanitized] ?: sanitized

fun ToolDefinition.toAiToolDefinition(truncateDesc: Boolean = false): com.amaya.intelligence.data.remote.api.AiToolDefinition {
    fun String.maybeTruncate() = if (truncateDesc && length > 1023) take(1023) + "…" else this
    // Sanitize the name: OpenAI only accepts ^[a-zA-Z0-9_-]+$ — dots are not allowed.
    // Bridge tool names (e.g. "screen.capture") are sanitized here and reversed in McpToolExecutor.
    val safeName = sanitizeBridgeToolName(name)
    return com.amaya.intelligence.data.remote.api.AiToolDefinition(
        name = safeName,
        description = description.maybeTruncate(),
        parameters = com.amaya.intelligence.data.remote.api.AiToolParameters(
            type = "object",
            properties = parameters.associate { param ->
                param.name to com.amaya.intelligence.data.remote.api.AiToolProperty(
                    type = param.type,
                    description = param.description.maybeTruncate(),
                    enum = param.enum,
                    items = param.items?.let { com.amaya.intelligence.data.remote.api.AiToolPropertyItems(it) }
                )
            },
            required = parameters.filter { it.required }.map { it.name },
            additionalProperties = false
        ),
        strict = false
    )
}
