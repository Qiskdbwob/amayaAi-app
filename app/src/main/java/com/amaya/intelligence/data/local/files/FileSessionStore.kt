package com.amaya.intelligence.data.local.files

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** File-backed store for local session recall data. */
@Singleton
class FileSessionStore @Inject constructor(
    @ApplicationContext context: Context
) {
    val rootDir: File = File(context.filesDir, "sessions").also { it.mkdirs() }
    val sessionsFile: File = File(rootDir, "sessions.jsonl").also { file ->
        val legacyFile = File(rootDir, "sessions.db")
        if (!file.exists() && legacyFile.exists()) legacyFile.copyTo(file, overwrite = false)
    }
    val summariesFile: File = File(rootDir, "summaries.jsonl")
}
