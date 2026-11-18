package com.euthopiar.core.provider

import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.provider.archive.InternetArchiveProvider
import com.euthopiar.core.provider.custom.CustomStreamProvider
import com.euthopiar.core.provider.metadata.StreamingMetadataManager
import com.euthopiar.core.provider.wikimedia.WikimediaCommonsProvider
import com.euthopiar.core.registry.PluginRegistry
import com.euthopiar.core.streams.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-performance, modular streaming source manager.
 * Coordinates authorized, public-domain, creative commons, and custom streaming providers.
 * Manages source discovery, health probing, multi-provider search, stream resolution, and automatic fallback.
 */
class StreamingSourceManager private constructor() {

    private val providers = CopyOnWriteArrayList<StreamingProvider>()
    private val disabledProviderIds = ConcurrentHashMap.newKeySet<String>()
    private val statsMap = ConcurrentHashMap<String, ProviderStats>()

    val metadataManager: StreamingMetadataManager by lazy { StreamingMetadataManager.getInstance() }
    val internetArchiveProvider: InternetArchiveProvider by lazy { InternetArchiveProvider() }
    val wikimediaCommonsProvider: WikimediaCommonsProvider by lazy { WikimediaCommonsProvider() }
    val customStreamProvider: CustomStreamProvider by lazy { CustomStreamProvider() }
    val castleTvProvider: com.euthopiar.core.provider.castle.CastleTvProvider by lazy { com.euthopiar.core.provider.castle.CastleTvProvider() }
    val jellyfinProvider: com.euthopiar.core.provider.media.JellyfinMediaServerProvider by lazy { com.euthopiar.core.provider.media.JellyfinMediaServerProvider() }

    init {
        // Register default official and authorized streaming source providers
        registerProvider(internetArchiveProvider)
        registerProvider(wikimediaCommonsProvider)
        registerProvider(customStreamProvider)
        registerProvider(castleTvProvider)
        registerProvider(jellyfinProvider)

        // Also register with UniversalPlugin registry for catalog and search visibility
        PluginRegistry.Default.registerPlugin(internetArchiveProvider)
        PluginRegistry.Default.registerPlugin(wikimediaCommonsProvider)
    }

    /**
     * Reference to the dedicated Content Providers system (Catalog, Search, Details).
     */
    val contentProviderRegistry: com.euthopiar.core.provider.catalog.ContentProviderRegistry
        get() = com.euthopiar.core.provider.catalog.ContentProviderRegistry.getInstance()

    /**
     * Reference to the dedicated Streaming Sources system (Playable Streams).
     */
    val streamingSourceRegistry: com.euthopiar.core.provider.source.StreamingSourceRegistry
        get() = com.euthopiar.core.provider.source.StreamingSourceRegistry.getInstance()

    fun registerProvider(provider: StreamingProvider) {
        val existingIndex = providers.indexOfFirst { it.id == provider.id }
        if (existingIndex >= 0) {
            providers[existingIndex] = provider
        } else {
            providers.add(provider)
        }
    }

    fun unregisterProvider(id: String) {
        providers.removeAll { it.id == id }
    }

    fun getProviders(): List<StreamingProvider> = providers.toList()

    fun getEnabledProviders(): List<StreamingProvider> {
        return providers.filter { it.isEnabled && !disabledProviderIds.contains(it.id) }
    }

    fun isProviderEnabled(id: String): Boolean {
        val p = providers.find { it.id == id } ?: return false
        return p.isEnabled && !disabledProviderIds.contains(id)
    }

    fun setProviderEnabled(id: String, enabled: Boolean) {
        if (enabled) {
            disabledProviderIds.remove(id)
        } else {
            disabledProviderIds.add(id)
        }
    }

    fun recordRequestOutcome(
        providerId: String,
        durationMs: Long,
        isSuccess: Boolean,
        errorMessage: String? = null
    ) {
        val provider = providers.find { it.id == providerId }
        val name = provider?.name ?: providerId
        val languages = provider?.supportedLanguages ?: listOf("en")
        val enabled = isProviderEnabled(providerId)

        statsMap.compute(providerId) { _, current ->
            val prev = current ?: ProviderStats(
                providerId = providerId,
                name = name,
                enabled = enabled,
                supportedLanguages = languages
            )
            val total = prev.totalRequests + 1
            val successful = if (isSuccess) prev.successfulRequests + 1 else prev.successfulRequests
            val newAvg = if (prev.totalRequests == 0L) durationMs else ((prev.averageResponseTimeMs * prev.totalRequests) + durationMs) / total
            val newHealth = when {
                !isSuccess && errorMessage != null -> ProviderHealth(status = ProviderStatus.DEGRADED, latencyMs = durationMs, errorMessage = errorMessage)
                isSuccess -> ProviderHealth(status = ProviderStatus.ACTIVE, latencyMs = durationMs)
                else -> prev.health
            }
            prev.copy(
                enabled = enabled,
                health = newHealth,
                lastSuccessfulResponseMs = if (isSuccess) System.currentTimeMillis() else prev.lastSuccessfulResponseMs,
                averageResponseTimeMs = newAvg,
                totalRequests = total,
                successfulRequests = successful,
                lastError = if (!isSuccess) errorMessage ?: "Request failed" else prev.lastError
            )
        }
    }

    fun getProviderStats(providerId: String): ProviderStats {
        return statsMap[providerId] ?: run {
            val provider = providers.find { it.id == providerId }
            ProviderStats(
                providerId = providerId,
                name = provider?.name ?: providerId,
                enabled = isProviderEnabled(providerId),
                supportedLanguages = provider?.supportedLanguages ?: listOf("en")
            )
        }
    }

    fun getAllProviderStats(): List<ProviderStats> {
        return providers.map { getProviderStats(it.id) }
    }

    /**
     * Probes all registered providers for connectivity and health status.
     */
    suspend fun discoverAndCheckProviders(): Map<String, ProviderHealth> = withContext(Dispatchers.IO) {
        coroutineScope {
            providers.map { provider ->
                async {
                    val health = withTimeoutOrNull(8000L) {
                        provider.checkHealth()
                    } ?: ProviderHealth(status = ProviderStatus.OFFLINE, errorMessage = "Timeout")
                    provider.id to health
                }
            }.awaitAll().toMap()
        }
    }

    /**
     * Executes federated search across all enabled providers.
     */
    suspend fun searchAll(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val activeProviders = getEnabledProviders().filter { it.capabilities.supportsSearch }
        coroutineScope {
            activeProviders.map { provider ->
                async {
                    runCatching {
                        withTimeoutOrNull(10_000L) {
                            provider.search(cleanQuery)
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten().distinctBy { it.id }
        }
    }

    /**
     * Concurrently queries all enabled providers for playable streams.
     */
    suspend fun resolveStreams(
        mediaId: String,
        normType: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        imdbId: String? = null
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val activeProviders = getEnabledProviders()
        coroutineScope {
            activeProviders.map { provider ->
                async {
                    runCatching {
                        withTimeoutOrNull(15_000L) {
                            provider.getStreamItems(
                                mediaId = mediaId,
                                season = season,
                                episode = episode,
                                title = title,
                                year = year
                            )
                        } ?: emptyList()
                    }.getOrDefault(emptyList())
                }
            }.awaitAll().flatten()
        }
    }

    /**
     * Selects the highest quality, most reliable stream for immediate playback.
     * Strictly requires a verified match score >= 0.60 to prevent wrong movie/episode playback.
     * Returns null if no verified stream exists.
     */
    fun selectBestStream(streams: List<StreamItem>): StreamItem? {
        val eligible = streams.filter { stream ->
            stream.playableDirectUrl != null && stream.matchScore >= 0.60
        }
        if (eligible.isEmpty()) return null

        return eligible.maxWithOrNull(
            compareBy<StreamItem> { stream ->
                when (stream.quality) {
                    "2160p", "4K" -> 100
                    "1080p", "FHD" -> 90
                    "720p", "HD" -> 80
                    "480p", "SD" -> 60
                    else -> 50
                }
            }.thenBy { stream ->
                when (stream.container) {
                    StreamContainer.HLS -> 20
                    StreamContainer.DASH -> 18
                    StreamContainer.MP4 -> 15
                    StreamContainer.WEBM -> 12
                    else -> 10
                }
            }.thenBy { stream ->
                stream.matchScore
            }
        )
    }

    /**
     * Automatic fallback: returns the next best candidate stream excluding failed URLs.
     */
    fun getNextFallbackStream(failedUrl: String, allStreams: List<StreamItem>): StreamItem? {
        val candidates = allStreams.filter { it.playableDirectUrl != null && it.playableDirectUrl != failedUrl }
        return selectBestStream(candidates)
    }

    companion object {
        @Volatile
        private var instance: StreamingSourceManager? = null

        fun getInstance(): StreamingSourceManager {
            return instance ?: synchronized(this) {
                instance ?: StreamingSourceManager().also { instance = it }
            }
        }
    }
}
