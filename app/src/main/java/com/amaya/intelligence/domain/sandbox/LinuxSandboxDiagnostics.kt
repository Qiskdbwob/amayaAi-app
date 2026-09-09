package com.amaya.intelligence.domain.sandbox

import com.amaya.intelligence.util.debugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * Structured runtime diagnostics for the embedded Alpine + PRoot sandbox.
 *
 * This intentionally runs the same execution path used by package installation,
 * but breaks startup into small probes so an exit code such as 255 can be traced
 * to PRoot startup, guest /bin/sh, the Alpine apk tool, or network access.
 */
object LinuxSandboxDiagnostics {
    private const val TAG = "LinuxSandboxDiagnostics"

    data class ProbeResult(
        val name: String,
        val command: String,
        val exitCode: Int?,
        val output: String,
        val timedOut: Boolean,
        val launchError: String? = null
    ) {
        val success: Boolean get() = launchError == null && !timedOut && exitCode == 0

        fun summary(): String = buildString {
            append(name).append(": ")
            when {
                launchError != null -> append("LAUNCH ERROR: ").append(launchError)
                timedOut -> append("TIMEOUT")
                success -> append("OK")
                else -> append("EXIT ").append(exitCode)
            }
            if (output.isNotBlank()) {
                append("\n").append(output.takeLast(4000))
            }
        }
    }

    data class Report(
        val rootfsReady: Boolean,
        val prootPath: String?,
        val loaderPath: String?,
        val probes: List<ProbeResult>
    ) {
        val success: Boolean get() = rootfsReady && probes.all { it.success }

        fun prettyPrint(): String = buildString {
            appendLine("Alpine sandbox diagnostics")
            appendLine("rootfsReady=$rootfsReady")
            appendLine("proot=${prootPath ?: "missing"}")
            appendLine("loader=${loaderPath ?: "missing/fallback"}")
            probes.forEach {
                appendLine()
                appendLine(it.summary())
            }
        }
    }

    suspend fun run(manager: LinuxSandboxManager): Report = withContext(Dispatchers.IO) {
        val rootfsReady = manager.isReady()
        val prootPath = manager.buildExecution("true", null).first.firstOrNull()
        val loaderPath = manager.getOrExtractProotLoader()?.absolutePath

        if (!rootfsReady) {
            return@withContext Report(
                rootfsReady = false,
                prootPath = prootPath,
                loaderPath = loaderPath,
                probes = emptyList()
            )
        }

        val probes = listOf(
            "guest-shell" to "echo AMAYA_GUEST_SHELL_OK",
            "busybox" to "busybox echo AMAYA_BUSYBOX_OK",
            "apk-version" to "apk --version",
            "dns-resolution" to "getent hosts dl-cdn.alpinelinux.org || busybox nslookup dl-cdn.alpinelinux.org",
            "repository-update" to "apk update"
        ).map { (name, command) ->
            execute(manager, name, command)
        }

        Report(
            rootfsReady = true,
            prootPath = prootPath,
            loaderPath = loaderPath,
            probes = probes
        )
    }

    private suspend fun execute(
        manager: LinuxSandboxManager,
        name: String,
        command: String
    ): ProbeResult = withContext(Dispatchers.IO) {
        val (args, env) = manager.buildExecution(command, null)
        try {
            val process = ProcessBuilder(args)
                .redirectErrorStream(true)
                .apply {
                    environment().putAll(env)
                    environment().remove("LD_PRELOAD")
                }
                .start()

            val output = StringBuilder()
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45)
                while (System.nanoTime() < deadline) {
                    while (reader.ready()) {
                        if (output.isNotEmpty()) output.append('\n')
                        output.append(reader.readLine())
                    }
                    if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                        reader.readLines().forEach { line ->
                            if (output.isNotEmpty()) output.append('\n')
                            output.append(line)
                        }
                        return@withContext ProbeResult(name, command, process.exitValue(), output.toString(), false)
                    }
                }
            }

            process.destroyForcibly()
            ProbeResult(name, command, null, output.toString(), true)
        } catch (e: Exception) {
            debugLog(TAG, "Probe $name failed to launch: ${e.message}")
            ProbeResult(name, command, null, "", false, e.message ?: IOException::class.java.simpleName)
        }
    }
}
