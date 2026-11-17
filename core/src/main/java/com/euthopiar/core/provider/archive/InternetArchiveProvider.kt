package com.euthopiar.core.provider.archive

import com.euthopiar.core.model.CatalogRow
import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaItem
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.model.StreamResult
import com.euthopiar.core.model.StreamSource
import com.euthopiar.core.model.UniversalPlugin
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
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Streaming provider for public-domain and Creative Commons content hosted on Internet Archive (archive.org).
 * Uses official Internet Archive JSON APIs for search, metadata, and direct high-performance MP4/WebM video stream extraction.
 */
class InternetArchiveProvider(
    private val client: OkHttpClient = DohDns.metadataClient
) : StreamingProvider, UniversalPlugin {

    override val id: String = "internet_archive"
    override val name: String = "Internet Archive"
    override val description: String = "Official public-domain and Creative Commons catalog from Archive.org"
    override val isEnabled: Boolean = true
    override val mainUrl: String = "https://archive.org"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE)
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
        supportsCatalog = true,
        supportsDirectPlayback = true,
        supportsSubtitles = true
    )

    override suspend fun checkHealth(): ProviderHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("https://archive.org/advancedsearch.php?q=mediatype:(movies)&rows=1&output=json")
                .header("User-Agent", "MOVIE-HUB/2.0.2 (Android)")
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

    override suspend fun getHomeCatalog(): List<CatalogRow> = withContext(Dispatchers.IO) {
        val classicMovies = search("classic film noir comedy sci-fi horror")
        if (classicMovies.isNotEmpty()) {
            listOf(
                CatalogRow(
                    title = "Public Domain Classics (Internet Archive)",
                    subtitle = "Licensed Creative Commons & Public Domain Films",
                    items = classicMovies
                )
            )
        } else {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val encoded = URLEncoder.encode("mediatype:(movies) AND ($cleanQuery)", "UTF-8")
        val searchUrl = "https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title,description,year,downloads,mediatype&sort[]=downloads+desc&rows=25&output=json"

        val results = mutableListOf<MediaItem>()
        try {
            val request = Request.Builder()
                .url(searchUrl)
                .header("User-Agent", "MOVIE-HUB/2.0.2 (Android)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val responseObj = json.optJSONObject("response") ?: return@withContext emptyList()
                val docs = responseObj.optJSONArray("docs") ?: return@withContext emptyList()

                for (i in 0 until docs.length()) {
                    val doc = docs.optJSONObject(i) ?: continue
                    val identifier = doc.optString("identifier")
                    if (identifier.isBlank()) continue

                    val title = doc.optString("title", identifier)
                    val year = doc.optString("year").toIntOrNull()
                    val posterUrl = "https://archive.org/services/img/$identifier"

                    results.add(
                        MediaItem(
                            id = "archive:$identifier",
                            title = title,
                            url = "https://archive.org/details/$identifier",
                            posterUrl = posterUrl,
                            backdropUrl = posterUrl,
                            type = MediaType.MOVIE,
                            year = year,
                            provider = name,
                            quality = "HD",
                            genres = listOf("Public Domain", "Classic")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("InternetArchiveProvider", "Search error: ${e.message}")
        }
        results
    }

    override suspend fun getDetails(mediaId: String): MediaDetail? = withContext(Dispatchers.IO) {
        val identifier = mediaId.removePrefix("archive:").trim()
        if (identifier.isEmpty()) return@withContext null

        val metaUrl = "https://archive.org/metadata/$identifier"
        try {
            val request = Request.Builder()
                .url(metaUrl)
                .header("User-Agent", "MOVIE-HUB/2.0.2 (Android)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val metadata = json.optJSONObject("metadata") ?: return@withContext null

                val title = metadata.optString("title", identifier)
                val description = metadata.optString("description", "")
                val year = metadata.optString("year").toIntOrNull()
                    ?: metadata.optString("date").take(4).toIntOrNull()
                val posterUrl = "https://archive.org/services/img/$identifier"

                MediaDetail(
                    id = "archive:$identifier",
                    title = title,
                    url = "https://archive.org/details/$identifier",
                    posterUrl = posterUrl,
                    backdropUrl = posterUrl,
                    type = MediaType.MOVIE,
                    year = year,
                    synopsis = description,
                    provider = name,
                    genres = listOf("Public Domain", "Internet Archive")
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("InternetArchiveProvider", "Details error: ${e.message}")
            null
        }
    }

    override suspend fun getDetails(mediaItem: MediaItem): MediaDetail {
        return getDetails(mediaItem.id) ?: MediaDetail(
            id = mediaItem.id,
            title = mediaItem.title,
            url = mediaItem.url,
            posterUrl = mediaItem.posterUrl,
            backdropUrl = mediaItem.backdropUrl,
            type = mediaItem.type,
            year = mediaItem.year,
            synopsis = "Public domain movie from Internet Archive.",
            provider = name
        )
    }

    override suspend fun getStreamLinks(episodeData: String): StreamResult {
        val streams = getStreamItems(mediaId = episodeData)
        val sources = streams.map { item ->
            StreamSource(
                url = item.url ?: "",
                serverName = item.addonName,
                resolutionLabel = item.quality ?: "720p",
                quality = item.quality ?: "720p",
                isM3u8 = false,
                container = item.container
            )
        }
        return StreamResult(sources, emptyList())
    }

    override suspend fun getStreamItems(
        mediaId: String,
        season: Int?,
        episode: Int?,
        title: String?,
        year: Int?
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val cleanId = mediaId.removePrefix("archive:").trim()
        val targetIdentifier = if (mediaId.startsWith("archive:")) {
            cleanId
        } else if (!title.isNullOrBlank()) {
            findIdentifierForTitle(title, year)
        } else {
            null
        }

        if (targetIdentifier.isNullOrBlank()) return@withContext emptyList()

        fetchStreamsForIdentifier(targetIdentifier, title, year)
    }

    private suspend fun findIdentifierForTitle(title: String, year: Int?): String? = withContext(Dispatchers.IO) {
        val cleanTitle = title.replace(Regex("""[^a-zA-Z0-9\s]"""), " ").trim()
        val queryStr = if (year != null && year > 1880) {
            "mediatype:(movies) AND ($cleanTitle) AND (year:$year OR date:$year*)"
        } else {
            "mediatype:(movies) AND ($cleanTitle)"
        }
        val encoded = URLEncoder.encode(queryStr, "UTF-8")
        val searchUrl = "https://archive.org/advancedsearch.php?q=$encoded&fl[]=identifier,title,year,downloads&sort[]=downloads+desc&rows=5&output=json"

        try {
            val req = Request.Builder().url(searchUrl).header("User-Agent", "MOVIE-HUB/2.0.2 (Android)").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val docs = json.optJSONObject("response")?.optJSONArray("docs") ?: return@withContext null
                if (docs.length() > 0) {
                    val first = docs.getJSONObject(0)
                    return@withContext first.optString("identifier")
                }
            }
        } catch (_: Exception) {}
        null
    }

    private suspend fun fetchStreamsForIdentifier(
        identifier: String,
        expectedTitle: String?,
        expectedYear: Int?
    ): List<StreamItem> = withContext(Dispatchers.IO) {
        val streams = mutableListOf<StreamItem>()
        val metaUrl = "https://archive.org/metadata/$identifier"

        try {
            val request = Request.Builder()
                .url(metaUrl)
                .header("User-Agent", "MOVIE-HUB/2.0.2 (Android)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return@withContext emptyList()
                val metadata = json.optJSONObject("metadata")
                val itemTitle = metadata?.optString("title") ?: identifier

                // Discover subtitles first
                val subtitles = mutableListOf<StreamSubtitle>()
                for (i in 0 until files.length()) {
                    val fileObj = files.optJSONObject(i) ?: continue
                    val fileName = fileObj.optString("name")
                    if (fileName.endsWith(".srt", ignoreCase = true) || fileName.endsWith(".vtt", ignoreCase = true)) {
                        val subLang = fileObj.optString("title", "English")
                        val encodedSub = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                        subtitles.add(
                            StreamSubtitle(
                                url = "https://archive.org/download/$identifier/$encodedSub",
                                language = subLang,
                                name = fileName
                            )
                        )
                    }
                }

                // Extract playable video files
                for (i in 0 until files.length()) {
                    val fileObj = files.optJSONObject(i) ?: continue
                    val fileName = fileObj.optString("name")
                    val format = fileObj.optString("format", "")

                    val isVideo = fileName.endsWith(".mp4", ignoreCase = true) ||
                            fileName.endsWith(".m4v", ignoreCase = true) ||
                            fileName.endsWith(".webm", ignoreCase = true) ||
                            format.contains("MPEG4", ignoreCase = true) ||
                            format.contains("h.264", ignoreCase = true) ||
                            format.contains("WebM", ignoreCase = true)

                    val isSampleOrThumb = fileName.contains(".sample.", ignoreCase = true) ||
                            fileName.contains("__ia_thumb", ignoreCase = true) ||
                            fileName.contains("_thumb", ignoreCase = true)

                    if (!isVideo || isSampleOrThumb) continue

                    val height = fileObj.optInt("height", 0)
                    val quality = when {
                        height >= 1080 -> "1080p"
                        height >= 720 -> "720p"
                        height >= 480 -> "480p"
                        format.contains("h.264", ignoreCase = true) -> "720p"
                        else -> "Direct MP4"
                    }

                    val sizeBytes = fileObj.optLong("size", 0L)
                    val sizeStr = if (sizeBytes > 0) {
                        String.format("%.1f GB", sizeBytes.toDouble() / (1024 * 1024 * 1024))
                    } else null

                    val encodedFileName = URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
                    val streamUrl = "https://archive.org/download/$identifier/$encodedFileName"
                    val container = if (fileName.endsWith(".webm", ignoreCase = true)) {
                        StreamContainer.WEBM
                    } else {
                        StreamContainer.MP4
                    }

                    streams.add(
                        StreamItem(
                            name = "Internet Archive [$quality]",
                            title = "$itemTitle ($quality)",
                            description = "Official Public Domain Direct Video Stream",
                            url = streamUrl,
                            quality = quality,
                            size = sizeStr,
                            addonName = "Internet Archive",
                            addonId = id,
                            container = container,
                            matchScore = 0.95,
                            subtitles = subtitles
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("InternetArchiveProvider", "Failed to resolve streams: ${e.message}", e)
        }

        // Sort streams: 1080p first, then 720p, 480p
        streams.sortedWith(
            compareByDescending<StreamItem> { it.quality == "1080p" }
                .thenByDescending { it.quality == "720p" }
                .thenByDescending { it.quality == "480p" }
        )
    }
}
