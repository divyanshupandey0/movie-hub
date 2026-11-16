package com.euthopiar.data.ota

import androidx.compose.runtime.Immutable
import com.euthopiar.data.update.isRemoteVersionNewer
import org.json.JSONObject

@Immutable
data class OtaAnnouncement(
    val enabled: Boolean = false,
    val id: String = "",
    val title: String = "",
    val message: String = "",
    val actionLabel: String = "",
    val actionUrl: String = "",
    val priority: String = "normal",
    val dismissible: Boolean = true
)

@Immutable
data class OtaMaintenance(
    val enabled: Boolean = false,
    val title: String = "",
    val message: String = "",
    val isBlocking: Boolean = false
)

@Immutable
data class OtaPromoBanner(
    val enabled: Boolean = false,
    val id: String = "",
    val title: String = "",
    val subtitle: String = "",
    val bannerImageUrl: String = "",
    val targetMediaType: String = "movie",
    val targetMediaId: String = ""
)

@Immutable
data class OtaContentDisplay(
    val featuredMediaType: String = "movie",
    val featuredMediaId: String = "",
    val trendingCacheTtlHours: Int = 6,
    val recommendedGenres: List<String> = emptyList()
)

@Immutable
data class OtaAppSettings(
    val defaultPlayerBufferSeconds: Int = 30,
    val defaultAutoSkipIntro: Boolean = true,
    val networkTimeoutSeconds: Int = 15
)

@Immutable
data class OtaRemoteConfig(
    val configVersion: Int = 1,
    val configName: String = "default",
    val minSupportedAppVersion: String = "1.0.0",
    val updatedAt: String = "",
    val announcement: OtaAnnouncement = OtaAnnouncement(),
    val maintenance: OtaMaintenance = OtaMaintenance(),
    val promotionalBanner: OtaPromoBanner = OtaPromoBanner(),
    val featureFlags: Map<String, Boolean> = emptyMap(),
    val uiText: Map<String, String> = emptyMap(),
    val contentDisplay: OtaContentDisplay = OtaContentDisplay(),
    val appSettings: OtaAppSettings = OtaAppSettings()
) {

    fun isFeatureEnabled(flagKey: String, default: Boolean = true): Boolean {
        return featureFlags[flagKey] ?: default
    }

    fun getUiText(key: String, fallback: String): String {
        val remoteText = uiText[key]
        return if (!remoteText.isNullOrBlank()) remoteText else fallback
    }

    companion object {
        val DEFAULT = OtaRemoteConfig(
            configVersion = 1,
            configName = "local-defaults",
            minSupportedAppVersion = "1.0.0",
            updatedAt = "2026-11-16T00:00:00Z",
            announcement = OtaAnnouncement(
                enabled = false,
                id = "default",
                title = "Welcome to MOVIE HUB",
                message = "Experience high-performance streaming with intelligent Hindi audio selection."
            ),
            maintenance = OtaMaintenance(enabled = false),
            promotionalBanner = OtaPromoBanner(enabled = false),
            featureFlags = mapOf(
                "enable_live_scraper_tester" to true,
                "enable_cloud_sync" to true,
                "enable_simkl_tracker" to true,
                "enable_trakt_tracker" to true,
                "enable_real_debrid" to true,
                "enable_all_debrid" to true,
                "enable_downloads" to true,
                "enable_playlists" to true,
                "enable_hindi_audio_auto_select" to true,
                "enable_promotional_banners" to true,
                "enable_announcements" to true
            ),
            uiText = mapOf(
                "home_top_ten_title" to "Top 10 Today",
                "home_top_ten_subtitle" to "Updated daily",
                "home_featured_tag" to "FEATURED SPOTLIGHT",
                "explore_screen_title" to "Find something.",
                "library_empty_title" to "Your library is empty",
                "library_empty_subtitle" to "Save movies and series to build your personal collection."
            ),
            contentDisplay = OtaContentDisplay(
                featuredMediaType = "movie",
                featuredMediaId = "",
                trendingCacheTtlHours = 6,
                recommendedGenres = listOf("Action", "Adventure", "Fantasy", "Sci-Fi", "Drama", "Thriller")
            ),
            appSettings = OtaAppSettings(
                defaultPlayerBufferSeconds = 30,
                defaultAutoSkipIntro = true,
                networkTimeoutSeconds = 15
            )
        )
    }
}

sealed interface OtaValidationResult {
    data class Success(val config: OtaRemoteConfig) : OtaValidationResult
    data class Error(val reason: String) : OtaValidationResult
}

object OtaConfigParser {

    /**
     * Parses and strictly validates a JSON string from the official remote config endpoint.
     * Guarantees zero code execution and validates version compatibility against current installed app version.
     */
    fun parseAndValidate(jsonString: String, currentAppVersion: String): OtaValidationResult {
        if (jsonString.isBlank()) {
            return OtaValidationResult.Error("Remote configuration payload is empty.")
        }

        return try {
            val root = JSONObject(jsonString)

            // Security check: reject executable or script indicators
            val lower = jsonString.lowercase()
            if (lower.contains("javascript:") || lower.contains(".apk") || lower.contains(".dex") || lower.contains("<script")) {
                return OtaValidationResult.Error("Security check rejected configuration containing untrusted script or binary references.")
            }

            // 1. Version checks
            val configVersion = root.optInt("config_version", -1)
            if (configVersion <= 0) {
                return OtaValidationResult.Error("Missing or invalid config_version ($configVersion). Must be positive integer.")
            }

            val configName = sanitizeString(root.optString("config_name", "v$configVersion"))
            val minAppVersion = sanitizeString(root.optString("minimum_supported_app_version", "1.0.0"))
            val updatedAt = sanitizeString(root.optString("updated_at", ""))

            // Compatibility check: if the installed app is older than minimum_supported_app_version, reject
            if (isRemoteVersionNewer(currentVersion = currentAppVersion, remoteTag = minAppVersion)) {
                return OtaValidationResult.Error(
                    "Configuration requires minimum app version $minAppVersion, but currently installed version is $currentAppVersion."
                )
            }

            // 2. Announcement parsing
            val announcementObj = root.optJSONObject("announcement")
            val announcement = if (announcementObj != null) {
                OtaAnnouncement(
                    enabled = announcementObj.optBoolean("enabled", false),
                    id = sanitizeString(announcementObj.optString("id", "")),
                    title = sanitizeString(announcementObj.optString("title", "")),
                    message = sanitizeString(announcementObj.optString("message", "")),
                    actionLabel = sanitizeString(announcementObj.optString("action_label", "")),
                    actionUrl = sanitizeUrl(announcementObj.optString("action_url", "")),
                    priority = sanitizeString(announcementObj.optString("priority", "normal")),
                    dismissible = announcementObj.optBoolean("dismissible", true)
                )
            } else {
                OtaAnnouncement()
            }

            // 3. Maintenance parsing
            val maintenanceObj = root.optJSONObject("maintenance")
            val maintenance = if (maintenanceObj != null) {
                OtaMaintenance(
                    enabled = maintenanceObj.optBoolean("enabled", false),
                    title = sanitizeString(maintenanceObj.optString("title", "Under Maintenance")),
                    message = sanitizeString(maintenanceObj.optString("message", "Service will resume shortly.")),
                    isBlocking = maintenanceObj.optBoolean("is_blocking", false)
                )
            } else {
                OtaMaintenance()
            }

            // 4. Promotional banner parsing
            val promoObj = root.optJSONObject("promotional_banner")
            val promoBanner = if (promoObj != null) {
                OtaPromoBanner(
                    enabled = promoObj.optBoolean("enabled", false),
                    id = sanitizeString(promoObj.optString("id", "")),
                    title = sanitizeString(promoObj.optString("title", "")),
                    subtitle = sanitizeString(promoObj.optString("subtitle", "")),
                    bannerImageUrl = sanitizeUrl(promoObj.optString("banner_image_url", "")),
                    targetMediaType = sanitizeString(promoObj.optString("target_media_type", "movie")),
                    targetMediaId = sanitizeString(promoObj.optString("target_media_id", ""))
                )
            } else {
                OtaPromoBanner()
            }

            // 5. Feature flags parsing
            val featureFlags = mutableMapOf<String, Boolean>()
            val flagsObj = root.optJSONObject("feature_flags")
            if (flagsObj != null) {
                val keys = flagsObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    featureFlags[key] = flagsObj.optBoolean(key, true)
                }
            }

            // 6. UI Text parsing
            val uiText = mutableMapOf<String, String>()
            val textObj = root.optJSONObject("ui_text")
            if (textObj != null) {
                val keys = textObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    uiText[key] = sanitizeString(textObj.optString(key, ""))
                }
            }

            // 7. Content display parsing
            val contentObj = root.optJSONObject("content_display")
            val contentDisplay = if (contentObj != null) {
                val genresArray = contentObj.optJSONArray("recommended_genres")
                val genresList = mutableListOf<String>()
                if (genresArray != null) {
                    for (i in 0 until genresArray.length()) {
                        val g = sanitizeString(genresArray.optString(i, ""))
                        if (g.isNotBlank()) genresList.add(g)
                    }
                }
                OtaContentDisplay(
                    featuredMediaType = sanitizeString(contentObj.optString("featured_media_type", "movie")),
                    featuredMediaId = sanitizeString(contentObj.optString("featured_media_id", "")),
                    trendingCacheTtlHours = contentObj.optInt("trending_cache_ttl_hours", 6).coerceIn(1, 48),
                    recommendedGenres = genresList
                )
            } else {
                OtaContentDisplay()
            }

            // 8. App settings parsing
            val appSettingsObj = root.optJSONObject("app_settings")
            val appSettings = if (appSettingsObj != null) {
                OtaAppSettings(
                    defaultPlayerBufferSeconds = appSettingsObj.optInt("default_player_buffer_seconds", 30).coerceIn(5, 120),
                    defaultAutoSkipIntro = appSettingsObj.optBoolean("default_auto_skip_intro", true),
                    networkTimeoutSeconds = appSettingsObj.optInt("network_timeout_seconds", 15).coerceIn(5, 60)
                )
            } else {
                OtaAppSettings()
            }

            val validConfig = OtaRemoteConfig(
                configVersion = configVersion,
                configName = configName,
                minSupportedAppVersion = minAppVersion,
                updatedAt = updatedAt,
                announcement = announcement,
                maintenance = maintenance,
                promotionalBanner = promoBanner,
                featureFlags = featureFlags,
                uiText = uiText,
                contentDisplay = contentDisplay,
                appSettings = appSettings
            )

            OtaValidationResult.Success(validConfig)
        } catch (e: Exception) {
            OtaValidationResult.Error("JSON parsing failure: ${e.message ?: "Invalid JSON format"}")
        }
    }

    private fun sanitizeString(value: String): String {
        return value.trim().take(500)
    }

    private fun sanitizeUrl(value: String): String {
        val trimmed = value.trim()
        return if (trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed.take(1000)
        } else {
            ""
        }
    }
}
