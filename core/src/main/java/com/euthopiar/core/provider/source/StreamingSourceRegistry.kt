package com.euthopiar.core.provider.source

import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.provider.catalog.IntegrationHealthStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Dedicated Registry for Playable Streaming Sources.
 *
 * ARCHITECTURAL RULE: Strictly separated from Content Providers.
 * ONLY manages services that resolve authenticated, playable media streams.
 * Catalog-only providers and standalone extractors are strictly barred.
 */
class StreamingSourceRegistry private constructor() {

    private val services = CopyOnWriteArrayList<StreamingSourceService>()
    private val disabledServiceIds = ConcurrentHashMap.newKeySet<String>()

    companion object {
        @Volatile
        private var instance: StreamingSourceRegistry? = null

        fun getInstance(): StreamingSourceRegistry {
            return instance ?: synchronized(this) {
                instance ?: StreamingSourceRegistry().also { instance = it }
            }
        }
    }

    init {
        registerDefaultServices()
    }

    private fun registerDefaultServices() {
        register(PlavioHdSourceService())
        register(TorrentioSourceService())
        register(MediaFusionSourceService())
        register(WikimediaCommonsSourceService())
        register(CustomStreamSourceService())
        register(JellyfinSourceService())
    }

    fun register(service: StreamingSourceService) {
        val idx = services.indexOfFirst { it.id == service.id }
        if (idx >= 0) {
            services[idx] = service
        } else {
            services.add(service)
        }
    }

    fun unregister(id: String) {
        services.removeAll { it.id == id }
    }

    fun getAllServices(): List<StreamingSourceService> = services.toList()

    fun getEnabledServices(): List<StreamingSourceService> {
        return services.filter { it.isEnabled && !disabledServiceIds.contains(it.id) && it.healthStatus == IntegrationHealthStatus.ACTIVE }
    }

    fun getService(id: String): StreamingSourceService? = services.find { it.id == id }

    fun setServiceEnabled(id: String, enabled: Boolean) {
        if (enabled) {
            disabledServiceIds.remove(id)
        } else {
            disabledServiceIds.add(id)
        }
    }

    fun isServiceEnabled(id: String): Boolean {
        val s = getService(id) ?: return false
        return s.isEnabled && !disabledServiceIds.contains(id)
    }

    /**
     * Resolves and aggregates verified playable streams across all active source services
     * with isolated 15s per-service timeouts and strict identity verification.
     */
    suspend fun resolveAllStreams(hints: MatchHints, timeoutMs: Long = 15000L): List<PlayableStream> = withContext(Dispatchers.IO) {
        val initializer = com.euthopiar.core.provider.initialization.BackgroundProviderInitializer.getInstance()
        val allEnabled = getEnabledServices()
        // Prioritize services that are validated as READY by background initializer, or evaluate directly if none yet marked READY
        val activeServices = if (allEnabled.any { initializer.isProviderReady(it.id) }) {
            allEnabled.filter { initializer.isProviderReady(it.id) || initializer.getProviderState(it.id) == com.euthopiar.core.provider.initialization.ProviderLifecycleState.NOT_INITIALIZED }
        } else {
            allEnabled
        }
        coroutineScope {
            val deferred = activeServices.map { service ->
                async {
                    try {
                        withTimeoutOrNull(timeoutMs) {
                            service.resolvePlayableStreams(hints)
                        } ?: emptyList()
                    } catch (t: Throwable) {
                        android.util.Log.e("StreamingSourceRegistry", "Service [${service.name}] failed: ${t.localizedMessage}", t)
                        emptyList()
                    }
                }
            }
            val allResolved = deferred.awaitAll().flatten()
            // Deduplicate streams by playable URL and sort by verification confidence and match score
            allResolved.distinctBy { it.url }
                .sortedWith(
                    compareByDescending<PlayableStream> { it.verificationConfidence == SourceConfidence.VERIFIED }
                        .thenByDescending { it.matchScore }
                )
        }
    }
}
