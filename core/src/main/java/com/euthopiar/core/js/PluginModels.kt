package com.euthopiar.core.js

import com.euthopiar.core.model.DrmConfig
import com.euthopiar.core.model.PluginManifest
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.model.StreamSource
import com.euthopiar.core.model.SubtitleTrack
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildClassSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

object StringListFlexibleSerializer : KSerializer<List<String>> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("StringListFlexible")

    override fun serialize(encoder: Encoder, value: List<String>) {
        encoder.encodeSerializableValue(ListSerializer(String.serializer()), value)
    }

    override fun deserialize(decoder: Decoder): List<String> {
        val input = decoder as? JsonDecoder ?: return emptyList()
        return when (val element = input.decodeJsonElement()) {
            is JsonArray -> {
                element.mapNotNull { item ->
                    (item as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
                }
            }
            is JsonPrimitive -> {
                val str = element.contentOrNull?.trim().orEmpty()
                if (str.isBlank() || str.equals("null", ignoreCase = true)) {
                    emptyList()
                } else {
                    str.split(Regex("""[\s,]+""")).map { it.trim() }.filter { it.isNotBlank() }
                }
            }
            else -> emptyList()
        }
    }
}

@Serializable(with = JsRepoManifestSerializer::class)
data class JsRepoManifest(
    val name: String = "",
    val version: String = "1.0.0",
    val description: String? = null,
    val author: String? = null,
    val scrapers: List<JsScraperMeta> = emptyList()
)

object JsRepoManifestSerializer : KSerializer<JsRepoManifest> {
    override val descriptor: SerialDescriptor =
        buildClassSerialDescriptor("JsRepoManifest")

    override fun serialize(encoder: Encoder, value: JsRepoManifest) {
        val jsonEncoder = encoder as? JsonEncoder
        if (jsonEncoder != null) {
            val jsonObject = buildJsonObject {
                put("name", JsonPrimitive(value.name))
                put("version", JsonPrimitive(value.version))
                value.description?.let { put("description", JsonPrimitive(it)) }
                value.author?.let { put("author", JsonPrimitive(it)) }
                put("scrapers", jsonEncoder.json.encodeToJsonElement(ListSerializer(JsScraperMeta.serializer()), value.scrapers))
            }
            jsonEncoder.encodeJsonElement(jsonObject)
        }
    }

    override fun deserialize(decoder: Decoder): JsRepoManifest {
        val input = decoder as? JsonDecoder ?: return JsRepoManifest()
        val root = runCatching { input.decodeJsonElement().jsonObject }.getOrNull() ?: return JsRepoManifest()

        val rawName = root["name"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: root["title"]?.jsonPrimitive?.contentOrNull?.trim()
            ?: ""
        val version = root["version"]?.jsonPrimitive?.contentOrNull?.trim() ?: "1.0.0"
        val description = root["description"]?.jsonPrimitive?.contentOrNull?.trim()
        val author = root["author"]?.jsonPrimitive?.contentOrNull?.trim()

        val scrapersElement = root["scrapers"] ?: root["plugins"] ?: root["providers"]
        val scrapers = if (scrapersElement != null && scrapersElement is JsonArray) {
            scrapersElement.mapNotNull { item ->
                try {
                    input.json.decodeFromJsonElement(JsScraperMeta.serializer(), item)
                } catch (_: Exception) {
                    null
                }
            }
        } else emptyList()

        return JsRepoManifest(
            name = rawName,
            version = version,
            description = description,
            author = author,
            scrapers = scrapers
        )
    }
}

@Serializable
data class JsScraperMeta(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val version: String = "1.0.0",
    val filename: String = "",
    @Serializable(with = StringListFlexibleSerializer::class)
    @SerialName("supportedTypes") val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    @Serializable(with = StringListFlexibleSerializer::class)
    @SerialName("contentLanguage") val contentLanguage: List<String>? = null,
    @Serializable(with = StringListFlexibleSerializer::class)
    val formats: List<String>? = null
)

fun inferRepoNameFromUrl(url: String): String {
    val clean = url.trim()
    if (clean.isBlank()) return "Repository"

    if (clean.contains("All-in-One-Nuvio", ignoreCase = true)) return "All-in-One-Nuvio"
    if (clean.contains("nuvio-providers", ignoreCase = true)) return "Yoru's Repo"

    val githubRegex = Regex("""github(?:usercontent)?\.com/([^/]+)/([^/]+)""", RegexOption.IGNORE_CASE)
    val ghMatch = githubRegex.find(clean)
    if (ghMatch != null) {
        val repo = ghMatch.groupValues[2]
        if (repo.isNotBlank() && !repo.equals("manifest", ignoreCase = true) && !repo.equals("manifest.json", ignoreCase = true)) {
            return repo
        }
    }

    val segments = clean.removeSuffix("/").split("/").filter { seg ->
        val s = seg.lowercase().substringBefore(".json").trim()
        s.isNotBlank() && s !in setOf("manifest", "main", "master", "heads", "refs", "raw", "blob", "raw.githubusercontent.com", "github.com")
    }
    val candidate = segments.lastOrNull()?.substringBefore(".json")?.trim()
    if (!candidate.isNullOrBlank()) {
        return candidate.replace('-', ' ').replace('_', ' ').split(" ").joinToString(" ") {
            it.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
        }
    }

    return "Repository"
}

fun computeCanonicalRepoId(repoUrl: String): String {
    val trimmed = repoUrl.trim()
    if (trimmed.isBlank() || trimmed.equals("local", ignoreCase = true)) return "local"
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val digest = md.digest(trimmed.toByteArray(Charsets.UTF_8))
    return "repo_" + digest.take(16).joinToString("") { "%02x".format(it) }
}

@Serializable
data class PluginScraper(
    val id: String,
    val repositoryUrl: String = "",
    val name: String,
    val description: String = "",
    val version: String = "1.0.0",
    val filename: String = "",
    @Serializable(with = StringListFlexibleSerializer::class)
    val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    @Serializable(with = StringListFlexibleSerializer::class)
    val formats: List<String>? = null,
    val code: String = "",
    val repoId: String = ""
) {
    val canonicalRepoId: String
        get() = if (repoId.isNotBlank()) repoId else computeCanonicalRepoId(repositoryUrl)

    fun supportsType(type: String): Boolean {
        if (supportedTypes.isNullOrEmpty()) return true
        val normType = normalizePluginType(type)
        return supportedTypes.any { raw ->
            val norm = normalizePluginType(raw)
            norm == normType || norm == "all" ||
                (normType == "anime" && (norm == "tv" || norm == "movie" || norm == "anime"))
        }
    }
}

fun normalizePluginType(value: String): String =
    when (value.lowercase().trim()) {
        "series", "show", "other", "tv_series" -> "tv"
        "movies" -> "movie"
        "anime" -> "anime"
        else -> value.lowercase().trim()
    }

fun pluginContentId(
    videoId: String,
    season: Int? = null,
    episode: Int? = null,
): String {
    val trimmed = videoId.trim()
    if (trimmed.isBlank()) return videoId

    val withoutPrefix = when {
        trimmed.startsWith("tmdb:") -> trimmed.removePrefix("tmdb:")
        trimmed.startsWith("tmdb/") -> trimmed.removePrefix("tmdb/")
        else -> trimmed
    }

    val withoutEpisodeSuffix = if (season != null && episode != null) {
        withoutPrefix.removeSuffix(":$season:$episode")
    } else {
        withoutPrefix
    }

    return withoutEpisodeSuffix.substringBefore('/').ifBlank { trimmed }
}

fun PluginScraper.toPluginManifest(): PluginManifest {
    val isAdult = supportedTypes.any { it.equals("adult", ignoreCase = true) || it.equals("vault", ignoreCase = true) }
    return PluginManifest(
        id = id,
        name = name,
        version = 1,
        versionName = version,
        author = "Universal/Nuvio",
        siteUrl = repositoryUrl,
        description = description,
        iconUrl = logo,
        className = "js:$id",
        downloadUrl = filename,
        isBuiltIn = false,
        isEnabled = enabled,
        supportedTypes = supportedTypes,
        isAdult = isAdult,
        isVault = false
    )
}

fun JsScraperMeta.toPluginManifest(repoUrl: String): PluginManifest {
    val isAdult = supportedTypes.any { it.equals("adult", ignoreCase = true) || it.equals("vault", ignoreCase = true) }
    val fullDownloadUrl = if (filename.startsWith("http")) filename else {
        repoUrl.substringBeforeLast("/") + "/" + filename
    }
    return PluginManifest(
        id = id,
        name = name,
        version = 1,
        versionName = version,
        author = "Universal/Nuvio",
        siteUrl = repoUrl,
        description = description ?: "",
        iconUrl = logo,
        className = "js:$id",
        downloadUrl = fullDownloadUrl,
        isBuiltIn = false,
        isEnabled = enabled,
        supportedTypes = supportedTypes,
        isAdult = isAdult,
        isVault = false
    )
}

@Serializable
data class PluginSubtitleResult(
    val url: String,
    val language: String,
    val name: String? = null,
    val headers: Map<String, String>? = null,
    val mimeType: String? = null
)

@Serializable
data class PluginRuntimeResult(
    val title: String,
    val name: String? = null,
    val url: String,
    val quality: String? = null,
    val size: String? = null,
    val language: String? = null,
    val provider: String? = null,
    val type: String? = null,
    val seeders: Int? = null,
    val peers: Int? = null,
    val infoHash: String? = null,
    val fileIdx: Int? = null,
    val headers: Map<String, String>? = null,
    val subtitles: List<PluginSubtitleResult>? = null,
    val format: String? = null,
    val trackers: List<String>? = null,
    val drm: DrmConfig? = null,
    val manifestContent: String? = null,
    val rawTitle: String? = null
) {
    fun inferContainer(scraperFormats: List<String>? = null): StreamContainer {
        val explicit = (format ?: type)?.lowercase()?.trim()
        if (explicit != null) {
            when {
                explicit == "mpd" || explicit == "dash" || explicit.contains("dash") -> return StreamContainer.DASH
                explicit == "m3u8" || explicit == "hls" || explicit.contains("hls") -> return StreamContainer.HLS
                explicit == "mp4" || explicit.contains("mp4") -> return StreamContainer.MP4
                explicit == "mkv" || explicit.contains("matroska") -> return StreamContainer.MKV
                explicit == "webm" -> return StreamContainer.WEBM
                explicit == "ts" -> return StreamContainer.TS
                explicit == "torrent" || explicit == "magnet" || explicit == "p2p" -> return StreamContainer.P2P
            }
        }
        val cleanUrl = url.trim().lowercase()
        if (cleanUrl.startsWith("magnet:") || cleanUrl.startsWith("torrent:") || !infoHash.isNullOrBlank()) {
            return StreamContainer.P2P
        }
        val path = cleanUrl.substringBefore('?').substringBefore('#')
        val urlContainer = when {
            path.endsWith(".m3u8") || cleanUrl.contains("format=m3u8") -> StreamContainer.HLS
            path.endsWith(".mpd") || cleanUrl.contains("format=mpd") -> StreamContainer.DASH
            path.endsWith(".mp4") -> StreamContainer.MP4
            path.endsWith(".mkv") -> StreamContainer.MKV
            path.endsWith(".webm") -> StreamContainer.WEBM
            path.endsWith(".ts") -> StreamContainer.TS
            else -> StreamContainer.AUTO
        }
        if (urlContainer != StreamContainer.AUTO) {
            return urlContainer
        }

        // Default to StreamContainer.HLS if scraper manifest declares ["m3u8"] or HLS
        if (scraperFormats != null && scraperFormats.isNotEmpty()) {
            val lowerFormats = scraperFormats.map { it.lowercase().trim() }
            if (lowerFormats.all { it == "m3u8" || it == "hls" }) {
                return StreamContainer.HLS
            }
            if (lowerFormats.all { it == "mpd" || it == "dash" }) {
                return StreamContainer.DASH
            }
        }

        return StreamContainer.AUTO
    }

    fun toStreamSource(scraperFormats: List<String>? = null): StreamSource {
        val detectedContainer = inferContainer(scraperFormats)
        val isM3u8 = detectedContainer == StreamContainer.HLS ||
                type?.equals("m3u8", ignoreCase = true) == true ||
                url.contains(".m3u8", ignoreCase = true) ||
                url.contains("hls", ignoreCase = true)
        val cleanServerName = name ?: provider ?: "Server"
        val qualityLabel = quality ?: "Auto"
        return StreamSource(
            url = url,
            serverName = cleanServerName,
            resolutionLabel = qualityLabel,
            quality = qualityLabel,
            isM3u8 = isM3u8,
            headers = headers ?: emptyMap(),
            infoHash = infoHash,
            fileIdx = fileIdx,
            seeders = seeders,
            subtitles = toSubtitles(),
            size = size,
            container = detectedContainer,
            drm = drm,
            trackers = trackers ?: emptyList(),
            manifestContent = manifestContent
        )
    }

    fun toSubtitles(): List<SubtitleTrack> {
        return subtitles?.filter { sub ->
            val u = sub.url.trim().lowercase()
            u.startsWith("http://") || u.startsWith("https://") || u.startsWith("data:")
        }?.map { sub ->
            SubtitleTrack(
                url = sub.url.trim(),
                language = sub.language.trim(),
                headers = sub.headers ?: headers ?: emptyMap(),
                name = sub.name?.trim() ?: sub.language.trim(),
                mimeType = sub.mimeType?.trim()
            )
        } ?: emptyList()
    }
}

/**
 * Compares two plugin version strings.
 * Supports Semantic Versioning (e.g. "1.2.3", "v1.2", "2.0.0-beta"),
 * integer versions ("2"), and pre-release suffix precedence.
 */
fun isPluginVersionNewer(currentVersion: String, remoteVersion: String): Boolean {
    if (currentVersion.isBlank() && remoteVersion.isNotBlank()) return true
    if (remoteVersion.isBlank()) return false

    val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V").trim()
    val cleanRemote = remoteVersion.trim().removePrefix("v").removePrefix("V").trim()

    val currentCore = cleanCurrent.substringBefore("-").substringBefore("+")
    val remoteCore = cleanRemote.substringBefore("-").substringBefore("+")

    val currentParts = currentCore.split(".").mapNotNull { it.trim().toIntOrNull() }
    val remoteParts = remoteCore.split(".").mapNotNull { it.trim().toIntOrNull() }

    val maxLen = maxOf(currentParts.size, remoteParts.size)
    for (i in 0 until maxLen) {
        val curr = currentParts.getOrElse(i) { 0 }
        val rem = remoteParts.getOrElse(i) { 0 }
        if (rem > curr) return true
        if (rem < curr) return false
    }

    val currentHasPreRelease = cleanCurrent.contains("-") && !cleanCurrent.contains("-stable", ignoreCase = true)
    val remoteHasPreRelease = cleanRemote.contains("-") && !cleanRemote.contains("-stable", ignoreCase = true)

    if (currentHasPreRelease && !remoteHasPreRelease) {
        return true
    }

    return false
}

/**
 * Validates that downloaded scraper code is non-empty and is not an HTML error or splash page.
 */
fun isValidJsPluginCode(code: String): Boolean {
    if (code.isBlank()) return false
    val trimmed = code.trimStart { it <= ' ' || it == '\uFEFF' }
    if (trimmed.startsWith("<!DOCTYPE", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
        return false
    }
    return true
}
