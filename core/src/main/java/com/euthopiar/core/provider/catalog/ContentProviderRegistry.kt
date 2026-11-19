package com.euthopiar.core.provider.catalog

import com.euthopiar.core.model.EpisodeItem
import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Registry and manager for all Content Discovery Providers.
 * Strictly decoupled from the Streaming Sources Manager.
 */
class ContentProviderRegistry private constructor() {

    private val providers = CopyOnWriteArrayList<ContentProvider>()
    private val disabledProviderIds = ConcurrentHashMap.newKeySet<String>()
    private val healthCache = ConcurrentHashMap<String, ProviderHealthInfo>()

    companion object {
        @Volatile
        private var instance: ContentProviderRegistry? = null

        fun getInstance(): ContentProviderRegistry {
            return instance ?: synchronized(this) {
                instance ?: ContentProviderRegistry().also { instance = it }
            }
        }
    }

    init {
        registerAuditedProviders()
    }

    private fun registerAuditedProviders() {
        // Register open-access and public domain catalog providers
        register(WikimediaCommonsContentProvider())
        register(JellyfinContentProvider())

        // Register audited WaveStream inventory reference entries with their verified categories and health statuses
        AuditedInventoryCatalog.ALL_ENTRIES.forEach { meta ->
            register(AuditedInventoryContentProvider(meta))
        }
    }

    fun register(provider: ContentProvider) {
        val existing = providers.indexOfFirst { it.id == provider.id }
        if (existing >= 0) {
            providers[existing] = provider
        } else {
            providers.add(provider)
        }
    }

    fun unregister(id: String) {
        providers.removeAll { it.id == id }
    }

    fun getAllProviders(): List<ContentProvider> = providers.toList()

    fun getProvidersByCategory(category: ProviderCategory): List<ContentProvider> {
        return providers.filter { it.category == category }
    }

    fun getEnabledProviders(): List<ContentProvider> {
        return providers.filter { it.isEnabled && !disabledProviderIds.contains(it.id) && it.healthStatus == IntegrationHealthStatus.ACTIVE }
    }

    fun getProvider(id: String): ContentProvider? = providers.find { it.id == id }

    fun setProviderEnabled(id: String, enabled: Boolean) {
        if (enabled) {
            disabledProviderIds.remove(id)
        } else {
            disabledProviderIds.add(id)
        }
    }

    fun isProviderEnabled(id: String): Boolean {
        val p = getProvider(id) ?: return false
        return p.isEnabled && !disabledProviderIds.contains(id)
    }

    /**
     * Executes federated search across all active, enabled discovery providers with timeout protection.
     */
    suspend fun searchAll(query: String, timeoutMs: Long = 8000L): List<MediaItem> = withContext(Dispatchers.IO) {
        val initializer = com.euthopiar.core.provider.initialization.BackgroundProviderInitializer.getInstance()
        val allEnabled = getEnabledProviders().filter { it.capabilities.supportsSearch }
        val activeProviders = if (allEnabled.any { initializer.isProviderReady(it.id) }) {
            allEnabled.filter { initializer.isProviderReady(it.id) || initializer.getProviderState(it.id) == com.euthopiar.core.provider.initialization.ProviderLifecycleState.NOT_INITIALIZED }
        } else {
            allEnabled
        }
        coroutineScope {
            val deferred = activeProviders.map { provider ->
                async {
                    try {
                        withTimeoutOrNull(timeoutMs) {
                            provider.search(query)
                        } ?: emptyList()
                    } catch (_: Throwable) {
                        emptyList()
                    }
                }
            }
            deferred.awaitAll().flatten().distinctBy { it.id }
        }
    }

    /**
     * Resolves media details from a specific content provider.
     */
    suspend fun getDetails(providerId: String, mediaId: String): MediaDetail? = withContext(Dispatchers.IO) {
        val provider = getProvider(providerId) ?: return@withContext null
        try {
            provider.getDetails(mediaId)
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Resolves series episode listing from a specific content provider.
     */
    suspend fun getEpisodes(providerId: String, mediaId: String, season: Int? = null): List<EpisodeItem> = withContext(Dispatchers.IO) {
        val provider = getProvider(providerId) ?: return@withContext emptyList()
        try {
            provider.getEpisodes(mediaId, season)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Checks health across all providers and updates internal health cache.
     */
    suspend fun checkAllHealth(): Map<String, ProviderHealthInfo> = withContext(Dispatchers.IO) {
        coroutineScope {
            val deferred = providers.map { provider ->
                async {
                    val health = try {
                        withTimeoutOrNull(5000L) {
                            provider.checkHealth()
                        } ?: ProviderHealthInfo(
                            status = IntegrationHealthStatus.UNAVAILABLE,
                            errorMessage = "Health check timed out (5s)"
                        )
                    } catch (t: Throwable) {
                        ProviderHealthInfo(
                            status = IntegrationHealthStatus.FAILED,
                            errorMessage = t.localizedMessage
                        )
                    }
                    healthCache[provider.id] = health
                    provider.id to health
                }
            }
            deferred.awaitAll().toMap()
        }
    }

    fun getCachedHealth(providerId: String): ProviderHealthInfo? = healthCache[providerId]
}
