package com.euthopiar.core.provider

import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.streams.StreamItem

enum class ProviderStatus {
    ACTIVE,
    DEGRADED,
    OFFLINE
}

data class ProviderHealth(
    val status: ProviderStatus = ProviderStatus.ACTIVE,
    val latencyMs: Long = 0L,
    val errorMessage: String? = null
)

data class ProviderCapabilities(
    val supportsSearch: Boolean = true,
    val supportsDetails: Boolean = true,
    val supportsCatalog: Boolean = false,
    val supportsDirectPlayback: Boolean = true,
    val supportsSubtitles: Boolean = false
)

/**
 * Base contract for modular streaming source providers.
 * Implementations provide discovery, metadata querying, and direct stream resolution.
 */
interface StreamingProvider {
    val id: String
    val name: String
    val description: String
    val isEnabled: Boolean
    val supportedTypes: Set<MediaType>
    val capabilities: ProviderCapabilities

    suspend fun checkHealth(): ProviderHealth = ProviderHealth(status = ProviderStatus.ACTIVE)

    suspend fun search(query: String): List<MediaItem>

    suspend fun getDetails(mediaId: String): MediaDetail?

    suspend fun getStreamItems(
        mediaId: String,
        season: Int? = null,
        episode: Int? = null,
        title: String? = null,
        year: Int? = null
    ): List<StreamItem>
}
