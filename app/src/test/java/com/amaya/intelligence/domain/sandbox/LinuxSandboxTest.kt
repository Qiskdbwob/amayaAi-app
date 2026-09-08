package com.amaya.intelligence.domain.sandbox

import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.UUID

class LinuxSandboxTest {

    @Test
    fun `architectures define correct 32-bit and 64-bit attributes`() {
        assertTrue(LinuxArchitecture.AARCH64.is64Bit)
        assertEquals("aarch64", LinuxArchitecture.AARCH64.alpineArch)
        assertEquals("aarch64", LinuxArchitecture.AARCH64.prootArch)

        assertFalse(LinuxArchitecture.ARMV7.is64Bit)
        assertEquals("armv7", LinuxArchitecture.ARMV7.alpineArch)
        assertEquals("arm", LinuxArchitecture.ARMV7.prootArch)

        assertTrue(LinuxArchitecture.X86_64.is64Bit)
        assertEquals("x86_64", LinuxArchitecture.X86_64.alpineArch)
        assertEquals("x86_64", LinuxArchitecture.X86_64.prootArch)

        assertFalse(LinuxArchitecture.X86.is64Bit)
        assertEquals("x86", LinuxArchitecture.X86.alpineArch)
        assertEquals("i686", LinuxArchitecture.X86.prootArch)
    }

    @Test
    fun `minirootfs URLs point to valid Alpine 3_20 endpoints`() {
        for (arch in LinuxArchitecture.entries) {
            assertTrue(arch.minirootfsUrl.contains("alpine/v3.20/releases/${arch.alpineArch}/alpine-minirootfs-3.20.0-${arch.alpineArch}.tar.gz"))
            assertTrue(arch.minirootfsBackupUrl.contains(arch.alpineArch))
            assertTrue(arch.prootBinaryUrl.contains(arch.prootArch))
        }
    }

    @Test
    fun `proot binary URLs point to existing v5_3_0 static release assets`() {
        for (arch in LinuxArchitecture.entries) {
            assertTrue(
                "proot URL must use the v5.3.0 -static assets (v5.4.0 paths 404)",
                arch.prootBinaryUrl.endsWith("proot-v5.3.0-${arch.prootArch}-static")
            )
        }
    }

    @Test
    fun `materializeSh replaces dangling bin sh symlink with busybox copy`() {
        val rootfs = createTempDirectory("alpine-rootfs-").toFile()
        try {
            val binDir = File(rootfs, "bin").apply { mkdirs() }
            val busybox = File(binDir, "busybox").apply { writeText("# fake busybox payload") }
            // Simulate the minirootfs layout: bin/sh is an absolute symlink to a host
            // path that does not exist here. On Android `/bin/busybox` never exists on
            // the host root, so the link dangles; use a random absolute target so the
            // test also dangles on CI runners that happen to ship busybox in /bin.
            val sh = File(binDir, "sh")
            java.nio.file.Files.createSymbolicLink(
                sh.toPath(),
                java.nio.file.Paths.get("/bin/busybox-does-not-exist-${UUID.randomUUID()}")
            )
            assertFalse(sh.exists()) // dangling, exactly the production symptom

            val healed = LinuxSandboxManager.materializeSh(rootfs)

            assertTrue("materializeSh should heal a dangling /bin/sh", healed)
            assertFalse("bin/sh must become a regular file, not a symlink", java.nio.file.Files.isSymbolicLink(sh.toPath()))
            assertTrue(sh.exists())
            assertTrue(sh.canExecute())
            assertEquals(busybox.readText(), sh.readText())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `materializeSh fails cleanly without busybox`() {
        val rootfs = createTempDirectory("alpine-rootfs-empty-").toFile()
        try {
            assertFalse(LinuxSandboxManager.materializeSh(rootfs))
            assertFalse(File(rootfs, "bin/sh").exists())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `provisionGuestDns installs resolver only when missing or empty`() {
        val rootfs = createTempDirectory("alpine-dns-").toFile()
        try {
            assertTrue(LinuxSandboxManager.provisionGuestDns(rootfs))
            val conf = File(rootfs, "etc/resolv.conf")
            assertTrue(conf.exists())
            assertTrue(conf.readText().contains("nameserver 8.8.8.8"))

            // Empty file (what the minirootfs ships) must be provisioned too.
            conf.writeText("")
            assertTrue(LinuxSandboxManager.provisionGuestDns(rootfs))
            assertTrue(conf.readText().contains("nameserver 1.1.1.1"))

            // A user-provided non-empty resolver config is never overwritten.
            conf.writeText("nameserver 10.0.0.1\n")
            assertTrue(LinuxSandboxManager.provisionGuestDns(rootfs))
            assertEquals("nameserver 10.0.0.1\n", conf.readText())
        } finally {
            rootfs.deleteRecursively()
        }
    }

    @Test
    fun `architecture detect returns a valid supported architecture`() {
        val detected = LinuxArchitecture.detect()
        assertNotNull(detected)
        assertTrue(detected in LinuxArchitecture.entries)
    }

    @Test
    fun `buildResolvConf includes public fallbacks and options`() {
        val resolv = LinuxSandboxManager.buildResolvConf(null)
        assertTrue(resolv.contains("nameserver 8.8.8.8"))
        assertTrue(resolv.contains("nameserver 1.1.1.1"))
        assertTrue(resolv.contains("options timeout:2 attempts:2"))
    }

    @Test
    fun `buildExecutionArgs includes link2symlink and defaults to root working directory`() {
        val rootfs = File("/tmp/fake_rootfs")
        val args = LinuxSandboxManager.buildExecutionArgs(
            prootPath = "/data/data/com.amaya.intelligence/lib/libproot.so",
            rootfsDir = rootfs,
            command = "git status",
            workspaceDir = null
        )

        assertTrue(args.contains("--link2symlink"))
        assertTrue(args.contains("-0"))
        assertTrue(args.contains("-r"))
        assertTrue(args.contains(rootfs.absolutePath))
        val wIndex = args.indexOf("-w")
        assertTrue(wIndex != -1)
        assertEquals("/root", args[wIndex + 1])
        assertTrue(args.contains("git status"))
    }

    @Test
    fun `buildExecutionArgs binds and switches to workspace when directory exists`() {
        val rootfs = File("/tmp/fake_rootfs")
        val tempWorkspace = createTempDirectory("workspace-").toFile()
        try {
            val args = LinuxSandboxManager.buildExecutionArgs(
                prootPath = "/data/data/com.amaya.intelligence/lib/libproot.so",
                rootfsDir = rootfs,
                command = "ls -la",
                workspaceDir = tempWorkspace.absolutePath
            )

            assertTrue(args.contains("${tempWorkspace.absolutePath}:/workspace"))
            val wIndex = args.indexOf("-w")
            assertTrue(wIndex != -1)
            assertEquals("/workspace", args[wIndex + 1])
        } finally {
            tempWorkspace.deleteRecursively()
        }
    }

    @Test
    fun `buildExecutionArgs falls back to system sh when proot is missing`() {
        val rootfs = File("/tmp/fake_rootfs")
        val args = LinuxSandboxManager.buildExecutionArgs(
            prootPath = null,
            rootfsDir = rootfs,
            command = "echo hello",
            workspaceDir = null
        )

        assertEquals(listOf("/system/bin/sh", "-c", "echo hello"), args)
    }

    @Test
    fun `buildExecutionEnv sets PROOT_NO_SECCOMP, PROOT_LOADER, and SSL certificates`() {
        val tmpDir = File("/tmp/proot_tmp")
        val env = LinuxSandboxManager.buildExecutionEnv(
            hasProot = true,
            prootTmpDir = tmpDir,
            prootLoaderPath = "/data/app/lib/arm64/libproot_loader.so"
        )

        assertEquals("/root", env["HOME"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
        assertEquals("1", env["PROOT_IGNORE_MISSING_BINDINGS"])
        assertEquals("/tmp", env["TMPDIR"])
        assertEquals(tmpDir.absolutePath, env["PROOT_TMP_DIR"])
        assertEquals("/data/app/lib/arm64/libproot_loader.so", env["PROOT_LOADER"])
        assertEquals("/data/app/lib/arm64/libproot_loader.so", env["PROOT_LOADER_32"])
        assertEquals("/data/app/lib/arm64/libproot_loader.so", env["PROOT_LOADER_64"])
        assertEquals("/etc/ssl/certs/ca-certificates.crt", env["SSL_CERT_FILE"])
        assertEquals("/etc/ssl/certs/ca-certificates.crt", env["GIT_SSL_CAINFO"])
        assertEquals("/etc/ssl/certs/ca-certificates.crt", env["CURL_CA_BUNDLE"])
    }

    @Test
    fun `extractEmbeddedLoader extracts ELF loader from arm64 and armv7 libproot`() {
        val arm64Proot = File("src/main/jniLibs/arm64-v8a/libproot.so")
        val arm7Proot = File("src/main/jniLibs/armeabi-v7a/libproot.so")
        val destDir = createTempDirectory("loader-test-").toFile()
        try {
            if (arm64Proot.exists()) {
                val dest64 = File(destDir, "arm64_loader")
                val res = LinuxSandboxManager.extractEmbeddedLoader(arm64Proot, dest64)
                assertTrue(res)
                assertTrue(dest64.exists())
                assertTrue(dest64.length() > 50000L)
            }
            if (arm7Proot.exists()) {
                val dest7 = File(destDir, "arm7_loader")
                val res = LinuxSandboxManager.extractEmbeddedLoader(arm7Proot, dest7)
                assertTrue(res)
                assertTrue(dest7.exists())
                assertTrue(dest7.length() > 50000L)
            }
        } finally {
            destDir.deleteRecursively()
        }
    }

    @Test
    fun `configureApkRepositories provisions http mirrors and rewrites legacy https`() {
        val rootfs = createTempDirectory("alpine-repo-").toFile()
        try {
            LinuxSandboxManager.configureApkRepositories(rootfs)
            val repoFile = File(rootfs, "etc/apk/repositories")
            assertTrue(repoFile.exists())
            val initialContent = repoFile.readText()
            assertTrue(initialContent.contains("http://dl-cdn.alpinelinux.org/alpine/v3.20/main"))
            assertTrue(initialContent.contains("http://dl-cdn.alpinelinux.org/alpine/v3.20/community"))
            assertFalse(initialContent.contains("https://"))

            // Rewrite legacy https repos to avoid SSL bootstrap failures
            repoFile.writeText("https://dl-cdn.alpinelinux.org/alpine/v3.20/main\n")
            LinuxSandboxManager.configureApkRepositories(rootfs)
            val updatedContent = repoFile.readText()
            assertTrue(updatedContent.contains("http://dl-cdn.alpinelinux.org/alpine/v3.20/main"))
            assertFalse(updatedContent.contains("https://"))
        } finally {
            rootfs.deleteRecursively()
        }
    }
}
