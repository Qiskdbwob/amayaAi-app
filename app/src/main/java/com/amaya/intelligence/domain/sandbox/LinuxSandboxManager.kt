package com.amaya.intelligence.domain.sandbox

import android.content.Context
import com.amaya.intelligence.util.debugLog
import com.amaya.intelligence.util.errorLog
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the embedded PRoot + Alpine Linux sandbox environment.
 * Allows executing arbitrary Linux tools (Python, Node.js, Git, GCC, C/C++)
 * inside an unprivileged Android container on both 32-bit and 64-bit devices without root.
 */
@Singleton
class LinuxSandboxManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "LinuxSandboxManager"
        private const val SANDBOX_DIR_NAME = "linux_sandbox"
        private const val ALPINE_DIR_NAME = "alpine"
        private const val BIN_DIR_NAME = "bin"
        private const val DEFAULT_RESOLV_CONF = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"

        /**
         * Re-create `<rootfs>/bin/sh` as a plain copy of `<rootfs>/bin/busybox`.
         * The minirootfs ships `bin/sh` as a symlink to the absolute host path
         * `/bin/busybox`, which dangles once extracted into app-private storage
         * (there is no `/bin` at the Android host root). Busybox dispatches on
         * argv[0], so a copy named `sh` behaves exactly like the ash shell.
         *
         * Returns true when `bin/sh` exists (or was healed) and is executable.
         * Visible for testing.
         */
        internal fun materializeSh(rootfsDir: File): Boolean {
            val busybox = File(rootfsDir, "bin/busybox")
            val sh = File(rootfsDir, "bin/sh")
            if (!busybox.exists()) return false
            try {
                sh.delete() // remove dangling (or valid) symlink
                busybox.copyTo(sh, overwrite = true)
                sh.setExecutable(true, false)
                sh.setReadable(true, false)
            } catch (e: Exception) {
                return false
            }
            return sh.exists()
        }

        /**
         * Guarantees the guest can resolve DNS: installs `etc/resolv.conf` with
         * public resolvers when missing or empty. The minirootfs ships an empty
         * resolv.conf, and without it `apk update` fails every download.
         * Existing non-empty files are left untouched. Visible for testing.
         */
        internal fun provisionGuestDns(rootfsDir: File): Boolean {
            return try {
                val resolvConf = File(rootfsDir, "etc/resolv.conf")
                if (resolvConf.exists() && resolvConf.length() > 0L) return true
                resolvConf.parentFile?.mkdirs()
                resolvConf.writeText(DEFAULT_RESOLV_CONF)
                resolvConf.exists() && resolvConf.length() > 0L
            } catch (e: Exception) {
                false
            }
        }
    }

    private val sandboxBaseDir: File
        get() = File(context.filesDir, SANDBOX_DIR_NAME)

    val rootfsDir: File
        get() = File(sandboxBaseDir, ALPINE_DIR_NAME)

    val binDir: File
        get() = File(sandboxBaseDir, BIN_DIR_NAME)

    val prootFile: File
        get() = File(binDir, "proot")

    /**
     * The PRoot binary bundled inside the APK as a jniLib (`libproot.so`).
     * Android 10+ (W^X) forbids executing binaries from app-private storage,
     * but files in [ApplicationInfo.nativeLibraryDir] are still executable, so
     * we prefer the bundled copy and keep the downloaded `bin/proot` as fallback
     * (e.g. for x86 debug builds that carry no bundled `libproot.so`).
     */
    private val bundledProotFile: File?
        get() {
            val f = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
            return if (f.exists() && f.canExecute()) f else null
        }

    private val _status = MutableStateFlow<SandboxStatus>(SandboxStatus.NotInstalled)
    val status: StateFlow<SandboxStatus> = _status.asStateFlow()

    init {
        checkStatus()
    }

    /**
     * Check current state of the sandbox on disk.
     */
    fun checkStatus(): SandboxStatus {
        val arch = LinuxArchitecture.detect()
        val isRootfsReady = isReady()
        val isProotReady = bundledProotFile != null || (prootFile.exists() && prootFile.canExecute())

        val newStatus = if (isRootfsReady) {
            SandboxStatus.Ready(
                architecture = arch,
                rootfsPath = rootfsDir.absolutePath,
                prootAvailable = isProotReady,
                details = "Alpine Linux 3.20 (${arch.displayName})"
            )
        } else {
            SandboxStatus.NotInstalled
        }

        _status.value = newStatus
        return newStatus
    }

    /**
     * The minirootfs ships `bin/sh` as a symlink to the absolute host path
     * `/bin/busybox`, which dangles once extracted into app-private storage
     * (there is no `/bin` on the Android host root). `busybox` itself is a
     * regular file inside the rootfs, so it is the reliable readiness marker.
     */
    fun isReady(): Boolean {
        val busybox = File(rootfsDir, "bin/busybox")
        if (!rootfsDir.exists() || !busybox.exists()) return false
        val sh = File(rootfsDir, "bin/sh")
        if (!sh.exists()) {
            // Heal installs where the shipped absolute /bin/sh symlink dangles.
            if (!materializeSh(rootfsDir)) return false
        }
        return true
    }

    /**
     * Installs Alpine Linux minirootfs and PRoot binary for the target architecture.
     * Supports both 64-bit (ARM64, x86_64) and 32-bit (ARMv7, x86) ABIs.
     */
    suspend fun install(
        onProgress: (stage: String, progress: Float) -> Unit = { _, _ -> }
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val architecture = LinuxArchitecture.detect()
        try {
            debugLog(TAG, "Starting Alpine Linux installation for ${architecture.displayName}")
            _status.value = SandboxStatus.Installing("Initializing directories...", 0.05f)
            onProgress("Initializing directories...", 0.05f)

            if (!sandboxBaseDir.exists()) sandboxBaseDir.mkdirs()
            if (!binDir.exists()) binDir.mkdirs()
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            // 1. Download Alpine minirootfs (.tar.gz)
            val rootfsTarGz = File(sandboxBaseDir, "alpine-minirootfs.tar.gz")
            _status.value = SandboxStatus.Installing("Downloading Alpine Linux rootfs...", 0.15f)
            onProgress("Downloading Alpine Linux rootfs...", 0.15f)

            val downloadSuccess = downloadFileWithProgress(
                url = architecture.minirootfsUrl,
                backupUrl = architecture.minirootfsBackupUrl,
                destination = rootfsTarGz,
                progressStart = 0.15f,
                progressEnd = 0.55f,
                stageName = "Downloading Alpine Linux rootfs (~4MB)...",
                onProgress = onProgress
            )

            if (!downloadSuccess || !rootfsTarGz.exists() || rootfsTarGz.length() < 1024L) {
                throw IOException("Failed to download Alpine rootfs archive from CDN mirrors")
            }

            // 2. Extract minirootfs
            _status.value = SandboxStatus.Installing("Extracting root filesystem...", 0.60f)
            onProgress("Extracting root filesystem...", 0.60f)

            extractTarGz(rootfsTarGz, rootfsDir) { extractProgress ->
                val overall = 0.60f + (extractProgress * 0.25f)
                _status.value = SandboxStatus.Installing("Extracting packages...", overall)
                onProgress("Extracting packages...", overall)
            }

            // Cleanup tar.gz to save device space
            rootfsTarGz.delete()

            // 3. Configure network DNS resolver
            _status.value = SandboxStatus.Installing("Configuring network & DNS...", 0.88f)
            onProgress("Configuring network & DNS...", 0.88f)
            setupDnsResolver()
            setupApkRepositories()

            // 4. Download and setup PRoot binary
            _status.value = SandboxStatus.Installing("Configuring PRoot binary...", 0.92f)
            onProgress("Configuring PRoot binary...", 0.92f)
            setupProotBinary(architecture)

            // 5. Make system binaries executable and heal the /bin/sh symlink
            fixExecutablePermissions(rootfsDir)
            materializeSh(rootfsDir)

            val readyStatus = SandboxStatus.Ready(
                architecture = architecture,
                rootfsPath = rootfsDir.absolutePath,
                prootAvailable = bundledProotFile != null || (prootFile.exists() && prootFile.canExecute()),
                details = "Alpine Linux 3.20 (${architecture.displayName})"
            )
            _status.value = readyStatus
            onProgress("Installation complete!", 1.0f)
            debugLog(TAG, "Alpine Linux sandbox successfully installed at ${rootfsDir.absolutePath}")
            Result.success(Unit)
        } catch (e: Exception) {
            errorLog(TAG, "Failed to install Alpine Linux sandbox: ${e.message}", e)
            val errorStatus = SandboxStatus.Error(e.message ?: "Installation failed", e)
            _status.value = errorStatus
            Result.failure(e)
        }
    }

    /**
     * Uninstalls the sandbox and frees all storage.
     */
    suspend fun uninstall(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            debugLog(TAG, "Uninstalling sandbox directory ${sandboxBaseDir.absolutePath}")
            sandboxBaseDir.deleteRecursively()
            _status.value = SandboxStatus.NotInstalled
            Result.success(Unit)
        } catch (e: Exception) {
            errorLog(TAG, "Failed to uninstall sandbox", e)
            Result.failure(e)
        }
    }

    /**
     * Builds execution command and environment for running a shell command inside the sandbox.
     */
    fun buildExecution(
        command: String,
        workspaceDir: String?
    ): Pair<List<String>, Map<String, String>> {
        // Idempotent pre-exec provisioning (only when a rootfs is really installed):
        // guest tmp dir + DNS resolver. Also keeps rootfs installs created before
        // these steps existed fully working.
        if (File(rootfsDir, "bin/busybox").exists()) {
            File(rootfsDir, "tmp").mkdirs()
            ensureGuestDns()
        }

        val prootPath = bundledProotFile?.absolutePath
            ?: prootFile.takeIf { it.exists() && it.canExecute() }?.absolutePath
        val hasProot = prootPath != null

        val cmdList = if (hasProot) {
            val list = mutableListOf(
                prootPath!!,
                "-0", // simulate root UID (0)
                "-r", rootfsDir.absolutePath,
                "-b", "/dev",
                "-b", "/proc",
                "-b", "/sys"
            )
            if (workspaceDir != null && File(workspaceDir).exists()) {
                list.add("-b")
                list.add("$workspaceDir:/workspace")
                list.add("-w")
                list.add("/workspace")
            }
            list.add("/bin/sh")
            list.add("-c")
            list.add(command)
            list
        } else {
            // Fallback: If PRoot binary is not yet available, execute shell directly
            listOf("/system/bin/sh", "-c", command)
        }

        val prootTmpDir = File(sandboxBaseDir, "tmp")
        prootTmpDir.mkdirs() // writable host dir for PRoot's loader extraction

        val envMap = buildMap {
            put("HOME", "/root")
            put("USER", "root")
            put("PATH", "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
            put("TERM", "xterm-256color")
            put("LANG", "C.UTF-8")
            put("SHELL", "/bin/sh")
            if (hasProot) {
                // PROOT_TMP_DIR must be a writable host dir for PRoot's loader;
                // TMPDIR stays a guest path so tool temp files land in rootfs /tmp.
                // NOTE: no LD_PRELOAD override — with targetSdk <= 28 exec() from
                // app data is permitted directly, and an empty value is redundant.
                put("TMPDIR", "/tmp")
                put("PROOT_TMP_DIR", prootTmpDir.absolutePath)
                put("PROOT_LOADER_TMP_DIR", prootTmpDir.absolutePath)
            }
        }

        return Pair(cmdList, envMap)
    }

    /**
     * Helper to run an `apk` package installation command (e.g. `apk add --no-cache python3 py3-pip`).
     */
    suspend fun runApkAdd(packageName: String): Result<String> = withContext(Dispatchers.IO) {
        checkStatus()
        if (!isReady()) {
            return@withContext Result.failure(IllegalStateException("Alpine Linux sandbox is not installed"))
        }

        val cmd = "apk update && apk add --no-cache $packageName"
        val (execCmd, envMap) = buildExecution(cmd, null)

        try {
            val processBuilder = ProcessBuilder(execCmd)
            processBuilder.environment().putAll(envMap)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                checkStatus()
                Result.success(output)
            } else {
                Result.failure(IOException("Package installation failed with exit code $exitCode:\n$output"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Ensures the guest can resolve DNS before executing commands in the rootfs.
     * Cheap and idempotent; safe to call before every exec.
     */
    private fun ensureGuestDns() {
        if (!provisionGuestDns(rootfsDir)) {
            debugLog(TAG, "Guest DNS provisioning skipped (rootfs not writable?)")
        }
    }

    private fun setupDnsResolver() {
        val etcDir = File(rootfsDir, "etc")
        etcDir.mkdirs()
        val resolvConf = File(etcDir, "resolv.conf")
        resolvConf.writeText(DEFAULT_RESOLV_CONF)
    }

    private fun setupApkRepositories() {
        val apkDir = File(rootfsDir, "etc/apk")
        apkDir.mkdirs()
        val repos = File(apkDir, "repositories")
        repos.writeText(
            """
            https://dl-cdn.alpinelinux.org/alpine/v3.20/main
            https://dl-cdn.alpinelinux.org/alpine/v3.20/community
            """.trimIndent() + "\n"
        )
    }

    private suspend fun setupProotBinary(architecture: LinuxArchitecture) {
        if (bundledProotFile != null || (prootFile.exists() && prootFile.canExecute())) {
            if (bundledProotFile != null) {
                debugLog(TAG, "Using bundled PRoot native library: ${bundledProotFile?.absolutePath}")
            }
            return
        }

        try {
            binDir.mkdirs()
            val request = Request.Builder().url(architecture.prootBinaryUrl).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    response.body?.byteStream()?.use { input ->
                        FileOutputStream(prootFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    prootFile.setExecutable(true, false)
                    debugLog(TAG, "PRoot binary downloaded and set executable: ${prootFile.absolutePath}")
                } else {
                    debugLog(TAG, "PRoot remote binary returned ${response.code}; will use container fallback")
                }
            }
        } catch (e: Exception) {
            debugLog(TAG, "PRoot download skipped: ${e.message}")
        }
    }

    private fun fixExecutablePermissions(dir: File) {
        val execDirs = listOf(
            File(dir, "bin"),
            File(dir, "sbin"),
            File(dir, "usr/bin"),
            File(dir, "usr/sbin")
        )
        for (d in execDirs) {
            if (d.exists() && d.isDirectory) {
                d.listFiles()?.forEach { file ->
                    file.setExecutable(true, false)
                }
            }
        }
    }

    private fun downloadFileWithProgress(
        url: String,
        backupUrl: String,
        destination: File,
        progressStart: Float,
        progressEnd: Float,
        stageName: String,
        onProgress: (String, Float) -> Unit
    ): Boolean {
        val urls = listOf(url, backupUrl)
        for (targetUrl in urls) {
            try {
                val request = Request.Builder().url(targetUrl).build()
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use

                    val body = response.body ?: return@use
                    val contentLength = body.contentLength()
                    val inputStream = body.byteStream()

                    destination.parentFile?.mkdirs()
                    FileOutputStream(destination).use { outputStream ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        var totalRead = 0L

                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead

                            if (contentLength > 0) {
                                val downloadRatio = totalRead.toFloat() / contentLength.toFloat()
                                val currentOverall = progressStart + (downloadRatio * (progressEnd - progressStart))
                                onProgress(stageName, currentOverall)
                            }
                        }
                    }
                    if (destination.exists() && destination.length() > 1024L) {
                        return true
                    }
                }
            } catch (e: Exception) {
                debugLog(TAG, "Failed downloading from $targetUrl: ${e.message}, trying backup...")
            }
        }
        return false
    }

    private fun extractTarGz(
        tarGzFile: File,
        destDir: File,
        onProgress: (Float) -> Unit
    ) {
        val totalBytes = tarGzFile.length()
        var processedBytes = 0L

        FileInputStream(tarGzFile).use { fis ->
            BufferedInputStream(fis).use { bis ->
                GzipCompressorInputStream(bis).use { gzis ->
                    TarArchiveInputStream(gzis).use { tarIn ->
                        var entry: TarArchiveEntry? = tarIn.nextTarEntry
                        while (entry != null) {
                            val targetFile = File(destDir, entry.name)

                            // Security check: Zip Slip prevention
                            if (!targetFile.canonicalPath.startsWith(destDir.canonicalPath)) {
                                entry = tarIn.nextTarEntry
                                continue
                            }

                            if (entry.isDirectory) {
                                targetFile.mkdirs()
                            } else if (entry.isSymbolicLink) {
                                try {
                                    targetFile.parentFile?.mkdirs()
                                    if (targetFile.exists()) targetFile.delete()
                                    Files.createSymbolicLink(
                                        targetFile.toPath(),
                                        Paths.get(entry.linkName)
                                    )
                                } catch (_: Exception) {
                                    // Ignored if target filesystem doesn't allow symlink
                                }
                            } else {
                                targetFile.parentFile?.mkdirs()
                                FileOutputStream(targetFile).use { out ->
                                    tarIn.copyTo(out)
                                }
                                if (entry.name.startsWith("bin/") ||
                                    entry.name.startsWith("sbin/") ||
                                    entry.name.startsWith("usr/bin/") ||
                                    entry.name.startsWith("usr/sbin/")
                                ) {
                                    targetFile.setExecutable(true, false)
                                }
                            }

                            processedBytes += entry.size
                            if (totalBytes > 0) {
                                val ratio = (processedBytes.toFloat() / (totalBytes * 3.5f)).coerceIn(0f, 1f)
                                onProgress(ratio)
                            }

                            entry = tarIn.nextTarEntry
                        }
                    }
                }
            }
        }
    }
}
