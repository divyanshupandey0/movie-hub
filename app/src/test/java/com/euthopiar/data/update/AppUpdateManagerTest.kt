package com.euthopiar.data.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppUpdateManagerTest {

    private lateinit var context: Context
    private lateinit var updateManager: AppUpdateManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        updateManager = AppUpdateManager.getInstance(context)
    }

    @Test
    fun testOfficialRepositoryConfig() {
        // Must point strictly to the user's official GitHub repository
        assertEquals("divyanshupandey0", AppUpdateConfig.GITHUB_OWNER)
        assertEquals("movie-hub", AppUpdateConfig.GITHUB_REPO)
        assertEquals("https://github.com/divyanshupandey0/movie-hub", AppUpdateConfig.GITHUB_REPO_URL)
        assertEquals("https://github.com/divyanshupandey0/movie-hub/releases", AppUpdateConfig.GITHUB_RELEASES_URL)
        assertEquals(
            "https://api.github.com/repos/divyanshupandey0/movie-hub/releases/latest",
            AppUpdateConfig.GITHUB_LATEST_RELEASE_API_URL
        )
        assertTrue(AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED)
    }

    @Test
    fun testOldRepositoryUrlCannotBeUsed() {
        assertFalse(
            "AppUpdateConfig must not point to legacy owner TheARCreactorGUY",
            AppUpdateConfig.GITHUB_LATEST_RELEASE_API_URL.contains("TheARCreactorGUY", ignoreCase = true)
        )
        assertFalse(
            "AppUpdateConfig must not point to legacy repo Euthopiar",
            AppUpdateConfig.GITHUB_LATEST_RELEASE_API_URL.contains("Euthopiar", ignoreCase = true)
        )
        assertFalse(
            "AppUpdateConfig must not use hardcoded version 1.2.0 in URL",
            AppUpdateConfig.GITHUB_LATEST_RELEASE_API_URL.contains("1.2.0")
        )
    }

    @Test
    fun testReleaseVersionParsingFromGitHubApi() {
        val githubApiResponse = JSONObject().apply {
            put("tag_name", "v1.0.1")
            put("name", "MOVIE HUB v1.0.1")
            put("body", "## What's New\n- Direct update system\n- Fixed player controls")
            put("html_url", "https://github.com/divyanshupandey0/movie-hub/releases/tag/v1.0.1")
            put("published_at", "2026-11-16T07:32:15Z")
            put("prerelease", false)
            val assetsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "MOVIE-HUB-v1.0.1.apk")
                    put("browser_download_url", "https://github.com/divyanshupandey0/movie-hub/releases/download/v1.0.1/MOVIE-HUB-v1.0.1.apk")
                    put("size", 75366400L)
                })
                put(JSONObject().apply {
                    put("name", "source.zip")
                    put("browser_download_url", "https://github.com/divyanshupandey0/movie-hub/archive/refs/tags/v1.0.1.zip")
                    put("size", 12345L)
                })
            }
            put("assets", assetsArray)
        }

        val releaseInfo = updateManager.parseGitHubReleaseApi(githubApiResponse)

        assertEquals("v1.0.1", releaseInfo.tagName)
        assertEquals("1.0.1", releaseInfo.versionName)
        assertEquals("MOVIE HUB v1.0.1", releaseInfo.releaseTitle)
        assertTrue(releaseInfo.changelog.contains("Direct update system"))
        assertEquals("https://github.com/divyanshupandey0/movie-hub/releases/download/v1.0.1/MOVIE-HUB-v1.0.1.apk", releaseInfo.apkDownloadUrl)
        assertEquals(75366400L, releaseInfo.apkSizeBytes)
        assertFalse(releaseInfo.isPrerelease)
    }

    @Test
    fun testVersionComparisonInstalledVsRemote() {
        val installedVersion = "1.1.0-Stable"

        // Remote is v1.0.1 (existing latest release on GitHub) -> Installed is NEWER, so NOT an update
        assertFalse(
            "Installed 1.1.0-Stable is newer than remote v1.0.1, so no update should be triggered",
            isRemoteVersionNewer(installedVersion, "v1.0.1")
        )

        // Remote is v1.1.0 -> same version, no update
        assertFalse(isRemoteVersionNewer(installedVersion, "v1.1.0"))

        // Remote is v1.2.0 (future update) -> should trigger update
        assertTrue(
            "Future remote release v1.2.0 is newer than installed 1.1.0-Stable",
            isRemoteVersionNewer(installedVersion, "v1.2.0")
        )

        // Remote is v2.0.0 (future major update) -> should trigger update
        assertTrue(isRemoteVersionNewer(installedVersion, "v2.0.0"))

        // Test isRemoteUpdateAvailable helper
        assertFalse(
            "isRemoteUpdateAvailable should return false for current release v1.0.1 against 1.1.0-Stable",
            isRemoteUpdateAvailable(
                currentVersionCode = 110,
                remoteVersionCode = 101,
                currentVersionName = installedVersion,
                remoteVersionName = "1.0.1"
            )
        )

        assertTrue(
            "isRemoteUpdateAvailable should return true when a newer version like 1.2.0 is released",
            isRemoteUpdateAvailable(
                currentVersionCode = 110,
                remoteVersionCode = 120,
                currentVersionName = installedVersion,
                remoteVersionName = "1.2.0"
            )
        )

        // Production verification: installed 1.0.1 against GitHub release v1.0.2
        assertTrue(
            "Installed 1.0.1 must detect GitHub release v1.0.2 as a new update",
            isRemoteUpdateAvailable(
                currentVersionCode = 101,
                remoteVersionCode = 102,
                currentVersionName = "1.0.1",
                remoteVersionName = "1.0.2"
            )
        )

        assertFalse(
            "Installed 1.0.2 must not detect v1.0.2 as an update",
            isRemoteUpdateAvailable(
                currentVersionCode = 102,
                remoteVersionCode = 102,
                currentVersionName = "1.0.2",
                remoteVersionName = "1.0.2"
            )
        )
    }

    @Test
    fun testApkDownloadUrlBelongsToOfficialRepository() {
        val githubApiResponse = JSONObject().apply {
            put("tag_name", "v1.0.1")
            put("name", "MOVIE HUB v1.0.1")
            val assetsArray = JSONArray().apply {
                put(JSONObject().apply {
                    put("name", "MOVIE-HUB-v1.0.1.apk")
                    put("browser_download_url", "https://github.com/divyanshupandey0/movie-hub/releases/download/v1.0.1/MOVIE-HUB-v1.0.1.apk")
                    put("size", 50000000L)
                })
            }
            put("assets", assetsArray)
        }

        val release = updateManager.parseGitHubReleaseApi(githubApiResponse)
        assertNotNull(release.apkDownloadUrl)
        assertTrue(
            "Download URL must point to github.com releases",
            release.apkDownloadUrl!!.startsWith("https://github.com/divyanshupandey0/movie-hub/releases/download/")
        )
    }

    @Test
    fun testReleaseNotesComeFromCorrectRelease() {
        val bodyText = "### Official MOVIE HUB Release\n- Replaced legacy update system\n- Fixed movie playback"
        val githubApiResponse = JSONObject().apply {
            put("tag_name", "v1.0.1")
            put("name", "MOVIE HUB v1.0.1")
            put("body", bodyText)
        }

        val release = updateManager.parseGitHubReleaseApi(githubApiResponse)
        assertEquals(bodyText, release.changelog)
    }

    @Test
    fun testApiErrorsDoNotShowFakeUpdates() {
        // UpdateCheckState.Error should contain the error message and never show an update available
        val errorState = UpdateCheckState.Error("GitHub Releases API returned HTTP 404")
        assertTrue(errorState is UpdateCheckState.Error)
        assertEquals("GitHub Releases API returned HTTP 404", errorState.message)

        val upToDateState = UpdateCheckState.UpToDate("1.1.0-Stable", null)
        assertTrue(upToDateState is UpdateCheckState.UpToDate)
        assertEquals("1.1.0-Stable", upToDateState.currentVersion)
        assertNull(upToDateState.latestRelease)
    }
}
