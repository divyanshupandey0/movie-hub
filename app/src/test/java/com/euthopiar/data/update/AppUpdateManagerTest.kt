package com.euthopiar.data.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class AppUpdateManagerTest {

    @Test
    fun testUpdateConfigPointsToOfficialMovieHubRepo() {
        assertEquals("divyanshupandey0", AppUpdateConfig.GITHUB_OWNER)
        assertEquals("movie-hub", AppUpdateConfig.GITHUB_REPO)
        assertEquals("https://github.com/divyanshupandey0/movie-hub", AppUpdateConfig.GITHUB_REPO_URL)
        assertEquals("https://github.com/divyanshupandey0/movie-hub/releases", AppUpdateConfig.GITHUB_RELEASES_URL)
        assertTrue(AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED)
    }

    @Test
    fun testVersionComparisonWithVersionCode() {
        // Newer version code
        assertTrue(isRemoteUpdateAvailable(
            currentVersionCode = 11,
            remoteVersionCode = 12,
            currentVersionName = "1.1.0",
            remoteVersionName = "1.2.0"
        ))

        // Same version code
        assertFalse(isRemoteUpdateAvailable(
            currentVersionCode = 11,
            remoteVersionCode = 11,
            currentVersionName = "1.1.0",
            remoteVersionName = "1.1.0"
        ))

        // Older version code
        assertFalse(isRemoteUpdateAvailable(
            currentVersionCode = 12,
            remoteVersionCode = 11,
            currentVersionName = "1.2.0",
            remoteVersionName = "1.1.0"
        ))
    }

    @Test
    fun testSemanticVersionComparison() {
        // Standard semantic versions
        assertTrue(isRemoteVersionNewer("1.0.0", "1.0.1"))
        assertTrue(isRemoteVersionNewer("1.0.0", "v1.1.0"))
        assertTrue(isRemoteVersionNewer("1.1.0", "2.0.0"))

        // Up to date or older
        assertFalse(isRemoteVersionNewer("1.1.0", "1.1.0"))
        assertFalse(isRemoteVersionNewer("1.2.0", "1.1.9"))
        assertFalse(isRemoteVersionNewer("2.0.0", "1.9.9"))

        // Pre-release vs stable upgrade
        assertTrue(isRemoteVersionNewer("1.1.0-beta", "1.1.0-stable"))
        assertTrue(isRemoteVersionNewer("1.1.0-beta", "1.1.0"))
    }

    @Test
    fun testVersionJsonParsing() {
        val jsonStr = """
            {
              "versionCode": 12,
              "versionName": "1.2.0",
              "downloadUrl": "https://github.com/divyanshupandey0/movie-hub/releases/download/v1.0.0/MOVIE-HUB-v1.0.0.apk",
              "releaseNotes": "## What's New\n* Direct updates",
              "isUpdateAvailable": true,
              "minimumSupportedVersion": 1
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        assertEquals(12, json.getInt("versionCode"))
        assertEquals("1.2.0", json.getString("versionName"))
        assertTrue(json.getString("downloadUrl").startsWith("https://github.com/divyanshupandey0/"))
        assertEquals("## What's New\n* Direct updates", json.getString("releaseNotes"))
        assertTrue(json.getBoolean("isUpdateAvailable"))
        assertEquals(1, json.getInt("minimumSupportedVersion"))
    }
}
