package com.euthopiar.core.provider.catalog

import com.euthopiar.core.model.EpisodeItem
import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType

/**
 * High-level category for content discovery providers.
 */
enum class ProviderCategory(val displayName: String) {
    MOVIES_WEB_SERIES("Movies & Web Series"),
    ANIME_DONGHUA("Anime & Donghua"),
    KIDS_CARTOONS("Kids Cartoons & Animation"),
    REGIONAL_DRAMA("Desi TV Serials, K-Drama & Regional Drama"),
    MULTI_SOURCE_TOOLS("Multi-Source Aggregators & Tools"),
    OPEN_ACCESS_CATALOG("Open Access & Public Domain")
}

/**
 * Health and operational status of an integration.
 */
enum class IntegrationHealthStatus {
    ACTIVE,         // Passed all compatibility, authorization, and operational checks
    TESTING,        // Under testing / staged verification
    DISABLED,       // Intentionally disabled (by user or administrative config)
    INCOMPATIBLE,   // Cannot run in native Android Media3 architecture (e.g. .cs3 Dalvik bytecode)
    UNAUTHORIZED,   // Authorization or licensing could not be legally established
    FAILED,         // Failed health checks or endpoint unresponsive
    UNAVAILABLE     // Remote server or endpoint temporarily unavailable
}

/**
 * Detailed health metrics for an integration.
 */
data class ProviderHealthInfo(
    val status: IntegrationHealthStatus,
    val latencyMs: Long = 0L,
    val lastCheckTimestampMs: Long? = null,
    val errorMessage: String? = null
)

/**
 * Capabilities supported by a Content Provider.
 */
data class ContentProviderCapabilities(
    val supportsSearch: Boolean = true,
    val supportsDetails: Boolean = true,
    val supportsEpisodes: Boolean = false,
    val supportsCatalog: Boolean = false
)

/**
 * Dedicated contract for Content Providers (Catalog, Search, and Metadata Discovery).
 *
 * ARCHITECTURAL RULE: A Content Provider is strictly responsible for content discovery
 * and metadata retrieval. It NEVER returns playable media streams or stream URLs.
 */
interface ContentProvider {
    val id: String
    val name: String
    val category: ProviderCategory
    val description: String
    val supportedTypes: Set<MediaType>
    val supportedLanguages: List<String>
    val capabilities: ContentProviderCapabilities
    val isEnabled: Boolean
    val healthStatus: IntegrationHealthStatus
    val lastSuccessfulCheckMs: Long?
    val lastErrorMessage: String?

    suspend fun checkHealth(): ProviderHealthInfo

    suspend fun search(query: String): List<MediaItem>

    suspend fun getDetails(mediaId: String): MediaDetail?

    suspend fun getEpisodes(mediaId: String, season: Int? = null): List<EpisodeItem> = emptyList()
}
