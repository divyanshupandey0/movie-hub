package com.euthopiar.core.backend.model

import com.euthopiar.core.model.StreamContainer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Standardized Backend API Response Contract for Movie Hub frontend clients.
 * Guarantees that the frontend receives only normalized, matched, validated, and ranked sources.
 */
@Serializable
data class BackendSourceResponse(
    @SerialName("success") val success: Boolean,
    @SerialName("content") val content: BackendContentDescriptor,
    @SerialName("sources") val sources: List<BackendSourceItem> = emptyList(),
    @SerialName("errors") val errors: List<BackendErrorEntry> = emptyList(),
    @SerialName("requestId") val requestId: String
)

@Serializable
data class BackendContentDescriptor(
    @SerialName("id") val id: String,
    @SerialName("type") val type: String, // "movie", "tv", "anime"
    @SerialName("title") val title: String,
    @SerialName("year") val year: Int? = null,
    @SerialName("season") val season: Int? = null,
    @SerialName("episode") val episode: Int? = null,
    @SerialName("episodeTitle") val episodeTitle: String? = null,
    @SerialName("imdbId") val imdbId: String? = null
)

@Serializable
data class BackendSourceItem(
    @SerialName("id") val id: String,
    @SerialName("provider") val provider: String,
    @SerialName("status") val status: String, // "verified", "partially_verified", "discovered", etc.
    @SerialName("contentMatch") val contentMatch: Boolean,
    @SerialName("url") val url: String,
    @SerialName("format") val format: String = "hls", // "hls", "dash", "mp4", "mkv", "torrent"
    @SerialName("language") val language: List<String> = emptyList(),
    @SerialName("requiresHeaders") val requiresHeaders: Boolean = false,
    @SerialName("headers") val headers: Map<String, String> = emptyMap(),
    @SerialName("quality") val quality: String = "1080p",
    @SerialName("matchScore") val matchScore: Double = 0.0,
    @SerialName("verificationReason") val verificationReason: String? = null,
    @SerialName("validationLatencyMs") val validationLatencyMs: Long = 0L,
    @SerialName("hasHindi") val hasHindi: Boolean = false,
    @SerialName("isHindiDubbed") val isHindiDubbed: Boolean = false,
    @SerialName("isMultiAudio") val isMultiAudio: Boolean = false,
    @SerialName("hasHindiEnglishMulti") val hasHindiEnglishMulti: Boolean = false,
    @SerialName("hasHindiOtherMulti") val hasHindiOtherMulti: Boolean = false,
    @SerialName("isOriginalLanguage") val isOriginalLanguage: Boolean = false,
    @SerialName("audioLanguages") val audioLanguages: List<String> = emptyList(),
    @SerialName("subtitleLanguages") val subtitleLanguages: List<String> = emptyList(),
    @SerialName("isDebrid") val isDebrid: Boolean = false,
    @SerialName("isP2p") val isP2p: Boolean = false,
    @SerialName("infoHash") val infoHash: String? = null,
    @SerialName("seeders") val seeders: Int? = null,
    @SerialName("sourceName") val sourceName: String = provider,
    @SerialName("state") val state: PlayableSourceState = PlayableSourceState.DISCOVERED
)

@Serializable
data class BackendErrorEntry(
    @SerialName("provider") val provider: String,
    @SerialName("errorCode") val errorCode: String,
    @SerialName("message") val message: String
)

/**
 * Internal result produced by StrictContentMatchingEngine.
 */
data class ContentMatchResult(
    val isMatch: Boolean,
    val state: PlayableSourceState,
    val score: Double,
    val reason: String,
    val hasHindi: Boolean = false,
    val isHindiDubbed: Boolean = false,
    val isMultiAudio: Boolean = false,
    val hasHindiEnglishMulti: Boolean = false,
    val hasHindiOtherMulti: Boolean = false,
    val isOriginalLanguage: Boolean = false,
    val audioLanguages: List<String> = emptyList(),
    val subtitleLanguages: List<String> = emptyList(),
    val detectedLanguages: List<String> = emptyList()
)

/**
 * Internal result produced by BackendUrlValidationEngine.
 */
data class UrlValidationResult(
    val isValid: Boolean,
    val state: PlayableSourceState,
    val resolvedUrl: String,
    val detectedFormat: StreamContainer,
    val httpStatus: Int? = null,
    val contentType: String? = null,
    val latencyMs: Long = 0L,
    val requiredHeaders: Map<String, String> = emptyMap(),
    val errorMessage: String? = null
)
