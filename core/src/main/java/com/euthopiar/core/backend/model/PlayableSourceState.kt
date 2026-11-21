package com.euthopiar.core.backend.model

import kotlinx.serialization.Serializable

/**
 * Distinct source lifecycle states mandated by Movie Hub Backend-First Streaming Infrastructure.
 * Ensures unverified, mismatched, expired, or failed sources are strictly isolated from playback.
 */
@Serializable
enum class PlayableSourceState {
    /** Raw source candidate discovered from provider API response, awaiting validation. */
    DISCOVERED,

    /** Active network, DNS, manifest, or redirect validation in progress. */
    VALIDATING,

    /** Content match verified (score >= 0.80) AND technical URL/manifest probe succeeded. */
    VERIFIED,

    /** Acceptable content match (score >= 0.60) or format compatible, but requires manual selection / user review. */
    PARTIALLY_VERIFIED,

    /** Network unreachable, connection timeout, 5xx, or provider offline. */
    UNAVAILABLE,

    /** Token expired, 401/403/410 response, or timestamp parameter elapsed. */
    EXPIRED,

    /** Corrupt URL, empty response, unsupported format, or invalid manifest structure. */
    INVALID,

    /** Target identity contradicted (wrong movie, wrong year, TV markers on movie, wrong season/episode, or promo clip). */
    CONTENT_MISMATCH,

    /** Real player playback failed during playback initiation or segment decoding. */
    PLAYBACK_FAILED;

    /**
     * Determines whether the source is eligible for playback delivery.
     * Only VERIFIED and PARTIALLY_VERIFIED candidates may ever be forwarded to the player engine.
     */
    val isPlayableEligible: Boolean
        get() = this == VERIFIED || this == PARTIALLY_VERIFIED

    /**
     * Determines whether the source qualifies for automatic default playback (0ms / fast auto-start).
     * Strictly limited to fully VERIFIED sources.
     */
    val isAutoPlayEligible: Boolean
        get() = this == VERIFIED
}
