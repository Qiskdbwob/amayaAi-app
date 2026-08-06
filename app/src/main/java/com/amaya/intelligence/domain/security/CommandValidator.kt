package com.amaya.intelligence.domain.security

import android.content.Context
import com.amaya.intelligence.data.repository.TerminalSettings
import com.amaya.intelligence.data.repository.commandMatchesWildcard
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
        /** Commands that remain blocked even when a wildcard marks them trusted. */
        private val HARD_BLOCK_PATTERNS = listOf(
            Regex("(?i)(^|[;&|]\\s*)rm\\s+-[^\\n]*r[^\\n]*f[^\\n]*\\s+/(?:\\s|$|\\*)"),
            Regex("(?i)\\b--no-preserve-root\\b"),
            Regex("(?i)(^|[;&|]\\s*)(mkfs(?:\\.[a-z0-9]+)?|factory_reset)\\b"),
            Regex("(?i)>\\s*/(?:dev/(?:sd|hd|nvme)|system/|vendor/|proc/|sys/)"),
            Regex("(?i)\\bdd\\b[^\\n]*(?:of=/dev/|if=/dev/(?:zero|random))"),
            Regex("(?i)\\|\\s*(?:sh|bash|dash|zsh|ash)\\b"),
            Regex("(?i)\\bchmod\\s+(?:777|\\+s)\\b")
        )

        // ====================================================================
        private val DANGEROUS_PATTERNS = listOf(
            Regex("(?i)\\brm\\s+-[^\n]*r"),
            Regex("(?i)\\bgit\\s+push\\b[^\n]*--force"),
            Regex("(?i)\\bgit\\s+reset\\s+--hard\\b"),
            Regex("(?i)\\bgit\\s+clean\\s+-[^\n]*f"),
            Regex("(?i)\\bsudo\\b"),
            Regex("(?i)\\bsu\\b"),
            Regex("(?i)\\breboot\\b|\\bshutdown\\b"),
            Regex("(?i)\\bchmod\\s+-R\\b"),
            Regex("(?i)\\bchown\\s+-R\\b"),
            Regex("(?i)\\bcurl\\b[^\n]*\\|\\s*(?:sh|bash)"),
            Regex("(?i)\\bwget\\b[^\n]*\\|\\s*(?:sh|bash)"),
            Regex("(?i)\\bkill\\s+-9\\b"),
            Regex("(?i)\\btruncate\\b"),
            Regex("(?i)\\bmkfifo\\b")
        )
        private val ABSOLUTE_PATH_TOKEN = Regex("(?<![\\w@%$.-])(/[^\\s\"'`;|&<>()]+)")
        private val SAFE_SYSTEM_READ_PREFIXES = listOf(
            "/proc", "/sys", "/system", "/vendor", "/dev/null", "/dev/zero", "/dev/urandom", "/dev/random"
        )


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
    fun validateCommand(command: String, settings: TerminalSettings = TerminalSettings()): ValidationResult {
        val normalized = command.trim()
        if (normalized.isEmpty()) return ValidationResult.Denied("Empty command", command)
        HARD_BLOCK_PATTERNS.firstOrNull { it.containsMatchIn(normalized) }?.let {
            return ValidationResult.Denied("Command is blocked by the host safety boundary", command)
        }
        if (settings.declinedCommands.any { commandMatchesWildcard(normalized, it) }) {
            return ValidationResult.Denied("Command matches a declined terminal pattern", command)
        }
        if (settings.trustedCommands.any { commandMatchesWildcard(normalized, it) }) {
            DANGEROUS_PATTERNS.firstOrNull { it.containsMatchIn(normalized) }?.let {
                return ValidationResult.RequiresConfirmation(
                    "Trusted command still requires review because it matches a dangerous pattern",
                    command,
                    RiskLevel.HIGH
                )
            }
            return ValidationResult.Allowed
        }
        DANGEROUS_PATTERNS.firstOrNull { it.containsMatchIn(normalized) }?.let {
            return ValidationResult.RequiresConfirmation(
                "Dangerous shell command requires confirmation",
                command,
                RiskLevel.HIGH
            )
        }
        return ValidationResult.RequiresConfirmation(
            "Shell command is not in Trusted Commands",
            command,
            RiskLevel.MEDIUM
        )
    }

    fun validateShellCommandPaths(
        command: String,
        workspacePath: String?
    ): ValidationResult {
        val root = workspacePath?.trim()?.takeIf { it.isNotBlank() } ?: return ValidationResult.Allowed
        val normalizedRoot = normalizePath(root)
        val tokens = ABSOLUTE_PATH_TOKEN.findAll(command).map { it.groupValues[1] }.toList()
        if (tokens.isEmpty()) return ValidationResult.Allowed
        for (raw in tokens) {
            val cleaned = raw.trimEnd('"', ''', '`', ',', ';', ')', ']')
            if (cleaned.isBlank() || cleaned == "/") continue
            val normalized = normalizePath(cleaned)
            if (isWithinPath(normalized, normalizedRoot)) continue
            if (SAFE_SYSTEM_READ_PREFIXES.any { isWithinPath(normalized, it) || normalized == it }) continue
            if (isWithinPath(normalized, appDataDir)) continue
            return ValidationResult.Denied(
                "Shell path escapes the active workspace: $cleaned",
                cleaned
            )
        }
        return ValidationResult.Allowed
    }

    fun isDangerousTool(toolName: String, arguments: Map<String, Any?> = emptyMap()): Boolean {
        return when (toolName) {
            "delete_file" -> true
            "skill_manage", "skill" -> {
                val op = (arguments["operation"] as? String)?.lowercase().orEmpty()
                op in setOf("delete", "remove", "uninstall", "disable")
            }
            "run_shell" -> {
                val command = (arguments["command"] as? String).orEmpty()
                DANGEROUS_PATTERNS.any { it.containsMatchIn(command) }
            }
            else -> toolName.startsWith("mcp__")
        }
    }

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
        arguments: Map<String, Any?>,
        terminalSettings: TerminalSettings = TerminalSettings()
    ): ValidationResult {
        missingToolTarget(toolName, arguments)?.let { return ValidationResult.Denied(it, "") }
        return when (toolName) {
            "run_shell" -> {
                val command = arguments["command"] as? String ?: ""
                val commandResult = validateCommand(command, terminalSettings)
                val workingDir = arguments["working_dir"] as? String
                val pathResult = if (workingDir.isNullOrBlank()) ValidationResult.Allowed
                    else validatePath(workingDir, isWrite = false)
                val workspaceFence = validateShellCommandPaths(command, workingDir)
                combineValidation(commandResult, pathResult, workspaceFence)
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
