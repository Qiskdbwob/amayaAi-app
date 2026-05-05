package com.amaya.intelligence.data.local.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** File-backed store for local reusable skill documents. */
@Singleton
class FileSkillStore @Inject constructor(
    @ApplicationContext context: Context
) {
    val rootDir: File = File(context.filesDir, "skills").also { it.mkdirs() }

    fun skillDir(name: String): File = File(rootDir, sanitizeName(name)).also { it.mkdirs() }
    fun skillContentFile(name: String): File = File(skillDir(name), "SKILL.md")
    fun metadataFile(name: String): File = File(skillDir(name), "metadata.json")

    fun sanitizeName(raw: String): String = raw
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9_-]+"), "-")
        .trim('-')
        .ifBlank { "untitled-skill" }
}
