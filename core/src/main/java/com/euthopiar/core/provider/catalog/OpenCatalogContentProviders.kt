package com.euthopiar.core.provider.catalog

import com.euthopiar.core.model.EpisodeItem
import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.network.DohDns
import com.euthopiar.core.provider.archive.InternetArchiveProvider
import com.euthopiar.core.provider.media.JellyfinMediaServerProvider
import com.euthopiar.core.provider.wikimedia.WikimediaCommonsProvider
import okhttp3.OkHttpClient

/**
 * Native ContentProvider for Internet Archive public domain cinema.
 */
class InternetArchiveContentProvider(
    private val delegate: InternetArchiveProvider = InternetArchiveProvider()
) : ContentProvider {
    override val id: String = "prov_internet_archive"
    override val name: String = "Internet Archive"
    override val category: ProviderCategory = ProviderCategory.OPEN_ACCESS_CATALOG
    override val description: String = "Public domain cinema, cultural film archives, and open media"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE)
    override val supportedLanguages: List<String> = listOf("en", "multi")
    override val capabilities: ContentProviderCapabilities = ContentProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
        supportsEpisodes = false,
        supportsCatalog = true
    )
    override val isEnabled: Boolean = true
    override val healthStatus: IntegrationHealthStatus = IntegrationHealthStatus.ACTIVE
    override val lastSuccessfulCheckMs: Long = System.currentTimeMillis()
    override val lastErrorMessage: String? = null

    override suspend fun checkHealth(): ProviderHealthInfo {
        val h = delegate.checkHealth()
        return ProviderHealthInfo(
            status = if (h.status == com.euthopiar.core.provider.ProviderStatus.ACTIVE) IntegrationHealthStatus.ACTIVE else IntegrationHealthStatus.FAILED,
            latencyMs = h.latencyMs,
            lastCheckTimestampMs = System.currentTimeMillis(),
            errorMessage = h.errorMessage
        )
    }

    override suspend fun search(query: String): List<MediaItem> = delegate.search(query)

    override suspend fun getDetails(mediaId: String): MediaDetail? = delegate.getDetails(mediaId)
}

/**
 * Native ContentProvider for Wikimedia Commons open educational and historical media.
 */
class WikimediaCommonsContentProvider(
    private val delegate: WikimediaCommonsProvider = WikimediaCommonsProvider()
) : ContentProvider {
    override val id: String = "prov_wikimedia_commons"
    override val name: String = "Wikimedia Commons"
    override val category: ProviderCategory = ProviderCategory.OPEN_ACCESS_CATALOG
    override val description: String = "Creative Commons and open-source historical and educational video catalog"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE)
    override val supportedLanguages: List<String> = listOf("en", "multi")
    override val capabilities: ContentProviderCapabilities = ContentProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
        supportsEpisodes = false,
        supportsCatalog = true
    )
    override val isEnabled: Boolean = true
    override val healthStatus: IntegrationHealthStatus = IntegrationHealthStatus.ACTIVE
    override val lastSuccessfulCheckMs: Long = System.currentTimeMillis()
    override val lastErrorMessage: String? = null

    override suspend fun checkHealth(): ProviderHealthInfo {
        val h = delegate.checkHealth()
        return ProviderHealthInfo(
            status = if (h.status == com.euthopiar.core.provider.ProviderStatus.ACTIVE) IntegrationHealthStatus.ACTIVE else IntegrationHealthStatus.FAILED,
            latencyMs = h.latencyMs,
            lastCheckTimestampMs = System.currentTimeMillis(),
            errorMessage = h.errorMessage
        )
    }

    override suspend fun search(query: String): List<MediaItem> = delegate.search(query)

    override suspend fun getDetails(mediaId: String): MediaDetail? = delegate.getDetails(mediaId)
}

/**
 * Native ContentProvider for authorized user-owned Jellyfin personal media servers.
 */
class JellyfinContentProvider(
    private val delegate: JellyfinMediaServerProvider = JellyfinMediaServerProvider()
) : ContentProvider {
    override val id: String = "prov_jellyfin"
    override val name: String = "Jellyfin Media Server"
    override val category: ProviderCategory = ProviderCategory.OPEN_ACCESS_CATALOG
    override val description: String = "User-authorized private media server catalog discovery"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.TV_SERIES)
    override val supportedLanguages: List<String> = listOf("en", "hi", "multi")
    override val capabilities: ContentProviderCapabilities = ContentProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
        supportsEpisodes = true,
        supportsCatalog = true
    )
    override val isEnabled: Boolean = delegate.isEnabled
    override val healthStatus: IntegrationHealthStatus = if (delegate.isEnabled) IntegrationHealthStatus.ACTIVE else IntegrationHealthStatus.DISABLED
    override val lastSuccessfulCheckMs: Long? = if (delegate.isEnabled) System.currentTimeMillis() else null
    override val lastErrorMessage: String? = null

    override suspend fun checkHealth(): ProviderHealthInfo {
        return ProviderHealthInfo(
            status = if (delegate.isEnabled) IntegrationHealthStatus.ACTIVE else IntegrationHealthStatus.DISABLED,
            lastCheckTimestampMs = System.currentTimeMillis()
        )
    }

    override suspend fun search(query: String): List<MediaItem> = delegate.search(query)

    override suspend fun getDetails(mediaId: String): MediaDetail? = delegate.getDetails(mediaId)
}
