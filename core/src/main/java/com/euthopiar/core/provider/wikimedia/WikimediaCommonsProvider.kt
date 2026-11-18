package com.euthopiar.core.provider.wikimedia

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Streaming provider for open-access, educational, and Creative Commons videos hosted on Wikimedia Commons.
 * Uses official MediaWiki Action APIs for video discovery and stream URL resolution.
 */
class WikimediaCommonsProvider(
    private val client: OkHttpClient = DohDns.metadataClient
) : StreamingProvider, UniversalPlugin {

    override val id: String = "wikimedia_commons"
    override val name: String = "Wikimedia Commons"
    override val description: String = "Open Creative Commons video repository from Wikimedia"
    override val isEnabled: Boolean = true
    override val mainUrl: String = "https://commons.wikimedia.org"
    override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE)
    override val supportedLanguages: List<String> = listOf("en", "hi", "multi")
    override val capabilities: ProviderCapabilities = ProviderCapabilities(
        supportsSearch = true,
        supportsDetails = true,
        supportsCatalog = true,
        supportsDirectPlayback = true,
        supportsSubtitles = false
    )

    override suspend fun checkHealth(): ProviderHealth = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val req = Request.Builder()
                .url("https://commons.wikimedia.org/w/api.php?action=query&meta=siteinfo&format=json")
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
        val natureVideos = search("nature documentary animation open movie")
        if (natureVideos.isNotEmpty()) {
            listOf(
                CatalogRow(
                    title = "Creative Commons Videos (Wikimedia Commons)",
                    subtitle = "Open Source and Educational Video Catalog",
                    items = natureVideos
                )
            )
        } else {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        val encoded = URLEncoder.encode("$cleanQuery filetype:video", "UTF-8")
        val searchUrl = "https://commons.wikimedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srnamespace=6&format=json&srlimit=20"

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
                val queryObj = json.optJSONObject("query") ?: return@withContext emptyList()
                val searchArr = queryObj.optJSONArray("search") ?: return@withContext emptyList()

                for (i in 0 until searchArr.length()) {
                    val item = searchArr.optJSONObject(i) ?: continue
                    val title = item.optString("title")
                    if (title.isBlank()) continue

                    val cleanTitle = title.removePrefix("File:").substringBeforeLast('.')

                    results.add(
                        MediaItem(
                            id = "wikimedia:$title",
                            title = cleanTitle,
                            url = "https://commons.wikimedia.org/wiki/$title",
                            posterUrl = null,
                            type = MediaType.MOVIE,
                            year = null,
                            provider = name,
                            quality = "CC-BY",
                            genres = listOf("Creative Commons", "Wikimedia")
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("WikimediaCommonsProvider", "Search error: ${e.message}")
        }
        results
    }

    override suspend fun getDetails(mediaId: String): MediaDetail? = withContext(Dispatchers.IO) {
        val fileTitle = mediaId.removePrefix("wikimedia:").trim()
        val canonicalTitle = if (fileTitle.startsWith("File:", ignoreCase = true)) fileTitle else "File:$fileTitle"
        val encoded = URLEncoder.encode(canonicalTitle, "UTF-8")
        val url = "https://commons.wikimedia.org/w/api.php?action=query&titles=$encoded&prop=imageinfo&iiprop=url|size|mime|extmetadata&format=json"

        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "MOVIE-HUB/2.0.2 (Android)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext null

                val keys = pages.keys()
                if (!keys.hasNext()) return@withContext null
                val pageObj = pages.getJSONObject(keys.next())
                val imageInfo = pageObj.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@withContext null

                val fileUrl = imageInfo.optString("url")
                val thumbUrl = imageInfo.optString("thumburl", fileUrl)
                val extMeta = imageInfo.optJSONObject("extmetadata")
                val description = extMeta?.optJSONObject("ImageDescription")?.optString("value") ?: "Creative Commons video."

                MediaDetail(
                    id = mediaId,
                    title = canonicalTitle.removePrefix("File:").substringBeforeLast('.'),
                    url = fileUrl,
                    posterUrl = thumbUrl,
                    backdropUrl = thumbUrl,
                    type = MediaType.MOVIE,
                    year = null,
                    synopsis = description,
                    provider = name,
                    genres = listOf("Creative Commons", "Open Video")
                )
            }
        } catch (e: Exception) {
            android.util.Log.w("WikimediaCommonsProvider", "Details error: ${e.message}")
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
            synopsis = "Creative Commons video from Wikimedia Commons.",
            provider = name
        )
    }

    override suspend fun getStreamLinks(episodeData: String): StreamResult {
        val streams = getStreamItems(mediaId = episodeData)
        val sources = streams.map { item ->
            StreamSource(
                url = item.url ?: "",
                serverName = item.addonName,
                resolutionLabel = item.quality ?: "Auto",
                quality = item.quality ?: "Auto",
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
        val fileTitle = when {
            mediaId.startsWith("wikimedia:") -> mediaId.removePrefix("wikimedia:")
            !title.isNullOrBlank() -> searchWikimediaFileTitle(title)
            else -> null
        } ?: return@withContext emptyList()

        val canonicalTitle = if (fileTitle.startsWith("File:", ignoreCase = true)) fileTitle else "File:$fileTitle"
        val encoded = URLEncoder.encode(canonicalTitle, "UTF-8")
        val infoUrl = "https://commons.wikimedia.org/w/api.php?action=query&titles=$encoded&prop=imageinfo&iiprop=url|size|mime|dimensions&format=json"

        try {
            val request = Request.Builder()
                .url(infoUrl)
                .header("User-Agent", "MOVIE-HUB/2.0.2 (Android)")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                val body = response.body?.string() ?: return@withContext emptyList()
                val json = JSONObject(body)
                val pages = json.optJSONObject("query")?.optJSONObject("pages") ?: return@withContext emptyList()
                val keys = pages.keys()
                if (!keys.hasNext()) return@withContext emptyList()

                val page = pages.getJSONObject(keys.next())
                val imageInfo = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@withContext emptyList()
                val directUrl = imageInfo.optString("url")
                if (directUrl.isBlank()) return@withContext emptyList()

                val mime = imageInfo.optString("mime", "")
                val height = imageInfo.optInt("height", 0)
                val quality = when {
                    height >= 1080 -> "1080p"
                    height >= 720 -> "720p"
                    height >= 480 -> "480p"
                    else -> "HD"
                }

                val container = if (directUrl.endsWith(".webm", ignoreCase = true) || mime.contains("webm", ignoreCase = true)) {
                    StreamContainer.WEBM
                } else {
                    StreamContainer.MP4
                }

                val cleanTitle = canonicalTitle.removePrefix("File:").substringBeforeLast('.')
                listOf(
                    StreamItem(
                        name = "Wikimedia Commons [$quality]",
                        title = "$cleanTitle ($quality)",
                        description = "Creative Commons Direct Playback Stream",
                        url = directUrl,
                        quality = quality,
                        addonName = "Wikimedia Commons",
                        addonId = id,
                        container = container,
                        matchScore = 0.90
                    )
                )
            }
        } catch (e: Exception) {
            android.util.Log.e("WikimediaCommonsProvider", "Stream resolution error: ${e.message}", e)
            emptyList()
        }
    }

    private suspend fun searchWikimediaFileTitle(title: String): String? = withContext(Dispatchers.IO) {
        val cleanTitle = title.replace(Regex("""[^a-zA-Z0-9\s]"""), " ").trim()
        val encoded = URLEncoder.encode("$cleanTitle filetype:video", "UTF-8")
        val searchUrl = "https://commons.wikimedia.org/w/api.php?action=query&list=search&srsearch=$encoded&srnamespace=6&format=json&srlimit=1"

        try {
            val req = Request.Builder().url(searchUrl).header("User-Agent", "MOVIE-HUB/2.0.2 (Android)").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val body = resp.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                val searchArr = json.optJSONObject("query")?.optJSONArray("search") ?: return@withContext null
                if (searchArr.length() > 0) {
                    return@withContext searchArr.getJSONObject(0).optString("title")
                }
            }
        } catch (_: Exception) {}
        null
    }
}
