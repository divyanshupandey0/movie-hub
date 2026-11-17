package com.euthopiar.core.provider.custom

import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.provider.ProviderCapabilities
import com.euthopiar.core.provider.ProviderHealth
import com.euthopiar.core.provider.ProviderStatus
import com.euthopiar.core.provider.StreamingProvider
import com.euthopiar.core.streams.StreamItem
import com.euthopiar.core.streams.StreamSubtitle
import java.util.concurrent.CopyOnWriteArrayList

data class CustomStreamEntry(
    val id: String,
    val title: String,
    val url: String,
    val mediaId: String? = null,
    val container: StreamContainer = StreamContainer.AUTO,
    val quality: String = "Direct",
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<StreamSubtitle> = emptyList()
)

/**
 * Provider supporting user-owned, licensed, and custom HLS, DASH, and direct MP4/WebM streaming URLs.
 * Enables users and external configs to inject custom authorized streaming endpoints with header configuration.
 */
class CustomStreamProvider : StreamingProvider {

    override val id: String = "custom_stream"
    override val name: String = "Custom Stream"
    override val description: String = "User-supplied and licensed direct HLS/DASH/MP4 streams"
    override val isEnabled: Boolean = true
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE, MediaType.TV_SERIES, MediaType.LIVE_TV)
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
        supportsCatalog = false,
        supportsDirectPlayback = true,
        supportsSubtitles = true
    )

    private val customStreams = CopyOnWriteArrayList<CustomStreamEntry>()

    fun registerStream(
        title: String,
        url: String,
        mediaId: String? = null,
        quality: String = "Direct",
        headers: Map<String, String> = emptyMap(),
        subtitles: List<StreamSubtitle> = emptyList(),
        explicitContainer: StreamContainer? = null
    ): CustomStreamEntry {
        val detectedContainer = explicitContainer ?: detectContainer(url)
        val entry = CustomStreamEntry(
            id = "custom_${System.currentTimeMillis()}_${customStreams.size}",
            title = title,
            url = url,
            mediaId = mediaId,
            container = detectedContainer,
            quality = quality,
            headers = headers,
            subtitles = subtitles
        )
        customStreams.add(entry)
        return entry
    }

    fun removeStream(id: String): Boolean {
        return customStreams.removeAll { it.id == id }
    }

    fun clearAll() {
        customStreams.clear()
    }

    fun getAllCustomStreams(): List<CustomStreamEntry> = customStreams.toList()

    override suspend fun checkHealth(): ProviderHealth {
        return ProviderHealth(status = ProviderStatus.ACTIVE, latencyMs = 0L)
    }

    override suspend fun search(query: String): List<MediaItem> {
        val clean = query.trim().lowercase()
        return customStreams.filter { it.title.lowercase().contains(clean) }.map { entry ->
            MediaItem(
                id = "custom:${entry.id}",
                title = entry.title,
                url = entry.url,
                posterUrl = null,
                type = MediaType.MOVIE,
                year = null,
                provider = name,
                quality = entry.quality
            )
        }
    }

    override suspend fun getDetails(mediaId: String): MediaDetail? {
        val customId = mediaId.removePrefix("custom:")
        val entry = customStreams.find { it.id == customId } ?: return null
        return MediaDetail(
            id = mediaId,
            title = entry.title,
            url = entry.url,
            posterUrl = null,
            backdropUrl = null,
            type = MediaType.MOVIE,
            year = null,
            synopsis = "User-supplied direct stream (${entry.container}).",
            provider = name
        )
    }

    override suspend fun getStreamItems(
        mediaId: String,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
    ): List<StreamItem> {
        val matching = customStreams.filter { entry ->
            entry.mediaId == mediaId ||
            (entry.mediaId != null && entry.mediaId.equals(mediaId, ignoreCase = true)) ||
            (!title.isNullOrBlank() && entry.title.contains(title, ignoreCase = true))
        }

        return matching.map { entry ->
            StreamItem(
                name = "${entry.title} [${entry.quality}]",
                title = entry.title,
                description = "User / Licensed Stream (${entry.container})",
                url = entry.url,
                quality = entry.quality,
                addonName = name,
                addonId = id,
                container = entry.container,
                headers = entry.headers,
                subtitles = entry.subtitles,
                matchScore = 1.0
            )
        }
    }

    companion object {
        fun detectContainer(url: String): StreamContainer {
            val clean = url.trim().lowercase().substringBefore('?')
            return when {
                clean.endsWith(".m3u8") || clean.contains(".m3u8") -> StreamContainer.HLS
                clean.endsWith(".mpd") || clean.contains(".mpd") -> StreamContainer.DASH
                clean.endsWith(".mp4") || clean.endsWith(".m4v") -> StreamContainer.MP4
                clean.endsWith(".webm") -> StreamContainer.WEBM
                clean.endsWith(".mkv") -> StreamContainer.MKV
                clean.endsWith(".ts") -> StreamContainer.TS
                else -> StreamContainer.AUTO
            }
        }
    }
}
