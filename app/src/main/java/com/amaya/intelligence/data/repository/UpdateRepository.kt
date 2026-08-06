package com.amaya.intelligence.data.repository

import android.os.Build
import com.amaya.intelligence.BuildConfig
import com.amaya.intelligence.data.remote.api.GitHubAsset
import com.amaya.intelligence.data.remote.api.GitHubUpdateService
import com.amaya.intelligence.domain.models.UpdateInfo
import com.amaya.intelligence.util.errorLog
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for app update operations.
 */
@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubService: GitHubUpdateService
) {
    companion object {
        private const val OWNER = "nazrielnr"
        private const val REPO = "amaya"

        internal fun selectApkAsset(assets: List<GitHubAsset>, supportedAbis: Array<String>): GitHubAsset? {
            val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) }
            return supportedAbis.firstNotNullOfOrNull { abi ->
                apks.firstOrNull { it.name.contains(abi, ignoreCase = true) }
            } ?: apks.firstOrNull()
        }

        internal fun isVersionNewer(latest: String, current: String): Boolean {
            if (latest == current) return false

            val latestParts = latest.split('-', limit = 2)
            val currentParts = current.split('-', limit = 2)
            val latestNumeric = latestParts[0].split('.').mapNotNull { it.toIntOrNull() }
            val currentNumeric = currentParts[0].split('.').mapNotNull { it.toIntOrNull() }

            for (i in 0 until maxOf(latestNumeric.size, currentNumeric.size)) {
                val latestPart = latestNumeric.getOrElse(i) { 0 }
                val currentPart = currentNumeric.getOrElse(i) { 0 }
                if (latestPart != currentPart) return latestPart > currentPart
            }

            val latestSuffix = latestParts.getOrNull(1)
            val currentSuffix = currentParts.getOrNull(1)
            return latestSuffix == null && currentSuffix != null ||
                latestSuffix != null && currentSuffix != null && latestSuffix > currentSuffix
        }
    }

    suspend fun getLatestUpdate(): UpdateInfo? {
        return try {
            val response = gitHubService.getLatestRelease(OWNER, REPO)
            val latestVersionName = response.tagName.removePrefix("v").trim()
            
            val currentVersionName = BuildConfig.VERSION_NAME
            
            val downloadUrl = selectApkAsset(response.assets, Build.SUPPORTED_ABIS)?.downloadUrl.orEmpty()
            val isNewer = downloadUrl.isNotBlank() && isVersionNewer(latestVersionName, currentVersionName)

            UpdateInfo(
                tagName = response.tagName,
                versionName = latestVersionName,
                versionCode = 0,
                changelog = response.body,
                downloadUrl = downloadUrl,
                isNewer = isNewer
            )
        } catch (e: Exception) {
            errorLog("UpdateRepository", "Failed to fetch latest update", e)
            null
        }
    }


}
