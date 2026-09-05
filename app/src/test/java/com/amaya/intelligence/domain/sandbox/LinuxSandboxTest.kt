package com.amaya.intelligence.domain.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
        val rootfs = createTempDir("alpine-rootfs-")
        try {
            val binDir = File(rootfs, "bin").apply { mkdirs() }
            val busybox = File(binDir, "busybox").apply { writeText("# fake busybox payload") }
            // Simulate the minirootfs layout: absolute symlink to the host /bin/busybox.
            // On the test host it dangles just like it does on an Android device.
            val sh = File(binDir, "sh")
            java.nio.file.Files.createSymbolicLink(sh.toPath(), java.nio.file.Paths.get("/bin/busybox"))
            assertFalse(sh.exists()) // dangling on the host, exactly the production symptom

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
        val rootfs = createTempDir("alpine-rootfs-empty-")
        try {
            assertFalse(LinuxSandboxManager.materializeSh(rootfs))
            assertFalse(File(rootfs, "bin/sh").exists())
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
}
