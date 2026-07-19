package com.amaya.intelligence.tools

/** Model-facing capability operations mapped to existing handlers during migration. */
object CapabilityToolMapper {
    data class Call(val handlerName: String, val arguments: Map<String, Any?>)

    fun map(name: String, arguments: Map<String, Any?>): Call? {
        val operation = arguments["operation"] as? String ?: return null
        val mapped = arguments.toMutableMap().apply { remove("operation") }
        return when (name) {
            "workspace_search" -> when (operation) {
                "list" -> Call("list_files", mapped)
                "find_name" -> Call("find_files", mapped.apply {
                    put("pattern", remove("query") ?: remove("pattern"))
                })
                "find_text" -> Call("find_files", mapped.apply {
                    put("content", remove("query") ?: remove("content"))
                })
                else -> null
            }
            "workspace_change" -> when (operation) {
                "write", "append" -> Call("write_file", mapped.apply {
                    if (operation == "append") put("append", true)
                })
                "replace" -> Call("edit_file", mapped.apply {
                    put("old_content", remove("old_text") ?: remove("old_content"))
                    put("new_content", remove("new_text") ?: remove("new_content"))
                })
                "patch" -> Call("edit_file", mapped)
                "mkdir" -> Call("create_directory", mapped)
                "delete" -> Call("delete_file", mapped)
                else -> null
            }
            "memory" -> when (operation) {
                "save" -> Call("update_memory", mapped)
                "list", "search", "update" -> Call("memory_manage", mapped.apply {
                    put("action", operation)
                })
                "recall_sessions" -> Call("session_search", mapped)
                else -> null
            }
            "skill" -> when (operation) {
                "view" -> Call("skill_view", mapped.apply {
                    put("name", remove("skill_id") ?: remove("name"))
                })
                "create", "update", "patch", "archive", "delete" -> Call("skill_manage", mapped.apply {
                    put("action", operation)
                })
                else -> null
            }
            "reminder" -> if (operation == "create") Call("create_reminder", mapped) else null
            else -> null
        }
    }

    fun displayName(name: String, arguments: Map<String, Any?>): String = map(name, arguments)?.handlerName ?: name
}
