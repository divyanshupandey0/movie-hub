package com.euthopiar.core.provider.media

import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.network.DohDns
import com.euthopiar.core.provider.ProviderCapabilities
import com.euthopiar.core.provider.ProviderHealth
import com.euthopiar.core.provider.ProviderStatus
import com.euthopiar.core.provider.StreamingProvider
import com.euthopiar.core.streams.StreamItem
import com.euthopiar.core.streams.StreamSubtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Provider supporting user-owned personal media servers (Jellyfin / Emby).
 * Enables users to stream their personal licensed library through MOVIE HUB's existing video player
 * via official Jellyfin REST APIs.
 */
class JellyfinMediaServerProvider(
    private val client: OkHttpClient = DohDns.metadataClient
) : StreamingProvider {

    override val id: String = "jellyfin_media_server"
    override val name: String = "Jellyfin Media Server"
    override val description: String = "Authorized personal media server integration (Jellyfin/Emby)"
    override var isEnabled: Boolean = false

    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.TV_SERIES)

    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
        supportsCatalog = false,
        supportsDirectPlayback = true,
        supportsSubtitles = true
    )

    var serverUrl: String = ""
        private set
    var apiKey: String = ""
        private set
    var userId: String = ""
        private set

    fun configure(url: String, key: String, user: String = "") {
        this.serverUrl = url.trim().trimEnd('/')
        this.apiKey = key.trim()
        this.userId = user.trim()
        this.isEnabled = this.serverUrl.isNotBlank() && this.apiKey.isNotBlank()
    }

    override suspend fun checkHealth(): ProviderHealth = withContext(Dispatchers.IO) {
        if (!isEnabled || serverUrl.isBlank()) {
            return@withContext ProviderHealth(
                status = ProviderStatus.DEGRADED,
                errorMessage = "Personal Jellyfin server not configured"
            )
        }
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("$serverUrl/System/Info/Public")
                .header("X-Emby-Token", apiKey)
                .build()
            client.newCall(req).execute().use { response ->
                val latency = System.currentTimeMillis() - start
                if (response.isSuccessful) {
                    ProviderHealth(status = ProviderStatus.ACTIVE, latencyMs = latency)
                } else {
                    ProviderHealth(status = ProviderStatus.DEGRADED, latencyMs = latency, errorMessage = "HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            ProviderHealth(status = ProviderStatus.OFFLINE, latencyMs = System.currentTimeMillis() - start, errorMessage = e.message)
        }
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        if (!isEnabled || serverUrl.isBlank()) return@withContext emptyList()
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val userParam = if (userId.isNotBlank()) "/Users/$userId" else ""
        val url = "$serverUrl$userParam/Items?searchTerm=$encoded&IncludeItemTypes=Movie,Series&Recursive=true&api_key=$apiKey"

        val results = mutableListOf<MediaItem>()
        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val items = json.optJSONArray("Items") ?: return@withContext emptyList()

                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val itemId = item.optString("Id")
                    val title = item.optString("Name", "Untitled")
                    val typeStr = item.optString("Type", "Movie")
                    val year = item.optInt("ProductionYear", 0).takeIf { it > 0 }
                    val posterUrl = "$serverUrl/Items/$itemId/Images/Primary?api_key=$apiKey"

                    results.add(
                        MediaItem(
                            id = "jellyfin:$itemId",
                            title = title,
                            url = "$serverUrl/web/index.html#!/item?id=$itemId",
                            posterUrl = posterUrl,
                            type = if (typeStr.equals("Series", ignoreCase = true)) MediaType.TV_SERIES else MediaType.MOVIE,
                            year = year,
                            provider = name,
                            quality = "Direct"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        results
    }

    override suspend fun getDetails(mediaId: String): MediaDetail? = withContext(Dispatchers.IO) {
        if (!isEnabled || serverUrl.isBlank()) return@withContext null
        val itemId = mediaId.removePrefix("jellyfin:").trim()
        val userParam = if (userId.isNotBlank()) "/Users/$userId" else ""
        val url = "$serverUrl$userParam/Items/$itemId?api_key=$apiKey"

        try {
            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)

                val title = json.optString("Name", itemId)
                val overview = json.optString("Overview", "")
                val year = json.optInt("ProductionYear", 0).takeIf { it > 0 }
                val posterUrl = "$serverUrl/Items/$itemId/Images/Primary?api_key=$apiKey"
                val backdropUrl = "$serverUrl/Items/$itemId/Images/Backdrop?api_key=$apiKey"
                val isSeries = json.optString("Type").equals("Series", ignoreCase = true)

                MediaDetail(
                    id = mediaId,
                    title = title,
                    url = "$serverUrl/web/index.html#!/item?id=$itemId",
                    posterUrl = posterUrl,
                    backdropUrl = backdropUrl,
                    type = if (isSeries) MediaType.TV_SERIES else MediaType.MOVIE,
                    year = year,
                    synopsis = overview,
                    provider = name
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    override suspend fun getStreamItems(
        mediaId: String,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        if (!isEnabled || serverUrl.isBlank()) return@withContext emptyList()
        val itemId = mediaId.removePrefix("jellyfin:").trim()

        val streamUrl = "$serverUrl/Videos/$itemId/stream.mp4?static=true&api_key=$apiKey"
        val hlsUrl = "$serverUrl/Videos/$itemId/master.m3u8?api_key=$apiKey"

        listOf(
            StreamItem(
                name = "Jellyfin Direct [1080p]",
                title = title ?: "Jellyfin Stream",
                description = "Personal Server Direct Stream",
                url = streamUrl,
                quality = "1080p",
                addonName = name,
                addonId = id,
                container = StreamContainer.MP4,
                headers = mapOf("X-Emby-Token" to apiKey),
                matchScore = 1.0
            ),
            StreamItem(
                name = "Jellyfin HLS [Adaptive]",
                title = title ?: "Jellyfin HLS",
                description = "Personal Server HLS Stream",
                url = hlsUrl,
                quality = "Auto",
                addonName = name,
                addonId = id,
                container = StreamContainer.HLS,
                headers = mapOf("X-Emby-Token" to apiKey),
                matchScore = 0.95
            )
        )
    }
}
