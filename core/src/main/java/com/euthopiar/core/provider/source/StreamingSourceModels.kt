package com.euthopiar.core.provider.source

import com.euthopiar.core.model.AudioTrackDescriptor
import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.model.SubtitleTrack
import com.euthopiar.core.provider.catalog.IntegrationHealthStatus

/**
 * Verification confidence level for strict content identity validation.
 *
 * ONLY [VERIFIED] streams are eligible for automatic playback.
 * [NEEDS_REVIEW] streams require explicit user selection.
 * [UNVERIFIED] and [REJECTED] streams must NEVER autoplay.
 */
enum class SourceConfidence {
    VERIFIED,       // Exact, authenticated match to title, year, season, and episode
    NEEDS_REVIEW,   // Borderline similarity or unconfirmed release year; requires explicit user click
    UNVERIFIED,     // Missing or incomplete identity metadata; autoplay strictly blocked
    REJECTED        // Explicit mismatch in title, year (> 1-2 years), season, episode, or sequel
}

/**
 * Model representing an actual, validated, playable media stream.
 *
 * ARCHITECTURAL RULE: Streaming Sources are strictly separated from Content Providers.
 * They return playable media streams, playback headers, audio tracks, and verification confidence.
 */
data class PlayableStream(
    val url: String,
    val format: StreamContainer = StreamContainer.AUTO,
    val quality: String = "Auto",
    val audioTracks: List<AudioTrackDescriptor> = emptyList(),
    val subtitleTracks: List<SubtitleTrack> = emptyList(),
    val language: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val sourceName: String = "",
    val sourceId: String = "",
    val verificationConfidence: SourceConfidence = SourceConfidence.UNVERIFIED,
    val verificationReason: String = "",
    val matchScore: Double = 0.0,
    val expiryTimestampMs: Long? = null,
    val isLive: Boolean = false,
    val seeders: Int? = null,
    val infoHash: String? = null,
    val isDebridCached: Boolean = false
) {
    val isPlayable: Boolean get() = verificationConfidence != SourceConfidence.REJECTED && url.isNotBlank()
    val isAutoplayEligible: Boolean get() = verificationConfidence == SourceConfidence.VERIFIED && isPlayable
}

/**
 * Contract for a dedicated Streaming Source Service.
 */
interface StreamingSourceService {
    val id: String
    val name: String
    val isAuthorized: Boolean
    val isEnabled: Boolean
    val healthStatus: IntegrationHealthStatus
    val supportedFormats: Set<StreamContainer>

    suspend fun resolvePlayableStreams(hints: MatchHints): List<PlayableStream>

    suspend fun validateStream(stream: PlayableStream): Boolean = stream.isPlayable
}
