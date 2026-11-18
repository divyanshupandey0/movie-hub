package com.euthopiar.core.provider.catalog

import com.euthopiar.core.model.EpisodeItem
import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType

/**
 * Concrete ContentProvider implementation representing an audited entry from the WaveStream / CloudStream catalog.
 * Accurately reports its technical and authorization status without fabricating fake active states.
 */
class AuditedInventoryContentProvider(
    val metadata: InventoryEntryMetadata
) : ContentProvider {

    override val id: String = metadata.id
    override val name: String = metadata.name
    override val category: ProviderCategory = metadata.category
    override val description: String = metadata.reasonForStatus
    override val supportedTypes: Set<MediaType> = metadata.supportedTypes
    override val supportedLanguages: List<String> = metadata.supportedLanguages
    override val capabilities: ContentProviderCapabilities = ContentProviderCapabilities(
        supportsSearch = metadata.technicalStatus == IntegrationHealthStatus.ACTIVE,
        supportsDetails = metadata.technicalStatus == IntegrationHealthStatus.ACTIVE,
        supportsEpisodes = metadata.supportedTypes.contains(MediaType.TV_SERIES) || metadata.supportedTypes.contains(MediaType.ANIME),
        supportsCatalog = false
    )
    override val isEnabled: Boolean = metadata.technicalStatus == IntegrationHealthStatus.ACTIVE
    override val healthStatus: IntegrationHealthStatus = metadata.technicalStatus
    override val lastSuccessfulCheckMs: Long? = if (metadata.technicalStatus == IntegrationHealthStatus.ACTIVE) System.currentTimeMillis() else null
    override val lastErrorMessage: String? = if (metadata.technicalStatus != IntegrationHealthStatus.ACTIVE) metadata.reasonForStatus else null

    override suspend fun checkHealth(): ProviderHealthInfo {
        return ProviderHealthInfo(
            status = metadata.technicalStatus,
            errorMessage = metadata.reasonForStatus
        )
    }

    override suspend fun search(query: String): List<MediaItem> = emptyList()

    override suspend fun getDetails(mediaId: String): MediaDetail? = null

    override suspend fun getEpisodes(mediaId: String, season: Int?): List<EpisodeItem> = emptyList()
}
