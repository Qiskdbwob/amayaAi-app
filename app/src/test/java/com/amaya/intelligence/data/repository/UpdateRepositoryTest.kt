package com.amaya.intelligence.data.repository

import com.amaya.intelligence.data.remote.api.GitHubAsset
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateRepositoryTest {
    @Test
    fun selectsApkMatchingSupportedAbi() {
        val assets = listOf(
            GitHubAsset("app-armeabi-v7a-release.apk", "https://example.com/32"),
            GitHubAsset("app-arm64-v8a-release.apk", "https://example.com/64")
        )

        assertTrue(UpdateRepository.selectApkAsset(assets, arrayOf("arm64-v8a", "armeabi-v7a"))?.downloadUrl == "https://example.com/64")
        assertTrue(UpdateRepository.selectApkAsset(assets, arrayOf("armeabi-v7a"))?.downloadUrl == "https://example.com/32")
    }

    @Test
    fun comparesReleaseVersions() {
        assertTrue(UpdateRepository.isVersionNewer("1.0.1", "1.0.0"))
        assertTrue(UpdateRepository.isVersionNewer("1.0.0", "1.0.0-rc1"))
        assertFalse(UpdateRepository.isVersionNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateRepository.isVersionNewer("1.0.0", "1.0.1"))
        assertFalse(UpdateRepository.isVersionNewer("1.0.0-beta", "1.0.0"))
    }
}
