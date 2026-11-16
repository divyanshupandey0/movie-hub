package com.euthopiar.data.ota

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class OtaConfigManagerTest {

    private lateinit var context: Context
    private lateinit var otaManager: OtaConfigManager

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        otaManager = OtaConfigManager.getInstance(context)
    }

    @Test
    fun testDefaultRemoteConfigSafeFallback() {
        val defaultConfig = OtaRemoteConfig.DEFAULT
        assertEquals(1, defaultConfig.configVersion)
        assertEquals("local-defaults", defaultConfig.configName)
        assertEquals("1.0.0", defaultConfig.minSupportedAppVersion)
        assertFalse("Maintenance should be disabled by default", defaultConfig.maintenance.enabled)
        assertTrue("Scraper tester should be enabled by default", defaultConfig.isFeatureEnabled("enable_live_scraper_tester"))
        assertTrue("Cloud sync should be enabled by default", defaultConfig.isFeatureEnabled("enable_cloud_sync"))
        assertEquals("Top 10 Today", defaultConfig.getUiText("home_top_ten_title", "Fallback"))
        assertEquals("Fallback", defaultConfig.getUiText("non_existent_key", "Fallback"))
    }

    @Test
    fun testValidRemoteConfigParsingAndValidation() {
        val sampleJson = """
            {
              "config_version": 2,
              "config_name": "production-v2",
              "minimum_supported_app_version": "1.1.0",
              "updated_at": "2026-11-16T21:00:00Z",
              "announcement": {
                "enabled": true,
                "id": "announcement_test_01",
                "title": "New Feature Available",
                "message": "Hindi audio track selection is now live.",
                "action_label": "Read More",
                "action_url": "https://moviehub.example.com/announcement",
                "priority": "high",
                "dismissible": true
              },
              "maintenance": {
                "enabled": false,
                "title": "Scheduled Maintenance",
                "message": "Maintenance active",
                "is_blocking": false
              },
              "promotional_banner": {
                "enabled": true,
                "id": "promo_01",
                "title": "Spotlight",
                "subtitle": "Featured stream",
                "banner_image_url": "https://moviehub.example.com/banner.jpg",
                "target_media_type": "movie",
                "target_media_id": "123"
              },
              "feature_flags": {
                "enable_live_scraper_tester": true,
                "enable_hindi_audio_auto_select": true,
                "test_experimental_feature": false
              },
              "ui_text": {
                "home_top_ten_title": "Trending Worldwide"
              },
              "content_display": {
                "featured_media_type": "movie",
                "featured_media_id": "456",
                "trending_cache_ttl_hours": 12,
                "recommended_genres": ["Action", "Sci-Fi"]
              },
              "app_settings": {
                "default_player_buffer_seconds": 45,
                "default_auto_skip_intro": true,
                "network_timeout_seconds": 20
              }
            }
        """.trimIndent()

        val result = OtaConfigParser.parseAndValidate(sampleJson, currentAppVersion = "1.1.2")
        assertTrue("Valid JSON must parse successfully", result is OtaValidationResult.Success)

        val config = (result as OtaValidationResult.Success).config
        assertEquals(2, config.configVersion)
        assertEquals("production-v2", config.configName)
        assertEquals("1.1.0", config.minSupportedAppVersion)
        assertTrue(config.announcement.enabled)
        assertEquals("New Feature Available", config.announcement.title)
        assertEquals("https://moviehub.example.com/announcement", config.announcement.actionUrl)
        assertFalse(config.maintenance.enabled)
        assertTrue(config.promotionalBanner.enabled)
        assertTrue(config.isFeatureEnabled("enable_live_scraper_tester"))
        assertFalse(config.isFeatureEnabled("test_experimental_feature"))
        assertEquals("Trending Worldwide", config.getUiText("home_top_ten_title", "Default"))
        assertEquals(45, config.appSettings.defaultPlayerBufferSeconds)
        assertEquals(12, config.contentDisplay.trendingCacheTtlHours)
    }

    @Test
    fun testIncompatibleMinimumAppVersionRejection() {
        val futureJson = """
            {
              "config_version": 99,
              "config_name": "future-v99",
              "minimum_supported_app_version": "2.0.0"
            }
        """.trimIndent()

        // Current installed app is 1.1.2, but config requires minimum 2.0.0
        val result = OtaConfigParser.parseAndValidate(futureJson, currentAppVersion = "1.1.2")
        assertTrue("Incompatible minimum app version must be rejected", result is OtaValidationResult.Error)
        val err = (result as OtaValidationResult.Error).reason
        assertTrue(err.contains("requires minimum app version 2.0.0"))
    }

    @Test
    fun testExecutablePayloadSecurityRejection() {
        val maliciousJson = """
            {
              "config_version": 5,
              "config_name": "exploit",
              "minimum_supported_app_version": "1.0.0",
              "announcement": {
                "enabled": true,
                "action_url": "javascript:eval(atob('malicious_code'))"
              }
            }
        """.trimIndent()

        val result = OtaConfigParser.parseAndValidate(maliciousJson, currentAppVersion = "1.1.2")
        assertTrue("Payload with executable script indicators must be rejected", result is OtaValidationResult.Error)
        val err = (result as OtaValidationResult.Error).reason
        assertTrue(err.contains("Security check rejected"))
    }

    @Test
    fun testMalformedJsonHandling() {
        val corruptJson = "{ this is not valid json : [[["
        val result = OtaConfigParser.parseAndValidate(corruptJson, currentAppVersion = "1.1.2")
        assertTrue("Malformed JSON must return Error result without crashing", result is OtaValidationResult.Error)
    }

    @Test
    fun testMissingOptionalFieldsFallbackToSafeDefaults() {
        val minimalJson = """
            {
              "config_version": 3,
              "minimum_supported_app_version": "1.0.0"
            }
        """.trimIndent()

        val result = OtaConfigParser.parseAndValidate(minimalJson, currentAppVersion = "1.1.2")
        assertTrue(result is OtaValidationResult.Success)
        val config = (result as OtaValidationResult.Success).config
        assertEquals(3, config.configVersion)
        assertFalse(config.announcement.enabled)
        assertFalse(config.maintenance.enabled)
        assertFalse(config.promotionalBanner.enabled)
        assertEquals(30, config.appSettings.defaultPlayerBufferSeconds)
    }

    @Test
    fun testDismissAnnouncement() {
        val testId = "announcement_test_42"
        assertFalse(otaManager.isAnnouncementDismissed(testId))

        otaManager.dismissAnnouncement(testId)
        assertTrue(otaManager.isAnnouncementDismissed(testId))
        assertTrue(otaManager.dismissedAnnouncementIds.value.contains(testId))
    }

    @Test
    fun testProductionRemoteConfigFileStructure() {
        val candidates = listOf(
            java.io.File("../remote-config.json"),
            java.io.File("remote-config.json"),
            java.io.File("../../remote-config.json")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertNotNull("remote-config.json should be present in project hierarchy", file)
        val jsonText = file!!.readText()

        val result = OtaConfigParser.parseAndValidate(jsonText, currentAppVersion = "2.0.1")
        assertTrue("Repository remote-config.json must be valid", result is OtaValidationResult.Success)

        val config = (result as OtaValidationResult.Success).config
        assertEquals(1, config.configVersion)
        assertEquals("production-v1", config.configName)
        assertEquals("1.1.0", config.minSupportedAppVersion)
        assertTrue(config.announcement.enabled)
        assertEquals("Welcome to MOVIE HUB", config.announcement.title)
        assertFalse(config.maintenance.enabled)
        assertTrue(config.isFeatureEnabled("enable_live_scraper_tester"))
        assertEquals("Top 10 Today", config.getUiText("home_top_ten_title", "Fallback"))
        assertEquals("Updated daily", config.getUiText("home_top_ten_subtitle", "Fallback"))
    }
}
