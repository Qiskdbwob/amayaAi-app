package com.amaya.intelligence.domain.security

internal fun missingToolTarget(toolName: String, arguments: Map<String, Any?>): String? {
    val pathTools = setOf(
        "read_file", "write_file", "delete_file", "list_files", "create_directory",
        "edit_file", "find_files"
    )
    if (toolName !in pathTools) return null
    val hasPath = (arguments["path"] as? String)?.isNotBlank() == true ||
        (toolName == "read_file" && (arguments["paths"] as? List<*>)?.any {
            (it as? String)?.isNotBlank() == true
        } == true)
    return if (hasPath) null else
        "No workspace is selected. Select a workspace before using file tools."
}
