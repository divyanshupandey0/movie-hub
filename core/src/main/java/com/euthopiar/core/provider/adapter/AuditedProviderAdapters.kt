package com.euthopiar.core.provider.adapter

import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.network.DohDns
import com.euthopiar.core.provider.ProviderCapabilities
import com.euthopiar.core.provider.ProviderHealth
import com.euthopiar.core.provider.ProviderStatus
import com.euthopiar.core.provider.StreamingProvider
import com.euthopiar.core.streams.StreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Independent runtime configuration and statistics for a streaming provider.
 */
data class ProviderAdapterConfig(
    val providerId: String,
    val displayName: String,
    val timeoutSeconds: Long = 10L,
    val maxRetries: Int = 2,
    val isEnabledByDefault: Boolean = false,
    val requiresAuthentication: Boolean = false,
    val supportsHindiAudio: Boolean = false,
    val authorizationStatus: String = "Audited"
)

/**
 * Performance & reliability statistics tracked per provider.
 */
class ProviderPerformanceStats(val providerId: String) {
    val totalRequests = AtomicInteger(0)
    val successfulRequests = AtomicInteger(0)
    val failedRequests = AtomicInteger(0)
    val cumulativeLatencyMs = AtomicLong(0L)
    @Volatile var lastError: String? = null
    @Volatile var lastHealthCheckMs: Long = 0L

    val averageLatencyMs: Long
        get() {
            val total = totalRequests.get()
            return if (total > 0) cumulativeLatencyMs.get() / total else 0L
        }

    fun recordSuccess(durationMs: Long) {
        totalRequests.incrementAndGet()
        successfulRequests.incrementAndGet()
        cumulativeLatencyMs.addAndGet(durationMs)
        lastHealthCheckMs = System.currentTimeMillis()
    }

    fun recordFailure(durationMs: Long, error: String?) {
        totalRequests.incrementAndGet()
        failedRequests.incrementAndGet()
        cumulativeLatencyMs.addAndGet(durationMs)
        lastError = error
        lastHealthCheckMs = System.currentTimeMillis()
    }
}

/**
 * Centralized Provider Adapter base class guaranteeing isolated failure domains.
 */
abstract class BaseAuditedProviderAdapter(
    val config: ProviderAdapterConfig
) : StreamingProvider {
    override val id: String get() = config.providerId
    override val name: String get() = config.displayName
    override var isEnabled: Boolean = config.isEnabledByDefault

    val stats = ProviderPerformanceStats(id)

    protected val isolatedClient: OkHttpClient by lazy {
        DohDns.metadataClient.newBuilder()
            .connectTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(config.timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}

/**
 * HDHub Provider Adapter (HDhub4u / FourKHDHub).
 *
 * Audit Findings:
 * - Official API: None available. Operates strictly via HTML scraping of pirate mirror domains (4khdhub.dad, hdhub4u).
 * - Authorization & Licensing: Unauthorized distribution of copyrighted theatrical cinema.
 * - Anti-bot: Protected by Cloudflare Turnstile and rotating shortener redirects.
 * - 4K Compliance: Actively targets 4K / 2160p releases (violates Point 10).
 * - Status: Unsupported / Disabled.
 */
class HdHubProviderAdapter : BaseAuditedProviderAdapter(
    ProviderAdapterConfig(
        providerId = "hdhub",
        displayName = "HDHub / HDhub4u",
        timeoutSeconds = 8L,
        isEnabledByDefault = false,
        requiresAuthentication = false,
        supportsHindiAudio = true,
        authorizationStatus = "Unauthorized / No Official API"
    )
) {
    override val description: String = "HDHub scraper (Unsupported: No official API, Cloudflare Turnstile gated)"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.TV_SERIES)
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = false,
        supportsDetails = false,
        supportsCatalog = false,
        supportsDirectPlayback = false,
        supportsSubtitles = false
    )

    override suspend fun checkHealth(): ProviderHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val duration = System.currentTimeMillis() - start
        stats.recordFailure(duration, "No official API endpoint; protected by anti-bot challenge")
        ProviderHealth(
            status = ProviderStatus.OFFLINE,
            latencyMs = duration,
            errorMessage = "Unsupported: HDHub lacks an authorized public API and relies on scraping Cloudflare-protected mirrors."
        )
    }

    override suspend fun search(query: String) = emptyList<com.euthopiar.core.model.MediaItem>()
    override suspend fun getDetails(mediaId: String) = null
    override suspend fun getStreamItems(mediaId: String, season: Int?, episode: Int?, title: String?, year: Int?) = emptyList<StreamItem>()
}

/**
 * NetMirror Provider Adapter.
 *
 * Audit Findings:
 * - Official API: None available. Uses reverse-engineered internal endpoints designed for private mobile clients.
 * - Authorization & Licensing: Unauthorized.
 * - Endpoints: Rotating proxies with proprietary app tokens; frequent HTTP 403 / 502 failures.
 * - Status: Unsupported / Disabled.
 */
class NetMirrorProviderAdapter : BaseAuditedProviderAdapter(
    ProviderAdapterConfig(
        providerId = "netmirror",
        displayName = "NetMirror",
        timeoutSeconds = 8L,
        isEnabledByDefault = false,
        requiresAuthentication = true,
        supportsHindiAudio = true,
        authorizationStatus = "Unauthorized / Private Proxy API"
    )
) {
    override val description: String = "NetMirror (Unsupported: No authorized public API, proprietary headers required)"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.TV_SERIES)
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = false,
        supportsDetails = false,
        supportsCatalog = false,
        supportsDirectPlayback = false,
        supportsSubtitles = false
    )

    override suspend fun checkHealth(): ProviderHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val duration = System.currentTimeMillis() - start
        stats.recordFailure(duration, "Unauthorized private proxy requires proprietary app tokens")
        ProviderHealth(
            status = ProviderStatus.OFFLINE,
            latencyMs = duration,
            errorMessage = "Unsupported: NetMirror has no public authorized API and enforces proprietary client authentication."
        )
    }

    override suspend fun search(query: String) = emptyList<com.euthopiar.core.model.MediaItem>()
    override suspend fun getDetails(mediaId: String) = null
    override suspend fun getStreamItems(mediaId: String, season: Int?, episode: Int?, title: String?, year: Int?) = emptyList<StreamItem>()
}

/**
 * Castle TV Provider Adapter.
 *
 * Audit Findings:
 * - Official API: Exposes handshake endpoint (https://api.hlowb.com/v0.1/system/getSecurityKey/1) which returns HTTP 200 with encrypted security key.
 * - Root Endpoint: Returns HTTP 404 Not Found.
 * - Authorization & Licensing: Unauthorized. Requires private encryption key and proprietary payload deciphering.
 * - Status: Disabled / Restricted.
 */
class CastleTvProviderAdapter : BaseAuditedProviderAdapter(
    ProviderAdapterConfig(
        providerId = "castle_tv",
        displayName = "Castle TV",
        timeoutSeconds = 6L,
        isEnabledByDefault = false,
        requiresAuthentication = true,
        supportsHindiAudio = true,
        authorizationStatus = "Restricted / Proprietary Encryption"
    )
) {
    override val description: String = "Castle TV (Restricted: Root 404, requires proprietary payload deciphering)"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.TV_SERIES)
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = false,
        supportsDetails = false,
        supportsCatalog = false,
        supportsDirectPlayback = false,
        supportsSubtitles = false
    )

    override suspend fun checkHealth(): ProviderHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US")
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                .build()

            isolatedClient.newCall(req).execute().use { response ->
                val duration = System.currentTimeMillis() - start
                if (response.isSuccessful) {
                    stats.recordSuccess(duration)
                    ProviderHealth(
                        status = ProviderStatus.DEGRADED,
                        latencyMs = duration,
                        errorMessage = "Handshake alive (~${duration}ms), but content endpoints require private encryption keys and are unlicensed."
                    )
                } else {
                    stats.recordFailure(duration, "HTTP ${response.code}")
                    ProviderHealth(
                        status = ProviderStatus.OFFLINE,
                        latencyMs = duration,
                        errorMessage = "HTTP ${response.code}"
                    )
                }
            }
        } catch (e: Exception) {
            val duration = System.currentTimeMillis() - start
            stats.recordFailure(duration, e.message)
            ProviderHealth(
                status = ProviderStatus.OFFLINE,
                latencyMs = duration,
                errorMessage = e.message ?: "Connection failed"
            )
        }
    }

    override suspend fun search(query: String) = emptyList<com.euthopiar.core.model.MediaItem>()
    override suspend fun getDetails(mediaId: String) = null
    override suspend fun getStreamItems(mediaId: String, season: Int?, episode: Int?, title: String?, year: Int?) = emptyList<StreamItem>()
}

/**
 * Centralized Provider Adapter Registry.
 * Coordinates independent configs, health monitoring, and performance stats.
 */
object ProviderAdapterRegistry {
    private val adapters = ConcurrentHashMap<String, BaseAuditedProviderAdapter>()

    init {
        registerAdapter(HdHubProviderAdapter())
        registerAdapter(NetMirrorProviderAdapter())
        registerAdapter(CastleTvProviderAdapter())
    }

    fun registerAdapter(adapter: BaseAuditedProviderAdapter) {
        adapters[adapter.id] = adapter
    }

    fun getAdapter(id: String): BaseAuditedProviderAdapter? = adapters[id]

    fun getAllAdapters(): List<BaseAuditedProviderAdapter> = adapters.values.toList()

    suspend fun checkAllHealth(): Map<String, ProviderHealth> = withContext(Dispatchers.IO) {
        adapters.mapValues { (_, adapter) ->
            try {
                adapter.checkHealth()
            } catch (e: Exception) {
                ProviderHealth(ProviderStatus.OFFLINE, errorMessage = e.message)
            }
        }
    }
}
