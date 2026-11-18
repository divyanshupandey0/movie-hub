package com.euthopiar.core.provider.castle

import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType
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

/**
 * Optional Castle TV provider implementation.
 *
 * Audit Findings & Endpoint Discrepancy Note:
 * - Root Endpoint (https://api.hlowb.com/): Returns HTTP 404 Not Found (path: "/", error: "Not Found")
 *   because the backend service has no root route handler.
 * - Subsystem Handshake Endpoint (https://api.hlowb.com/v0.1/system/getSecurityKey/1?channel=IndiaA&clientType=1&lang=en-US):
 *   Returns HTTP 200 OK (latency ~432ms) with body `{"code":200,"msg":"OK","data":"ZkpBVG0qa2dmSg=="}`.
 * - Health Assessment: The HTTP 200 response merely confirms host reachability for the key exchange
 *   subsystem; it is NOT a working streaming or search API. Content endpoints require private authorization
 *   and proprietary payload decryption.
 * - Compliance: Provider remains disabled by default (`isEnabled = false`) until legitimate API access and
 *   publicly authorized search/extraction are verified.
 */
class CastleTvProvider(
    private val client: OkHttpClient = DohDns.metadataClient
) : StreamingProvider {

    override val id: String = "castle_tv"
    override val name: String = "Castle TV"
    override val description: String = "Castle TV streaming catalog (requires private authorization)"
    
    // Kept disabled by default per project specification until public authorization is granted
    override var isEnabled: Boolean = false

    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.TV_SERIES)

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
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

            client.newCall(req).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (response.isSuccessful) {
                    // Endpoint responds with private encrypted tokens; marked DEGRADED/RESTRICTED
                    ProviderHealth(
                        status = ProviderStatus.DEGRADED,
                        latencyMs = latency,
                        errorMessage = "API active but requires private encryption key and proprietary payload deciphering"
                    )
                } else {
                    ProviderHealth(
                        status = ProviderStatus.OFFLINE,
                        latencyMs = latency,
                        errorMessage = "HTTP ${response.code}"
                    )
                }
            }
        } catch (e: Exception) {
            ProviderHealth(
                status = ProviderStatus.OFFLINE,
                latencyMs = System.currentTimeMillis() - start,
                errorMessage = e.message ?: "Connection failed"
            )
        }
    }

    override suspend fun search(query: String): List<MediaItem> {
        // Disabled / restricted provider does not return fabricated or mock results
        return emptyList()
    }

    override suspend fun getDetails(mediaId: String): MediaDetail? {
        return null
    }

    override suspend fun getStreamItems(
        mediaId: String,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
    ): List<StreamItem> {
        // Does not return fabricated streams when access is unauthorized
        return emptyList()
    }
}
