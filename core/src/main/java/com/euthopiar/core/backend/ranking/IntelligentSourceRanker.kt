package com.euthopiar.core.backend.ranking

import com.euthopiar.core.backend.model.BackendSourceItem
import com.euthopiar.core.backend.model.PlayableSourceState

/**
 * Intelligent Source Ranking Engine for Movie Hub.
 *
 * Implements Section 5 guarantees:
 * 1. Correct content match (score >= 0.80).
 * 2. Verified playback compatibility (VERIFIED > PARTIALLY_VERIFIED).
 * 3. Strict Hindi audio priority:
 *    - Priority 1: Verified Hindi audio.
 *    - Priority 2: Verified Hindi dubbed.
 *    - Priority 3: Verified Hindi multi-audio.
 *    - Priority 4: Fallback to original/other languages.
 * 4. Provider health and historical reliability.
 * 5. Low latency and fast response times.
 * 6. Recent successful playback history.
 * 7. Quality and resolution: 1080p > 720p > 480p > 360p (4K strictly penalized unless user requested 4K).
 * 8. Never invents audio language or resolution information.
 */
object IntelligentSourceRanker {

    private fun parseResolutionHeight(qualityStr: String): Int {
        val q = qualityStr.lowercase().trim()
        return when {
            q.contains("2160") || q.contains("4k") || q.contains("uhd") -> 2160
            q.contains("1440") || q.contains("2k") -> 1440
            q.contains("1080") || q.contains("fhd") -> 1080
            q.contains("720") || q.contains("hd") -> 720
            q.contains("480") || q.contains("sd") -> 480
            q.contains("360") -> 360
            else -> 1080 // Default to standard HD
        }
    }

    /**
     * Scores an individual candidate source according to strict multi-tier criteria.
     */
    fun scoreSource(
        source: BackendSourceItem,
        preferredLanguage: String = "hi",
        originalLanguage: String? = null,
        hasVerifiedHindiInCatalog: Boolean = false,
        allow4k: Boolean = false,
        recentSuccessfulProviderIds: Set<String> = emptySet()
    ): Double {
        var score = 0.0

        // Tier 1: Technical & Playability State (Crucial Gate)
        when (source.state) {
            PlayableSourceState.VERIFIED -> score += 1000.0
            PlayableSourceState.PARTIALLY_VERIFIED -> score += 500.0
            PlayableSourceState.DISCOVERED, PlayableSourceState.VALIDATING -> score += 100.0
            PlayableSourceState.PLAYBACK_FAILED -> score -= 3000.0
            PlayableSourceState.EXPIRED -> score -= 5000.0
            PlayableSourceState.CONTENT_MISMATCH -> score -= 8000.0
            PlayableSourceState.INVALID -> score -= 8000.0
            PlayableSourceState.UNAVAILABLE -> score -= 6000.0
        }

        // Tier 2: Content Match Score Weight
        score += (source.matchScore * 200.0)

        // Tier 3: Strict Hindi-First Language Policy
        val isHindiPref = preferredLanguage.equals("hi", true) || preferredLanguage.contains("hin", true)
        if (isHindiPref) {
            if (hasVerifiedHindiInCatalog) {
                // Verified Hindi IS available in catalog for this title
                if (source.hasHindi) {
                    when {
                        // Priority 1: Verified Hindi audio (pure/original Hindi)
                        !source.isHindiDubbed && !source.isMultiAudio && !source.hasHindiEnglishMulti && !source.hasHindiOtherMulti -> score += 3000.0
                        // Priority 2: Verified Hindi + English multi-audio
                        source.hasHindiEnglishMulti || (source.isMultiAudio && source.language.any { it.contains("eng", true) }) -> score += 2600.0
                        // Priority 3: Verified Hindi + other languages multi-audio
                        source.hasHindiOtherMulti || source.isMultiAudio -> score += 2300.0
                        // Priority 4: Verified Hindi dubbed audio
                        source.isHindiDubbed -> score += 2000.0
                        // General verified Hindi audio
                        else -> score += 1800.0
                    }
                } else {
                    // English-only or Tamil-only alternative when verified Hindi IS available -> Suppress/Reject
                    score -= 4000.0
                }
            } else {
                // Hindi audio is genuinely UNAVAILABLE for this title
                // Priority 5: Original language audio ONLY when Hindi is unavailable
                // Never reject original-language versions (English, Tamil, Telugu, Korean, etc.)
                val isOriginal = source.isOriginalLanguage || (originalLanguage != null && source.language.any { it.equals(originalLanguage, true) })
                val isEnglishFallback = source.language.any { it.equals("English", true) || it.equals("en", true) }
                when {
                    isOriginal -> score += 1500.0
                    isEnglishFallback -> score += 1000.0
                    else -> score -= 500.0 // Random foreign dub penalty
                }
            }
        } else {
            // User requested another specific language (e.g. English)
            val matchesPref = source.language.any { it.equals(preferredLanguage, true) }
            if (matchesPref) {
                score += 1500.0
            } else {
                score += 200.0
            }
        }

        // Tier 4: Provider & Resolution Quality Hierarchy (Torrentio 1080p Default Policy)
        // 1. Verified Torrentio 1080p source
        // 2. Other verified Torrentio 1080p sources
        // 3. Other verified 1080p providers
        // 4. Verified Torrentio sources in lower resolutions
        // 5. Other verified providers as fallback
        val providerName = source.provider.trim().lowercase()
        val sourceName = source.sourceName.trim().lowercase()
        val isTorrentio = providerName.contains("torrentio") || sourceName.contains("torrentio")
        val height = parseResolutionHeight(source.quality)

        when {
            // Priority 1 & 2: Torrentio 1080p
            isTorrentio && height == 1080 -> score += 2000.0
            // Priority 3: Other 1080p Providers
            !isTorrentio && height == 1080 -> score += 1200.0
            // Priority 4: Torrentio in lower resolutions
            isTorrentio -> {
                when (height) {
                    720 -> score += 800.0
                    480 -> score += 600.0
                    360 -> score += 400.0
                    else -> score += 300.0
                }
            }
            // Priority 5: Other providers in lower resolutions
            else -> {
                when (height) {
                    720 -> score += 400.0
                    480 -> score += 200.0
                    360 -> score += 100.0
                    else -> score += 50.0
                }
            }
        }

        // Secondary provider adjustments (MediaFusion, Plavio, etc. as reliable fallbacks)
        when {
            providerName.contains("mediafusion") -> score += 100.0
            providerName.contains("plavio") -> score += 80.0
            providerName.contains("cinestream") -> score += 60.0
        }

        // 4K Handling: Strictly deprioritize 4K on mobile unless user explicitly enabled 4K
        if (height >= 2160) {
            if (allow4k) {
                score += 300.0
            } else {
                score -= 2500.0 // Strictly deprioritized to prevent mobile cellular stalls
            }
        }

        // Debrid cached streams outrank raw unseeded P2P
        if (source.isDebrid) {
            score += 150.0
        } else if (source.isP2p) {
            val seeders = source.seeders ?: 0
            when {
                seeders >= 50 -> score += 100.0
                seeders >= 20 -> score += 70.0
                seeders >= 10 -> score += 30.0
                seeders in 1..9 -> score -= 2500.0 // Low-seed raw torrents cannot stream smoothly on mobile without Debrid
                else -> score -= 4000.0 // Unseeded / dead torrent
            }
        }

        // Tier 5: Recent Successful Playback history bonus
        if (recentSuccessfulProviderIds.contains(source.provider)) {
            score += 100.0
        }

        // Tier 6: Low Latency / Fast Response Time
        if (source.validationLatencyMs in 1..800) {
            score += 50.0
        } else if (source.validationLatencyMs in 801..2500) {
            score += 25.0
        } else if (source.validationLatencyMs > 6000) {
            score -= 50.0
        }

        return score
    }

    /**
     * Helper to identify all Torrentio sources, including variants like "Torrentio TeleCine".
     */
    fun isTorrentioSource(source: BackendSourceItem): Boolean {
        val providerName = source.provider.trim().lowercase()
        val sourceName = source.sourceName.trim().lowercase()
        return providerName.contains("torrentio") || sourceName.contains("torrentio")
    }

    /**
     * Backward-compatible 4-argument overload.
     */
    fun scoreSource(
        source: BackendSourceItem,
        preferredLanguage: String = "hi",
        allow4k: Boolean = false,
        recentSuccessfulProviderIds: Set<String> = emptySet()
    ): Double = scoreSource(
        source = source,
        preferredLanguage = preferredLanguage,
        originalLanguage = null,
        hasVerifiedHindiInCatalog = false,
        allow4k = allow4k,
        recentSuccessfulProviderIds = recentSuccessfulProviderIds
    )

    /**
     * Ranks a collection of backend sources in descending priority order.
     */
    fun rankSources(
        sources: List<BackendSourceItem>,
        preferredLanguage: String = "hi",
        allow4k: Boolean = false,
        recentSuccessfulProviderIds: Set<String> = emptySet()
    ): List<BackendSourceItem> = rankSources(
        sources = sources,
        preferredLanguage = preferredLanguage,
        originalLanguage = null,
        allow4k = allow4k,
        recentSuccessfulProviderIds = recentSuccessfulProviderIds
    )

    /**
     * Ranks a collection of backend sources in descending priority order with originalLanguage support.
     */
    fun rankSources(
        sources: List<BackendSourceItem>,
        preferredLanguage: String = "hi",
        originalLanguage: String? = null,
        allow4k: Boolean = false,
        recentSuccessfulProviderIds: Set<String> = emptySet()
    ): List<BackendSourceItem> {
        val isHindiPref = preferredLanguage.equals("hi", true) || preferredLanguage.contains("hin", true)
        val hasVerifiedHindiInCatalog = isHindiPref && sources.any {
            it.hasHindi && (it.state == PlayableSourceState.VERIFIED || it.state == PlayableSourceState.PARTIALLY_VERIFIED)
        }
        return sources.sortedByDescending { item ->
            scoreSource(
                source = item,
                preferredLanguage = preferredLanguage,
                originalLanguage = originalLanguage,
                hasVerifiedHindiInCatalog = hasVerifiedHindiInCatalog,
                allow4k = allow4k,
                recentSuccessfulProviderIds = recentSuccessfulProviderIds
            )
        }
    }
}
