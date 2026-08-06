package com.amaya.intelligence.data.local.files

import java.io.File

internal fun canonicalWorkspacePath(path: String, preserveOnFailure: Boolean = true): String =
    runCatching { File(path).canonicalPath }.getOrElse { if (preserveOnFailure) path else throw it }
