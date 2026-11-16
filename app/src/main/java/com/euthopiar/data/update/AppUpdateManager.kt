package com.euthopiar.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.Immutable
import androidx.core.content.FileProvider
import com.euthopiar.core.network.DohDns
import com.euthopiar.data.preferences.PreferencesRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

object AppUpdateConfig {
    /**
     * Master toggle for the remote update subsystem.
     */
    const val IS_UPDATE_SYSTEM_ENABLED = true

    /**
     * Official GitHub Repository Information.
     */
    const val GITHUB_OWNER = "divyanshupandey0"
    const val GITHUB_REPO = "movie-hub"
    const val GITHUB_REPO_URL = "https://github.com/$GITHUB_OWNER/$GITHUB_REPO"
    const val GITHUB_RELEASES_URL = "$GITHUB_REPO_URL/releases"
    const val GITHUB_LATEST_RELEASE_API_URL = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"
}

@Immutable
data class GitHubVersionManifest(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String,
    val isUpdateAvailableFlag: Boolean = true,
    val minimumSupportedVersion: Int = 1
)

@Immutable
data class ReleaseInfo(
    val tagName: String,
    val versionName: String,
    val versionCode: Int,
    val releaseTitle: String,
    val changelog: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long = 0L,
    val isPrerelease: Boolean = false,
    val isMandatory: Boolean = false,
    val minSupportedVersionCode: Int = 1,
    val apkSha256: String = ""
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpdateAvailable(
        val release: ReleaseInfo,
        val currentVersion: String,
        val currentVersionCode: Int,
        val isMandatory: Boolean
    ) : UpdateCheckState
    data class UpToDate(val currentVersion: String, val latestRelease: ReleaseInfo? = null) : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}

sealed interface ApkDownloadState {
    data object Idle : ApkDownloadState
    data class Downloading(
        val progressFraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : ApkDownloadState
    data class Paused(
        val progressFraction: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : ApkDownloadState
    data class ReadyToInstall(val apkFile: File, val release: ReleaseInfo) : ApkDownloadState
    data class Error(val message: String, val canRetry: Boolean = true) : ApkDownloadState
}

/**
 * Checks if remote version is newer than installed version.
 */
fun isRemoteUpdateAvailable(
    currentVersionCode: Int,
    remoteVersionCode: Int,
    currentVersionName: String,
    remoteVersionName: String
): Boolean {
    if (!AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) return false

    // 1. Numerical comparison on versionCode if both are valid positive integers
    if (remoteVersionCode > 0 && currentVersionCode > 0) {
        return remoteVersionCode > currentVersionCode
    }

    // 2. Fallback: semantic comparison on versionName
    return isRemoteVersionNewer(currentVersionName, remoteVersionName)
}

fun isRemoteVersionNewer(currentVersion: String, remoteTag: String): Boolean {
    if (!AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) return false
    val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V")
    val cleanRemote = remoteTag.trim().removePrefix("v").removePrefix("V")

    val currentCore = cleanCurrent.substringBefore("-").substringBefore("+")
    val remoteCore = cleanRemote.substringBefore("-").substringBefore("+")

    val currentParts = currentCore.split(".").mapNotNull { it.toIntOrNull() }
    val remoteParts = remoteCore.split(".").mapNotNull { it.toIntOrNull() }

    val maxLen = maxOf(currentParts.size, remoteParts.size)
    for (i in 0 until maxLen) {
        val curr = currentParts.getOrElse(i) { 0 }
        val rem = remoteParts.getOrElse(i) { 0 }
        if (rem > curr) return true
        if (rem < curr) return false
    }

    val currentHasPreRelease = cleanCurrent.contains("-") && !cleanCurrent.contains("-stable", ignoreCase = true)
    val remoteHasPreRelease = cleanRemote.contains("-") && !cleanRemote.contains("-stable", ignoreCase = true)

    if (currentHasPreRelease && !remoteHasPreRelease) {
        return true
    }

    return false
}

class AppUpdateManager private constructor(private val context: Context) {

    private val preferencesRepository = PreferencesRepository.getInstance(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _updateState = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val updateState: StateFlow<UpdateCheckState> = _updateState.asStateFlow()

    private val _downloadState = MutableStateFlow<ApkDownloadState>(ApkDownloadState.Idle)
    val downloadState: StateFlow<ApkDownloadState> = _downloadState.asStateFlow()

    private val _bannerDismissedForSession = MutableStateFlow(false)
    val bannerDismissedForSession: StateFlow<Boolean> = _bannerDismissedForSession.asStateFlow()

    private val _autoPopupShownForSession = MutableStateFlow(false)
    val autoPopupShownForSession: StateFlow<Boolean> = _autoPopupShownForSession.asStateFlow()

    private var activeDownloadJob: Job? = null
    private var lastAttemptedRelease: ReleaseInfo? = null

    fun getCurrentVersionName(): String {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pInfo.versionName ?: "1.0.1"
        } catch (_: Exception) {
            "1.0.1"
        }
    }

    fun getCurrentVersionCode(): Int {
        return try {
            val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (_: Exception) {
            101
        }
    }

    suspend fun checkForUpdates(forceManual: Boolean = false) {
        if (!AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) {
            _updateState.value = UpdateCheckState.UpToDate(getCurrentVersionName())
            return
        }
        _updateState.value = UpdateCheckState.Checking
        val currentVersion = getCurrentVersionName()
        val currentVersionCode = getCurrentVersionCode()

        withContext(Dispatchers.IO) {
            var lastErrorMessage: String? = null
            var resolvedRelease: ReleaseInfo? = null

            val client = DohDns.sharedBaseClient

            try {
                val request = Request.Builder()
                    .url(AppUpdateConfig.GITHUB_LATEST_RELEASE_API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MOVIE-HUB-Android/$currentVersion")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (!body.isNullOrBlank()) {
                            resolvedRelease = parseGitHubReleaseApi(JSONObject(body))
                        } else {
                            lastErrorMessage = "Empty response received from GitHub Releases API"
                        }
                    } else {
                        lastErrorMessage = "GitHub Releases API returned HTTP ${response.code}"
                    }
                }
            } catch (e: Exception) {
                lastErrorMessage = e.message ?: "Network connection failed"
            }

            preferencesRepository.setLastUpdateCheckTimestamp(System.currentTimeMillis())

            val release = resolvedRelease
            if (release != null) {
                val isNewer = isRemoteUpdateAvailable(
                    currentVersionCode = currentVersionCode,
                    remoteVersionCode = release.versionCode,
                    currentVersionName = currentVersion,
                    remoteVersionName = release.versionName
                )

                if (isNewer) {
                    _updateState.value = UpdateCheckState.UpdateAvailable(
                        release = release,
                        currentVersion = currentVersion,
                        currentVersionCode = currentVersionCode,
                        isMandatory = release.isMandatory
                    )
                } else {
                    _updateState.value = UpdateCheckState.UpToDate(currentVersion, release)
                }
            } else {
                if (forceManual) {
                    _updateState.value = UpdateCheckState.Error(
                        lastErrorMessage ?: "Unable to connect to GitHub repository. Please check your internet connection."
                    )
                } else {
                    _updateState.value = UpdateCheckState.UpToDate(currentVersion, null)
                }
            }
        }
    }

    fun parseGitHubReleaseApi(json: JSONObject): ReleaseInfo {
        val tagName = json.optString("tag_name", "").trim()
        val versionName = tagName.removePrefix("v").removePrefix("V")
        val releaseTitle = json.optString("name", if (tagName.isNotBlank()) "MOVIE HUB $tagName" else "MOVIE HUB Release")
        val changelog = json.optString("body", "Bug fixes and performance improvements.")
        val publishedAt = json.optString("published_at", "Recently")
        val htmlUrl = json.optString("html_url", AppUpdateConfig.GITHUB_RELEASES_URL)
        val isPrerelease = json.optBoolean("prerelease", false)

        var apkDownloadUrl: String? = null
        var apkSizeBytes = 0L

        val assets = json.optJSONArray("assets")
        if (assets != null) {
            // Priority 1: Official branded MOVIE-HUB-*.apk
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val assetName = asset.optString("name", "")
                if (assetName.startsWith("MOVIE-HUB", ignoreCase = true) && assetName.endsWith(".apk", ignoreCase = true)) {
                    val url = asset.optString("browser_download_url", null)
                    if (url != null && (url.contains("github.com") || url.startsWith("https://"))) {
                        apkDownloadUrl = url
                        apkSizeBytes = asset.optLong("size", 0L)
                        break
                    }
                }
            }
            // Priority 2: Fallback to any valid .apk asset
            if (apkDownloadUrl == null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.optString("name", "")
                    if (assetName.endsWith(".apk", ignoreCase = true)) {
                        val url = asset.optString("browser_download_url", null)
                        if (url != null && (url.contains("github.com") || url.startsWith("https://"))) {
                            apkDownloadUrl = url
                            apkSizeBytes = asset.optLong("size", 0L)
                            break
                        }
                    }
                }
            }
        }

        val inferredVersionCode = try {
            val parts = versionName.split(".")
            val major = parts.getOrNull(0)?.toIntOrNull() ?: 1
            val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val patch = parts.getOrNull(2)?.substringBefore("-")?.toIntOrNull() ?: 0
            (major * 100) + (minor * 10) + patch
        } catch (_: Exception) {
            0
        }

        // Support explicit versionCode in release notes or release title
        val explicitVersionCode = try {
            val body = json.optString("body", "")
            val title = json.optString("name", "")
            val regex = Regex("""(?i)\b(?:versionCode|code|build|vc)[:=\s]+(\d+)\b""")
            regex.find(body)?.groupValues?.get(1)?.toIntOrNull()
                ?: regex.find(title)?.groupValues?.get(1)?.toIntOrNull()
        } catch (_: Exception) {
            null
        }

        val resolvedVersionCode = explicitVersionCode ?: inferredVersionCode

        return ReleaseInfo(
            tagName = tagName,
            versionName = versionName,
            versionCode = resolvedVersionCode,
            releaseTitle = releaseTitle,
            changelog = changelog,
            publishedAt = publishedAt.substringBefore("T"),
            htmlUrl = htmlUrl,
            apkDownloadUrl = apkDownloadUrl,
            apkSizeBytes = apkSizeBytes,
            isPrerelease = isPrerelease,
            isMandatory = false,
            minSupportedVersionCode = 1
        )
    }

    /**
     * Downloads target APK with live progress, pause/resume, and validation.
     */
    fun downloadAndInstallUpdate(release: ReleaseInfo) {
        if (!AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) return
        val downloadUrl = release.apkDownloadUrl
        if (downloadUrl.isNullOrBlank()) {
            openInBrowser(release.htmlUrl)
            return
        }

        if (activeDownloadJob?.isActive == true) {
            return
        }

        lastAttemptedRelease = release

        activeDownloadJob = scope.launch(Dispatchers.IO) {
            try {
                val updatesDir = File(context.cacheDir, "updates")
                if (!updatesDir.exists()) {
                    updatesDir.mkdirs()
                }

                val sanitizedVersion = release.versionName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                val apkFile = File(updatesDir, "MOVIE-HUB-$sanitizedVersion.apk")
                val tempFile = File(updatesDir, "MOVIE-HUB-$sanitizedVersion.apk.part")

                var existingBytes = 0L
                if (tempFile.exists()) {
                    existingBytes = tempFile.length()
                }

                _downloadState.value = ApkDownloadState.Downloading(
                    progressFraction = if (release.apkSizeBytes > 0) (existingBytes.toFloat() / release.apkSizeBytes).coerceIn(0f, 1f) else 0f,
                    downloadedBytes = existingBytes,
                    totalBytes = release.apkSizeBytes
                )

                val client = DohDns.sharedBaseClient
                val requestBuilder = Request.Builder()
                    .url(downloadUrl)
                    .header("User-Agent", "MOVIE-HUB-Android/${getCurrentVersionName()}")

                if (existingBytes > 0) {
                    requestBuilder.header("Range", "bytes=$existingBytes-")
                }

                client.newCall(requestBuilder.build()).execute().use { response ->
                    val isResume = response.code == 206
                    if (!response.isSuccessful) {
                        _downloadState.value = ApkDownloadState.Error("Download failed with HTTP ${response.code}")
                        return@launch
                    }

                    val body = response.body ?: run {
                        _downloadState.value = ApkDownloadState.Error("Empty download stream from server")
                        return@launch
                    }

                    val serverContentLength = body.contentLength()
                    val totalBytes = when {
                        isResume && serverContentLength > 0 -> existingBytes + serverContentLength
                        serverContentLength > 0 -> serverContentLength
                        release.apkSizeBytes > 0 -> release.apkSizeBytes
                        else -> 0L
                    }

                    if (!isResume && existingBytes > 0) {
                        tempFile.delete()
                        existingBytes = 0L
                    }

                    val append = isResume && existingBytes > 0
                    var downloadedBytes = existingBytes

                    body.byteStream().use { input ->
                        FileOutputStream(tempFile, append).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            var bytesRead: Int
                            var lastProgressUpdate = 0L

                            while (input.read(buffer).also { bytesRead = it } != -1) {
                                output.write(buffer, 0, bytesRead)
                                downloadedBytes += bytesRead

                                val now = System.currentTimeMillis()
                                if (now - lastProgressUpdate > 100 || (totalBytes > 0 && downloadedBytes == totalBytes)) {
                                    lastProgressUpdate = now
                                    val fraction = if (totalBytes > 0) {
                                        (downloadedBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
                                    } else {
                                        0f
                                    }
                                    _downloadState.value = ApkDownloadState.Downloading(fraction, downloadedBytes, totalBytes)
                                }
                            }
                            output.flush()
                        }
                    }
                }

                // Verify file integrity
                if (release.apkSha256.isNotBlank()) {
                    val computedSha256 = calculateSha256(tempFile)
                    if (!computedSha256.equals(release.apkSha256.trim(), ignoreCase = true)) {
                        tempFile.delete()
                        _downloadState.value = ApkDownloadState.Error("APK checksum verification failed. Please retry.")
                        return@launch
                    }
                }

                // Move temp part to final APK
                if (apkFile.exists()) {
                    apkFile.delete()
                }
                if (!tempFile.renameTo(apkFile)) {
                    tempFile.copyTo(apkFile, overwrite = true)
                    tempFile.delete()
                }

                // Pre-installation APK package, signature and version compatibility audit
                val compatibilityError = verifyApkCompatibility(apkFile)
                if (compatibilityError != null) {
                    apkFile.delete()
                    _downloadState.value = ApkDownloadState.Error(compatibilityError)
                    return@launch
                }

                _downloadState.value = ApkDownloadState.ReadyToInstall(apkFile, release)
                withContext(Dispatchers.Main) {
                    triggerInstallApk(apkFile, release.htmlUrl)
                }
            } catch (e: CancellationException) {
                _downloadState.value = ApkDownloadState.Idle
            } catch (e: Exception) {
                _downloadState.value = ApkDownloadState.Error(
                    e.message ?: "Download encountered an error. Tap retry to resume."
                )
            } finally {
                activeDownloadJob = null
            }
        }
    }

    fun retryDownload() {
        lastAttemptedRelease?.let { release ->
            downloadAndInstallUpdate(release)
        }
    }

    fun pauseOrCancelDownload() {
        activeDownloadJob?.cancel()
        activeDownloadJob = null
        val current = _downloadState.value
        if (current is ApkDownloadState.Downloading) {
            _downloadState.value = ApkDownloadState.Paused(
                progressFraction = current.progressFraction,
                downloadedBytes = current.downloadedBytes,
                totalBytes = current.totalBytes
            )
        } else {
            _downloadState.value = ApkDownloadState.Idle
        }
    }

    fun verifyApkCompatibility(apkFile: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            android.content.pm.PackageManager.GET_SIGNATURES
        }
        val archiveInfo = pm.getPackageArchiveInfo(apkFile.absolutePath, flags)
            ?: return "Downloaded file is corrupted or not a valid Android APK."
        if (archiveInfo.packageName != context.packageName) {
            return "Package name mismatch: APK is '${archiveInfo.packageName}', but installed app is '${context.packageName}'. Android creates separate applications for different package identities and cannot update across packages in-place. Install '${archiveInfo.packageName}' directly as a separate application."
        }
        val archiveVersionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.versionCode
        }
        val currentCode = getCurrentVersionCode()
        if (archiveVersionCode > 0 && currentCode > 0 && archiveVersionCode <= currentCode) {
            return "Cannot install update: Downloaded APK version (code $archiveVersionCode) is not newer than currently installed version (code $currentCode)."
        }
        val installedSignatures = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
                    .signatures?.map { it.toByteArray().contentHashCode() }
            }
        } catch (_: Exception) { null }

        val archiveSignatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            archiveInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray().contentHashCode() }
        } else {
            @Suppress("DEPRECATION")
            archiveInfo.signatures?.map { it.toByteArray().contentHashCode() }
        }

        if (!installedSignatures.isNullOrEmpty() && !archiveSignatures.isNullOrEmpty()) {
            val hasMatchingCert = installedSignatures.any { it in archiveSignatures }
            if (!hasMatchingCert) {
                return "Package signature conflict: APK is signed with a different key than your installed app."
            }
        }
        return null
    }

    private fun calculateSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(16 * 1024)
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Safely triggers the official Android PackageInstaller flow with unknown-app permissions handling.
     */
    fun triggerInstallApk(apkFile: File, fallbackUrl: String) {
        if (!AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED) return
        try {
            if (!apkFile.exists()) {
                openInBrowser(fallbackUrl)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (!context.packageManager.canRequestPackageInstalls()) {
                    val settingsIntent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(settingsIntent)
                    return
                }
            }

            val apkUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            markAutoPopupShownForSession()
            context.startActivity(installIntent)
        } catch (_: Exception) {
            openInBrowser(fallbackUrl)
        }
    }

    /**
     * Refreshes installed version against the current update state.
     * Transitions from UpdateAvailable to UpToDate if installed version now matches or exceeds the remote release.
     */
    fun refreshInstalledVersion(): Boolean {
        val currentVersion = getCurrentVersionName()
        val currentCode = getCurrentVersionCode()

        scope.launch(Dispatchers.IO) {
            val lastSavedCode = preferencesRepository.lastAppVersionCode.first()
            if (lastSavedCode != currentCode) {
                preferencesRepository.setLastAppVersionCode(currentCode)
                preferencesRepository.setLastAppVersion(currentVersion)
            }
        }

        val state = _updateState.value
        if (state is UpdateCheckState.UpdateAvailable) {
            val isStillNewer = isRemoteUpdateAvailable(
                currentVersionCode = currentCode,
                remoteVersionCode = state.release.versionCode,
                currentVersionName = currentVersion,
                remoteVersionName = state.release.versionName
            )
            if (!isStillNewer) {
                _updateState.value = UpdateCheckState.UpToDate(currentVersion, state.release)
                _downloadState.value = ApkDownloadState.Idle
                _autoPopupShownForSession.value = true
                cleanUpDownloadedApk()
                return true
            }
        } else if (state is UpdateCheckState.Idle || state is UpdateCheckState.UpToDate) {
            cleanUpDownloadedApk()
        }
        return false
    }

    fun cleanUpDownloadedApk() {
        try {
            val updatesDir = File(context.cacheDir, "updates")
            if (updatesDir.exists()) {
                updatesDir.deleteRecursively()
            }
        } catch (_: Exception) {}
    }

    fun openInBrowser(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {}
    }

    fun dismissBannerForSession() {
        _bannerDismissedForSession.value = true
    }

    fun markAutoPopupShownForSession() {
        _autoPopupShownForSession.value = true
    }

    fun skipVersion(versionName: String) {
        scope.launch {
            preferencesRepository.setSkippedUpdateVersion(versionName)
        }
    }

    fun resetDownloadState() {
        _downloadState.value = ApkDownloadState.Idle
    }

    companion object {
        @Volatile
        private var instance: AppUpdateManager? = null

        fun getInstance(context: Context): AppUpdateManager {
            return instance ?: synchronized(this) {
                instance ?: AppUpdateManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
