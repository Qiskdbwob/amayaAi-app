package com.amaya.intelligence.data.local.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FileWorkspaceMemoryStore @Inject constructor(
    @ApplicationContext context: Context
) {
    val rootDir: File = File(context.filesDir, "memory/workspaces").also { it.mkdirs() }

    fun resolve(path: String, create: Boolean = true): WorkspaceMemoryLocation? {
        val root = File(path).canonicalPath
        list().firstOrNull { root == it.root || root in it.previousRoots }?.let { return it }
        if (!create) return null
        val id = UUID.randomUUID().toString()
        val directory = File(rootDir, id).also { it.mkdirs() }
        return WorkspaceMemoryLocation(id, root, directory).also(::writeMetadata)
    }

    fun list(): List<WorkspaceMemoryLocation> = rootDir.listFiles().orEmpty()
        .filter(File::isDirectory)
        .mapNotNull(::readLocation)

    fun legacyUnmapped(): WorkspaceMemoryLocation {
        list().firstOrNull { it.id == LEGACY_WORKSPACE_ID }?.let { return it }
        val directory = File(rootDir, LEGACY_WORKSPACE_ID).also { it.mkdirs() }
        return WorkspaceMemoryLocation(
            id = LEGACY_WORKSPACE_ID,
            root = File(rootDir.parentFile, "legacy-project-unmapped").canonicalPath,
            directory = directory
        ).also(::writeMetadata)
    }

    fun remap(id: String, newRoot: String): WorkspaceMemoryLocation {
        require(UUID_REGEX.matches(id)) { "Invalid workspace id." }
        val canonicalRoot = File(newRoot).canonicalPath
        require(File(canonicalRoot).isDirectory) { "New workspace root is not a directory: $newRoot" }
        val current = readLocation(File(rootDir, id)) ?: throw IllegalArgumentException("Workspace memory not found: $id")
        require(list().none { it.id != id && (canonicalRoot == it.root || canonicalRoot in it.previousRoots) }) {
            "The new root is already mapped to another workspace memory."
        }
        return current.copy(
            root = canonicalRoot,
            previousRoots = (current.previousRoots + current.root).filter { it != canonicalRoot }.distinct()
        ).also(::writeMetadata)
    }

    private fun readLocation(directory: File): WorkspaceMemoryLocation? {
        val file = File(directory, "workspace.json").takeIf(File::isFile) ?: return null
        return runCatching {
            val json = JSONObject(file.readText())
            val root = json.optString("root").takeIf(String::isNotBlank)?.let { File(it).canonicalPath } ?: return null
            val previousRoots = json.optJSONArray("previousRoots")?.let { array ->
                List(array.length()) { array.optString(it) }
                    .filter(String::isNotBlank)
                    .map { File(it).canonicalPath }
                    .distinct()
            }.orEmpty()
            WorkspaceMemoryLocation(directory.name, root, directory, previousRoots)
        }.getOrNull()
    }

    private fun writeMetadata(location: WorkspaceMemoryLocation) {
        val file = File(location.directory, "workspace.json")
        val tmp = File(location.directory, "workspace.json.tmp")
        tmp.writeText(JSONObject()
            .put("id", location.id)
            .put("root", location.root)
            .put("previousRoots", JSONArray(location.previousRoots))
            .toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    companion object {
        private val UUID_REGEX = Regex("[0-9a-fA-F-]{36}")
        private val LEGACY_WORKSPACE_ID = UUID.nameUUIDFromBytes("legacy-project-memory".toByteArray()).toString()
    }
}

data class WorkspaceMemoryLocation(
    val id: String,
    val root: String,
    val directory: File,
    val previousRoots: List<String> = emptyList()
) {
    val recordsFile: File get() = File(directory, "records.jsonl")
}
