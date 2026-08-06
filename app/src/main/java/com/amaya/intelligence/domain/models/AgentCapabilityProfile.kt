package com.amaya.intelligence.domain.models

data class AgentCapabilityProfile(
    val workspace: Boolean = true,
    val terminal: Boolean = true,
    val browser: Boolean = true,
    val subagents: Boolean = true,
    val webSearch: Boolean = true,
    val skills: Boolean = true,
    val reminders: Boolean = true,
    val todo: Boolean = true
) {
    fun allows(toolName: String): Boolean = when (toolName) {
        "workspace_search", "workspace_change", "list_files", "read_file", "write_file",
        "edit_file", "create_directory", "delete_file", "find_files" -> workspace
        "run_shell" -> terminal
        "browser" -> browser
        "invoke_subagents", "delegate_agent" -> subagents
        "web_search" -> webSearch
        "skill", "skill_view", "skill_manage" -> skills
        "reminder", "create_reminder" -> reminders
        "update_todo" -> todo
        "memory", "update_memory", "memory_manage", "agent_memory" -> false
        else -> true
    }

    fun encode(): String = listOf(
        "workspace=$workspace",
        "terminal=$terminal",
        "browser=$browser",
        "subagents=$subagents",
        "web_search=$webSearch",
        "skills=$skills",
        "reminders=$reminders",
        "todo=$todo"
    ).joinToString(";")

    companion object {
        fun decode(value: String?): AgentCapabilityProfile {
            val values = value.orEmpty().split(';').mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                pair.takeIf { it.size == 2 }?.let { it[0] to it[1].toBooleanStrictOrNull() }
            }.toMap()
            return AgentCapabilityProfile(
                workspace = values["workspace"] ?: true,
                terminal = values["terminal"] ?: true,
                browser = values["browser"] ?: true,
                subagents = values["subagents"] ?: true,
                webSearch = values["web_search"] ?: true,
                skills = values["skills"] ?: true,
                reminders = values["reminders"] ?: true,
                todo = values["todo"] ?: true
            )
        }
    }
}
