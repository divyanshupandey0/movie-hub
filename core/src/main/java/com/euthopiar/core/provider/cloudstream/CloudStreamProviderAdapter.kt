package com.euthopiar.core.provider.cloudstream

import com.euthopiar.core.model.EpisodeItem
import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.provider.ProviderCapabilities
import com.euthopiar.core.provider.ProviderHealth
import com.euthopiar.core.provider.ProviderStatus
import com.euthopiar.core.provider.StreamingProvider
import com.euthopiar.core.streams.StreamItem
import com.euthopiar.core.streams.StreamSubtitle

/**
 * Native MOVIE HUB adapter for CloudStream-derived providers.
 * Translates CloudStream MainAPI patterns (search, details, episodes, links)
 * into MOVIE HUB's unified streaming pipeline while enforcing strict sandboxing,
 * error isolation, and licensing compliance.
 */
abstract class CloudStreamProviderAdapter(
    val metadata: CloudStreamExtensionMetadata
) : StreamingProvider {

    override val id: String get() = "cs_${metadata.internalName.lowercase()}"
    override val name: String get() = metadata.name
    override val description: String get() = metadata.description ?: "CloudStream-derived provider"
    override val isEnabled: Boolean get() = metadata.status == CloudStreamIntegrationStatus.PASS
    override val supportedTypes: Set<MediaType> get() = metadata.supportedTypes
    override val supportedLanguages: List<String> get() = metadata.supportedLanguages

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
        supportsCatalog = false,
        supportsDirectPlayback = metadata.status == CloudStreamIntegrationStatus.PASS,
        supportsSubtitles = true
    )

    open suspend fun initialize(): Boolean = true

    open fun isAvailable(): Boolean = isEnabled

    override suspend fun checkHealth(): ProviderHealth {
        return when (metadata.status) {
            CloudStreamIntegrationStatus.PASS -> ProviderHealth(status = ProviderStatus.ACTIVE)
            CloudStreamIntegrationStatus.PARTIAL -> ProviderHealth(status = ProviderStatus.DEGRADED, errorMessage = metadata.statusReason)
            CloudStreamIntegrationStatus.BLOCKED -> ProviderHealth(status = ProviderStatus.OFFLINE, errorMessage = metadata.statusReason)
            CloudStreamIntegrationStatus.NOT_TESTED -> ProviderHealth(status = ProviderStatus.OFFLINE, errorMessage = "Provider not tested")
        }
    }

    override suspend fun search(query: String): List<MediaItem> = emptyList()

    override suspend fun getDetails(mediaId: String): MediaDetail? = null

    open suspend fun getEpisodes(mediaId: String, season: Int? = null): List<EpisodeItem> = emptyList()

    override suspend fun getStreamItems(
        mediaId: String,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
    ): List<StreamItem> = emptyList()

    open suspend fun getSubtitles(mediaId: String): List<StreamSubtitle> = emptyList()

    open fun reportError(error: Throwable) {
        android.util.Log.w("CloudStreamAdapter", "[$name] Error reported: ${error.message}", error)
    }
}
