package com.euthopiar.core.provider.extractor

import com.euthopiar.core.model.StreamContainer

/**
 * Result returned by an internal extractor.
 */
data class ExtractedMedia(
    val url: String,
    val container: StreamContainer = StreamContainer.AUTO,
    val headers: Map<String, String> = emptyMap(),
    val quality: String = "Auto"
)

/**
 * Dedicated contract for internal media extractors.
 *
 * ARCHITECTURAL RULE: Extractors resolve an authorized provider response into a playable media URL.
 * They NEVER independently claim that a title is verified and are strictly decoupled from
 * both the Provider Registry and the Streaming Source Registry.
 */
interface MediaExtractor {
    val name: String
    val supportedDomains: List<String>

    suspend fun canExtract(url: String): Boolean = supportedDomains.any { url.contains(it, ignoreCase = true) }

    suspend fun extract(url: String): List<ExtractedMedia>
}

/**
 * Standard direct media link extractor for open HLS, DASH, MP4, and MKV links.
 */
class DirectMediaExtractor : MediaExtractor {
    override val name: String = "DirectMediaExtractor"
    override val supportedDomains: List<String> = listOf(".m3u8", ".mpd", ".mp4", ".mkv", ".webm")

    override suspend fun extract(url: String): List<ExtractedMedia> {
        val container = when {
            url.contains(".m3u8", ignoreCase = true) -> StreamContainer.HLS
            url.contains(".mpd", ignoreCase = true) -> StreamContainer.DASH
            url.contains(".mp4", ignoreCase = true) -> StreamContainer.MP4
            url.contains(".mkv", ignoreCase = true) -> StreamContainer.MKV
            else -> StreamContainer.AUTO
        }
        return listOf(ExtractedMedia(url = url, container = container))
    }
}
