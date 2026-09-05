package com.amaya.intelligence.domain.sandbox

import android.os.Build

/**
 * Supported Linux architectures for Alpine Linux rootfs and PRoot binary.
 * Supports both 64-bit (aarch64, x86_64) and 32-bit (armv7, x86) Android devices.
 */
enum class LinuxArchitecture(
    val androidAbi: String,
    val alpineArch: String,
    val prootArch: String,
    val displayName: String,
    val is64Bit: Boolean
) {
    AARCH64(
        androidAbi = "arm64-v8a",
        alpineArch = "aarch64",
        prootArch = "aarch64",
        displayName = "ARM64 (64-bit)",
        is64Bit = true
    ),
    ARMV7(
        androidAbi = "armeabi-v7a",
        alpineArch = "armv7",
        prootArch = "arm",
        displayName = "ARMv7 (32-bit)",
        is64Bit = false
    ),
    X86_64(
        androidAbi = "x86_64",
        alpineArch = "x86_64",
        prootArch = "x86_64",
        displayName = "x86_64 (64-bit Intel/AMD)",
        is64Bit = true
    ),
    X86(
        androidAbi = "x86",
        alpineArch = "x86",
        prootArch = "i686",
        displayName = "x86 (32-bit Intel)",
        is64Bit = false
    );

    /**
     * Primary official Alpine Linux minirootfs download URL.
     * Ultra-lightweight (~3-5MB compressed, ~15MB uncompressed).
     */
    val minirootfsUrl: String
        get() = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/$alpineArch/alpine-minirootfs-3.20.0-$alpineArch.tar.gz"

    /**
     * Mirror URL in case the primary CDN is unreachable.
     */
    val minirootfsBackupUrl: String
        get() = "https://mirrors.edge.kernel.org/alpine/v3.20/releases/$alpineArch/alpine-minirootfs-3.20.0-$alpineArch.tar.gz"

    /**
     * Static PRoot binary URL for non-root execution inside Android sandbox.
     */
    val prootBinaryUrl: String
        get() = "https://github.com/proot-me/proot/releases/download/v5.4.0/proot-v5.4.0-$prootArch"

    companion object {
        /**
         * Detect the device architecture based on Android [Build.SUPPORTED_ABIS].
         */
        fun detect(): LinuxArchitecture {
            val supported = Build.SUPPORTED_ABIS ?: emptyArray()
            for (abi in supported) {
                when {
                    abi.equals("arm64-v8a", ignoreCase = true) -> return AARCH64
                    abi.startsWith("arm64", ignoreCase = true) -> return AARCH64
                    abi.equals("armeabi-v7a", ignoreCase = true) -> return ARMV7
                    abi.startsWith("armeabi", ignoreCase = true) -> return ARMV7
                    abi.equals("x86_64", ignoreCase = true) -> return X86_64
                    abi.equals("x86", ignoreCase = true) -> return X86
                }
            }
            return AARCH64
        }
    }
}
