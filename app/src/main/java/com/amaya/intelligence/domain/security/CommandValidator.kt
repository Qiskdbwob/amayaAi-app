package com.amaya.intelligence.domain.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Security guardrails for command and path validation.
 *
 * This is the "Shield" module of the AI Coding Agent. Every tool call
 * from the AI goes through validation before execution.
 *
 * SECURITY PRINCIPLES:
 * 1. Whitelist over Blacklist: Only allow known-safe commands
 * 2. Defense in Depth: Multiple layers of validation
 * 3. Fail Secure: When in doubt, block the operation
 * 4. User in the Loop: Dangerous operations require explicit confirmation
 */
@Singleton
class CommandValidator @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        // ====================================================================
        // COMMAND WHITELIST
        // ====================================================================

        /**
         * Commands that are always allowed.
         * These are safe, read-only or low-impact commands.
         */
        private val ALWAYS_ALLOWED = setOf(
            "echo", "printf", "cat", "head", "tail",
            "grep", "cut", "sort", "uniq",
            "wc", "tr", "diff",
            "ls", "which", "whereis", "file",
            "pwd", "basename", "dirname", "realpath",
            "date", "cal", "uptime",
            // Build and package tools can execute project-controlled code; require confirmation below.
            // Read-only Android inspection remains allowed.
            "aapt", "apksigner",
            // FIX 1.10: Removed "task_status" and "task_stop" — no tool/service/script defines them.
            // Adding unimplemented commands to the whitelist expands attack surface for no benefit.
        )

        /**
         * Commands allowed but may require confirmation for certain args.
         */
        private val CONDITIONALLY_ALLOWED = setOf(
            "git", "logcat", "am", "pm", "adb",
            "gradle", "gradlew", "npm", "node", "npx",
            "awk", "sed", "find", "patch",
            "mkdir", "touch", "cp", "mv",
            "chmod", "chown",
            "curl", "wget",
            "tar", "zip", "unzip", "gzip", "gunzip"
            // Note: adb, gradle, gradlew moved to ALWAYS_ALLOWED (FIX #16)
        )

        // ====================================================================
        // COMMAND BLACKLIST
        // ====================================================================

        /**
         * Commands that are NEVER allowed.
         * These can cause irreversible system damage.
         */
        private val ALWAYS_BLOCKED = setOf(
            "rm", "rmdir",              // Use our safe delete instead
            "dd",                       // Can destroy disk
            "mkfs", "format",           // Filesystem destruction
            "reboot", "shutdown", "poweroff",
            "su", "sudo",               // Blocked here, handled separately
            "mount", "umount",          // Filesystem operations
            "insmod", "rmmod", "modprobe", // Kernel modules
            "iptables", "ip6tables",    // Network manipulation
            "init", "systemctl",        // System services
            "setenforce",               // SELinux
            "factory_reset"             // Factory reset
        )

        /**
         * Dangerous argument patterns that block even allowed commands.
         */
        private val DANGEROUS_PATTERNS = listOf(
            Regex("""-rf\s+/"""),                    // rm -rf /
            Regex("""--no-preserve-root"""),         // Bypass root protection
            Regex(""">\s*/dev/(sd|hd|nvme)"""),     // Write to disk device
            Regex("""\|\s*sh\b"""),                  // Pipe to shell
            Regex("""\|\s*bash\b"""),                // Pipe to bash
            Regex("""`[^`]+`"""),                    // Command substitution
            Regex("""\$\([^)]+\)"""),                // Command substitution
            Regex(""";\s*rm\b"""),                   // Chained rm
            Regex("""&&\s*rm\b"""),                  // Chained rm
            Regex("""\|\|\s*rm\b"""),                // Chained rm
            Regex("""chmod\s+777"""),                // Overly permissive
            Regex("""chmod\s+\+s"""),                // Setuid bit
            Regex(""">\s*/etc/"""),                  // Write to /etc
            Regex(""">\s*/system/""")                // Write to /system
        )

        // ====================================================================
        // PROTECTED PATHS
        // ====================================================================

        /**
         * System paths that should never be modified.
         */
        private val PROTECTED_PATHS = listOf(
            ProtectedPath("/system", "Android system partition", allowRead = true),
            ProtectedPath("/vendor", "Vendor partition", allowRead = true),
            ProtectedPath("/proc", "Kernel process info", allowRead = true),
            ProtectedPath("/sys", "Kernel sysfs", allowRead = true),
            ProtectedPath("/dev", "Device files"),
            ProtectedPath("/root", "Root home directory"),
            ProtectedPath("/sbin", "System binaries"),
            ProtectedPath("/init", "Init scripts"),
            ProtectedPath("/data/data", "Other apps' data"),
            ProtectedPath("/data/app", "Installed APKs"),
            ProtectedPath("/data/system", "System settings")
        )
    }

    // Current app's data directory (safe to access)
    private val appDataDir: String by lazy {
        context.filesDir.parentFile?.absolutePath ?: context.filesDir.absolutePath
    }

    // ========================================================================
    // PUBLIC API
    // ========================================================================

    /**
     * Validate a shell command before execution.
     */
    fun validateCommand(command: String): ValidationResult {
        val argv = when (val parsed = parseCommand(command)) {
            is CommandParseResult.Success -> parsed.argv
            is CommandParseResult.Error -> return ValidationResult.Denied(parsed.reason, command)
        }
        if (argv.isEmpty()) return ValidationResult.Denied("Empty command", command)
        val executable = argv.first()
        if (executable.contains('/') && !java.io.File(executable).isAbsolute && !executable.startsWith("./")) {
            return ValidationResult.Denied("Executable path must be absolute, relative to the workspace, or resolved from PATH", command)
        }
        val baseCommand = executable.substringAfterLast('/')
        if (baseCommand in ALWAYS_BLOCKED || baseCommand in setOf("sh", "bash", "dash", "zsh", "ash", "su", "sudo")) {
            return ValidationResult.Denied("Command '$baseCommand' is blocked for safety", command)
        }
        DANGEROUS_PATTERNS.firstOrNull { it.containsMatchIn(command) }?.let { pattern ->
            return ValidationResult.Denied("Command contains dangerous pattern: ${pattern.pattern}", command)
        }
        return when (baseCommand) {
            in ALWAYS_ALLOWED -> ValidationResult.Allowed
            in CONDITIONALLY_ALLOWED -> validateConditionalCommand(baseCommand, argv, command)
            else -> ValidationResult.RequiresConfirmation(
                "Unknown command '$baseCommand' requires confirmation",
                command,
                RiskLevel.MEDIUM
            )
        }
    }

    /** Parse one executable plus argv. Compound shell grammar is deliberately unsupported. */
    fun parseCommandArguments(command: String): List<String>? = parseSafeCommandArguments(command)

    /**
     * Validate a file path for read or write access.
     */
    fun validatePath(path: String, isWrite: Boolean): ValidationResult {
        if (path.isBlank()) return ValidationResult.Denied("Path is required", path)
        if (!java.io.File(path).isAbsolute) {
            return ValidationResult.Denied("Path must be host-resolved inside the active workspace.", path)
        }
        if (containsPathTraversal(path)) return ValidationResult.Denied("Path traversal detected", path)
        val normalizedPath = normalizePath(path)

        // FIX 3: Resolve symlinks to prevent symlink-based path traversal bypass.
        // e.g. /sdcard/mylink → /data/data/... would bypass normalized path checks.
        val canonicalPath = try {
            java.io.File(normalizedPath).canonicalPath
        } catch (_: Exception) { normalizedPath }
        if (canonicalPath != normalizedPath) {
            // Re-check canonical path against protected prefixes
            for (protected in PROTECTED_PATHS) {
                if (isWithinPath(canonicalPath, protected.path) &&
                    !isWithinPath(canonicalPath, appDataDir)) {
                    return ValidationResult.Denied(
                        "Symlink traversal into protected path detected: $path → $canonicalPath",
                        path
                    )
                }
            }
        }

        // Check if it's our app's directory (always allowed)
        if (isWithinPath(normalizedPath, appDataDir)) {
            return ValidationResult.Allowed
        }

        // Check protected paths
        for (protected in PROTECTED_PATHS) {
            if (isWithinPath(normalizedPath, protected.path)) {
                // Special case: reading from own data directory
                if (isWithinPath(normalizedPath, appDataDir)) {
                    return ValidationResult.Allowed
                }

                if (isWrite && !protected.allowWrite) {
                    return ValidationResult.Denied(
                        "Cannot write to protected path: ${protected.reason}",
                        path
                    )
                }

                if (!isWrite && !protected.allowRead) {
                    return ValidationResult.Denied(
                        "Cannot read from protected path: ${protected.reason}",
                        path
                    )
                }

                // Reading from protected but readable paths requires confirmation
                if (!isWrite && protected.allowRead) {
                    return ValidationResult.RequiresConfirmation(
                        "Accessing system path: ${protected.reason}",
                        path,
                        RiskLevel.LOW
                    )
                }
            }
        }

        return ValidationResult.Allowed
    }

    /**
     * Check if a tool operation is allowed.
     */
    fun validateToolCall(
        toolName: String,
        arguments: Map<String, Any?>
    ): ValidationResult {
        missingToolTarget(toolName, arguments)?.let { return ValidationResult.Denied(it, "") }
        return when (toolName) {
            "run_shell" -> {
                val command = arguments["command"] as? String ?: ""
                val commandResult = validateCommand(command)
                val workingDir = arguments["working_dir"] as? String
                val pathResult = if (workingDir.isNullOrBlank()) ValidationResult.Allowed
                    else validatePath(workingDir, isWrite = false)
                combineValidation(commandResult, pathResult, validateSafeReadTargets(command, workingDir))
            }

            "read_file" -> {
                val paths = (arguments["paths"] as? List<*>)
                    ?.mapNotNull { (it as? String)?.takeIf(String::isNotBlank) }
                if (paths != null) combinePathValidation(paths, isWrite = false)
                else validatePath(arguments["path"] as? String ?: "", isWrite = false)
            }

            "write_file" -> {
                val path = arguments["path"] as? String ?: ""
                validatePath(path, isWrite = true)
            }

            "delete_file" -> {
                val path = arguments["path"] as? String ?: ""
                val result = validatePath(path, isWrite = true)

                // Deletion always requires confirmation
                if (result is ValidationResult.Allowed) {
                    ValidationResult.RequiresConfirmation(
                        "Confirm file deletion",
                        path,
                        RiskLevel.MEDIUM
                    )
                } else result
            }

            "list_files" -> {
                val path = arguments["path"] as? String ?: ""
                validatePath(path, isWrite = false)
            }

            "create_directory" -> {
                val path = arguments["path"] as? String ?: ""
                validatePath(path, isWrite = true)
            }

            // FIX 2.9: Added missing path validation for edit_file, transfer_file, find_files
            "edit_file" -> {
                val path = arguments["path"] as? String ?: ""
                validatePath(path, isWrite = true)
            }

            "transfer_file" -> {
                // Validate both source (read) and destination (write)
                val source = arguments["source"] as? String ?: ""
                val destination = arguments["destination"] as? String ?: ""
                val srcResult = validatePath(source, isWrite = false)
                if (srcResult !is ValidationResult.Allowed) return srcResult
                validatePath(destination, isWrite = true)
            }

            "find_files" -> {
                val path = arguments["path"] as? String ?: ""
                validatePath(path, isWrite = false)
            }

            else -> ValidationResult.Allowed
        }
    }

    // ========================================================================
    // PRIVATE HELPERS
    // ========================================================================

    private fun validateSafeReadTargets(command: String, workingDir: String?): ValidationResult {
        val argv = parseSafeCommandArguments(command) ?: return ValidationResult.Denied("Unsafe shell syntax", command)
        val executable = argv.firstOrNull()?.substringAfterLast('/') ?: return ValidationResult.Denied("Empty command", command)
        val filesystemReads = setOf("cat", "head", "tail", "grep", "cut", "sort", "uniq", "wc", "tr", "diff", "ls", "which", "whereis", "file", "basename", "dirname", "realpath")
        if (executable !in filesystemReads) return ValidationResult.Allowed
        if (workingDir.isNullOrBlank()) return ValidationResult.RequiresConfirmation(
            "Shell file reads require an active workspace", command, RiskLevel.LOW
        )
        val targets = argv.drop(1).filter { it.isNotBlank() && !it.startsWith("-") }
        if (targets.isEmpty()) return ValidationResult.Allowed
        val root = runCatching { java.io.File(workingDir).canonicalFile }.getOrNull() ?: return ValidationResult.RequiresConfirmation(
            "Cannot verify shell working directory", command, RiskLevel.LOW
        )
        targets.forEach { raw ->
            val target = runCatching {
                val file = java.io.File(raw)
                (if (file.isAbsolute) file else java.io.File(root, raw)).canonicalFile
            }.getOrNull() ?: return ValidationResult.RequiresConfirmation("Cannot verify shell read target", command, RiskLevel.LOW)
            if (!isWithinPath(target.path, root.path)) {
                return ValidationResult.RequiresConfirmation(
                    "Safe shell reads are limited to the active workspace", command, RiskLevel.LOW
                )
            }
        }
        return ValidationResult.Allowed
    }

    private fun combinePathValidation(paths: List<String>, isWrite: Boolean): ValidationResult {
        var confirmation: ValidationResult.RequiresConfirmation? = null
        for (path in paths) {
            when (val result = validatePath(path, isWrite)) {
                is ValidationResult.Denied -> return result
                is ValidationResult.RequiresConfirmation -> if (confirmation == null) confirmation = result
                is ValidationResult.Allowed -> Unit
            }
        }
        return confirmation ?: ValidationResult.Allowed
    }

    private fun validateConditionalCommand(
        baseCommand: String,
        argv: List<String>,
        fullCommand: String
    ): ValidationResult {
        return when (baseCommand) {
            "git" -> ValidationResult.RequiresConfirmation(
                "Git commands can execute configured aliases or modify the repository",
                fullCommand,
                if ("reset" in argv || "clean" in argv) RiskLevel.HIGH else RiskLevel.MEDIUM
            )

            "mv", "cp" -> {
                val destination = argv.lastOrNull() ?: ""
                combineValidation(
                    validatePath(destination, isWrite = true),
                    ValidationResult.RequiresConfirmation(
                        "File copy or move can overwrite data",
                        fullCommand,
                        RiskLevel.MEDIUM
                    )
                )
            }

            "awk", "sed", "find", "patch" -> ValidationResult.RequiresConfirmation(
                "Command can write files or execute external programs",
                fullCommand,
                RiskLevel.MEDIUM
            )

            "gradle", "gradlew", "npm", "node", "npx", "adb", "am", "pm" -> ValidationResult.RequiresConfirmation(
                "Command can execute code or modify device/project state",
                fullCommand,
                RiskLevel.MEDIUM
            )

            "curl", "wget" -> ValidationResult.RequiresConfirmation(
                "Network request to external URL",
                fullCommand,
                RiskLevel.MEDIUM
            )

            "chmod", "chown" -> ValidationResult.RequiresConfirmation(
                "Changing file permissions",
                fullCommand,
                RiskLevel.HIGH
            )

            else -> ValidationResult.RequiresConfirmation(
                "Run shell command '$baseCommand'",
                fullCommand,
                RiskLevel.MEDIUM
            )
        }
    }

    private fun combineValidation(vararg results: ValidationResult): ValidationResult {
        results.filterIsInstance<ValidationResult.Denied>().firstOrNull()?.let { return it }
        return results.filterIsInstance<ValidationResult.RequiresConfirmation>()
            .maxByOrNull { it.riskLevel.ordinal } ?: ValidationResult.Allowed
    }

    private fun isWithinPath(candidate: String, root: String): Boolean {
        val normalizedRoot = root.trimEnd('/', '\\')
        return candidate == normalizedRoot || candidate.startsWith("$normalizedRoot/") || candidate.startsWith("$normalizedRoot\\")
    }

    private fun normalizePath(path: String): String {
        // Resolve all . and .. segments, remove double slashes
        val parts = path.replace(Regex("""//+"""), "/").split("/")
        val resolved = ArrayDeque<String>()
        for (part in parts) {
            when (part) {
                "", "." -> { /* skip */ }
                ".." -> if (resolved.isNotEmpty()) resolved.removeLast()
                else -> resolved.addLast(part)
            }
        }
        val normalized = "/" + resolved.joinToString("/")
        return normalized
    }

    private fun containsPathTraversal(path: String): Boolean {
        // After normalization, path should never contain ".." anymore.
        // But also guard against encoded variants.
        return path.contains("..") ||
            path.contains("%2e%2e", ignoreCase = true) ||
            path.contains("%252e", ignoreCase = true)
    }
}
