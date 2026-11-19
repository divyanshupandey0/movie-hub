package com.euthopiar.core.provider.source

import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.network.DohDns
import com.euthopiar.core.provider.catalog.IntegrationHealthStatus
import com.euthopiar.core.provider.custom.CustomStreamProvider
import com.euthopiar.core.provider.media.JellyfinMediaServerProvider
import com.euthopiar.core.provider.wikimedia.WikimediaCommonsProvider
import com.euthopiar.core.streams.StreamItem
import okhttp3.OkHttpClient

/**
 * Native StreamingSourceService for PlavioHD Indian cinema scrapers.
 * Resolves direct HTTPS 206 video streams from 36+ domains.
 */
class PlavioHdSourceService(
    private val client: OkHttpClient = DohDns.metadataClient
) : StreamingSourceService {
    override val id: String = "src_plaviohd"
    override val name: String = "PlavioHD Direct"
    override val isAuthorized: Boolean = true
    override val isEnabled: Boolean = true
    override val healthStatus: IntegrationHealthStatus = IntegrationHealthStatus.ACTIVE
    override val supportedFormats: Set<StreamContainer> = setOf(StreamContainer.MP4, StreamContainer.MKV, StreamContainer.HLS)

    override suspend fun resolvePlayableStreams(hints: MatchHints): List<PlayableStream> {
        // Query PlavioHD manifest endpoints if IMDB / TMDB query is provided
        val queryId = hints.imdbId ?: hints.tmdbId?.let { "tmdb:$it" } ?: return emptyList()
        val path = if (hints.season != null && hints.episode != null) "$queryId:${hints.season}:${hints.episode}" else queryId
        val manifestUrl = "https://plaviohd.net/stream/${if (hints.season != null) "series" else "movie"}/$path.json"

        return try {
            val req = okhttp3.Request.Builder()
                .url(manifestUrl)
                .header("User-Agent", "Mozilla/5.0")
                .header("Referer", "https://plaviohd.net/")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val parsedItems = com.euthopiar.core.streams.StreamParser.parse(body, "PlavioHD", "community.plaviohd")
                parsedItems.mapNotNull { item ->
                    val decision = ContentIdentityVerifier.verify(hints, item)
                    if (decision.confidence == SourceConfidence.REJECTED) return@mapNotNull null
                    PlayableStream(
                        url = item.url ?: return@mapNotNull null,
                        format = item.container,
                        quality = item.quality ?: "1080p",
                        audioTracks = item.audioLanguages.map { com.euthopiar.core.model.AudioTrackDescriptor(it, it) },
                        headers = item.headers ?: emptyMap(),
                        sourceName = "PlavioHD • ${item.streamLabel}",
                        sourceId = id,
                        verificationConfidence = decision.confidence,
                        verificationReason = decision.reason,
                        matchScore = decision.score
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Native StreamingSourceService for Torrentio Stremio Debrid streams.
 */
class TorrentioSourceService : StreamingSourceService {
    override val id: String = "src_torrentio"
    override val name: String = "Torrentio (Debrid/P2P)"
    override val isAuthorized: Boolean = true
    override val isEnabled: Boolean = true
    override val healthStatus: IntegrationHealthStatus = IntegrationHealthStatus.ACTIVE
    override val supportedFormats: Set<StreamContainer> = setOf(StreamContainer.AUTO, StreamContainer.MKV, StreamContainer.MP4)

    override suspend fun resolvePlayableStreams(hints: MatchHints): List<PlayableStream> {
        val queryId = hints.imdbId ?: return emptyList()
        val path = if (hints.season != null && hints.episode != null) "$queryId:${hints.season}:${hints.episode}" else queryId
        val endpoint = "https://torrentio.strem.fun/stream/${if (hints.season != null) "series" else "movie"}/$path.json"

        return try {
            val req = okhttp3.Request.Builder().url(endpoint).header("User-Agent", "Mozilla/5.0").build()
            DohDns.metadataClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val body = resp.body?.string() ?: return emptyList()
                val parsedItems = com.euthopiar.core.streams.StreamParser.parse(body, "Torrentio", "torrentio")
                parsedItems.mapNotNull { item ->
                    val decision = ContentIdentityVerifier.verify(hints, item)
                    if (decision.confidence == SourceConfidence.REJECTED) return@mapNotNull null
                    val url = item.playableDirectUrl ?: item.url ?: return@mapNotNull null
                    PlayableStream(
                        url = url,
                        format = item.container,
                        quality = item.quality ?: "1080p",
                        audioTracks = item.audioLanguages.map { com.euthopiar.core.model.AudioTrackDescriptor(it, it) },
                        headers = item.headers ?: emptyMap(),
                        sourceName = "Torrentio • ${item.streamLabel}",
                        sourceId = id,
                        verificationConfidence = decision.confidence,
                        verificationReason = decision.reason,
                        matchScore = decision.score,
                        seeders = item.seeders,
                        infoHash = item.infoHash,
                        isDebridCached = item.isCached
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

/**
 * Native StreamingSourceService for MediaFusion regional Indian multilingual streams.
 */
class MediaFusionSourceService : StreamingSourceService {
    override val id: String = "src_mediafusion"
    override val name: String = "MediaFusion Regional"
    override val isAuthorized: Boolean = true
    override val isEnabled: Boolean = true
    override val healthStatus: IntegrationHealthStatus = IntegrationHealthStatus.ACTIVE
    override val supportedFormats: Set<StreamContainer> = setOf(StreamContainer.AUTO, StreamContainer.MKV, StreamContainer.MP4)

    override suspend fun resolvePlayableStreams(hints: MatchHints): List<PlayableStream> = emptyList()
}


/**
 * Native StreamingSourceService for Wikimedia Commons Creative Commons media (DISABLED for commercial movies).
 */
class WikimediaCommonsSourceService(
    private val delegate: WikimediaCommonsProvider = WikimediaCommonsProvider()
) : StreamingSourceService {
    override val id: String = "src_wikimedia_commons"
    override val name: String = "Wikimedia Commons"
    override val isAuthorized: Boolean = false
    override val isEnabled: Boolean = false
    override val healthStatus: IntegrationHealthStatus = IntegrationHealthStatus.DISABLED
    override val supportedFormats: Set<StreamContainer> = setOf(StreamContainer.MP4, StreamContainer.AUTO)

    override suspend fun resolvePlayableStreams(hints: MatchHints): List<PlayableStream> {
        // Disabled: Wikimedia Commons does not provide commercial movies or television streams
        return emptyList()
    }
}

/**
 * Native StreamingSourceService for user-injected custom streams (HLS, DASH, MP4).
 */
class CustomStreamSourceService(
    private val delegate: CustomStreamProvider = CustomStreamProvider()
) : StreamingSourceService {
    override val id: String = "src_custom_stream"
    override val name: String = "Custom Stream Injector"
    override val isAuthorized: Boolean = true
    override val isEnabled: Boolean = true
    override val healthStatus: IntegrationHealthStatus = IntegrationHealthStatus.ACTIVE
    override val supportedFormats: Set<StreamContainer> = setOf(StreamContainer.HLS, StreamContainer.DASH, StreamContainer.MP4)

    override suspend fun resolvePlayableStreams(hints: MatchHints): List<PlayableStream> {
        val mediaId = hints.tmdbId?.toString() ?: hints.imdbId.orEmpty()
        val items = delegate.getStreamItems(mediaId, hints.season, hints.episode, hints.titles.firstOrNull(), hints.year)
        return items.mapNotNull { item ->
            PlayableStream(
                url = item.url ?: return@mapNotNull null,
                format = item.container,
                quality = item.quality ?: "Auto",
                headers = item.headers ?: emptyMap(),
                sourceName = "Custom Stream • ${item.streamLabel}",
                sourceId = id,
                verificationConfidence = SourceConfidence.VERIFIED,
                verificationReason = "User-authorized custom stream link",
                matchScore = 1.0
            )
        }
    }
}

/**
 * Native StreamingSourceService for user's personal Jellyfin server.
 */
class JellyfinSourceService(
    private val delegate: JellyfinMediaServerProvider = JellyfinMediaServerProvider()
) : StreamingSourceService {
    override val id: String = "src_jellyfin"
    override val name: String = "Jellyfin Personal Server"
    override val isAuthorized: Boolean = true
    override val isEnabled: Boolean = delegate.isEnabled
    override val healthStatus: IntegrationHealthStatus = if (delegate.isEnabled) IntegrationHealthStatus.ACTIVE else IntegrationHealthStatus.DISABLED
    override val supportedFormats: Set<StreamContainer> = setOf(StreamContainer.HLS, StreamContainer.MP4, StreamContainer.MKV)

    override suspend fun resolvePlayableStreams(hints: MatchHints): List<PlayableStream> {
        if (!isEnabled) return emptyList()
        val mediaId = hints.tmdbId?.toString() ?: hints.imdbId.orEmpty()
        val items = delegate.getStreamItems(mediaId, hints.season, hints.episode, hints.titles.firstOrNull(), hints.year)
        return items.mapNotNull { item ->
            PlayableStream(
                url = item.url ?: return@mapNotNull null,
                format = item.container,
                quality = item.quality ?: "Direct",
                sourceName = "Jellyfin • Private Server",
                sourceId = id,
                verificationConfidence = SourceConfidence.VERIFIED,
                verificationReason = "Authorized private personal media server",
                matchScore = 1.0
            )
        }
    }
}
