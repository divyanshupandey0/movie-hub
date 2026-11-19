package com.euthopiar.core.provider.source

import com.euthopiar.core.matcher.MatchScorer
import com.euthopiar.core.model.ContentType
import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.streams.StreamItem

/**
 * Strict Content Identity Verifier for Movie Hub.
 *
 * Implements Section 6 requirements:
 * 1. Movie Identity Verification (TMDB ID, IMDb ID, normalized title, release year, content type)
 * 2. Series & Episode Verification (Series identity, title, season number, episode number)
 * 3. Confidence-Based Matching (VERIFIED, NEEDS_REVIEW, UNVERIFIED, REJECTED)
 * 4. Hard Contradiction Rejection (wrong year, wrong sequel, TV markers on movie, SxxExx mismatches)
 */
object ContentIdentityVerifier {

    data class VerificationDecision(
        val confidence: SourceConfidence,
        val score: Double,
        val reason: String
    )

    /**
     * Evaluates a raw stream candidate against target metadata hints and assigns an exact confidence level.
     */
    fun verify(hints: MatchHints, stream: StreamItem): VerificationDecision {
        val streamLabel = listOfNotNull(stream.title, stream.name, stream.filename).joinToString(" ")
        if (streamLabel.isBlank()) {
            return VerificationDecision(
                confidence = SourceConfidence.NEEDS_REVIEW,
                score = 0.65,
                reason = "Stream label is empty; requires explicit user selection"
            )
        }

        // 0. Wikimedia Commons / placeholder URL rejection
        val streamUrl = stream.url.orEmpty()
        if (streamUrl.contains("commons.wikimedia.org", ignoreCase = true) || streamUrl.contains("upload.wikimedia.org", ignoreCase = true)) {
            return VerificationDecision(
                confidence = SourceConfidence.REJECTED,
                score = 0.0,
                reason = "Wikimedia Commons does not host licensed commercial movies or TV series"
            )
        }

        // 0b. Trailer / sample / short clip rejection for full movie or episode requests
        val trailerRegex = Regex(
            """(?:^|[^a-zA-Z0-9]|(?<=[a-z])(?=[A-Z]))(trailer|teaser|preview|promo|sample|behind[\s_.-]*the[\s_.-]*scenes|bts|featurette|clip|scene|making[\s_.-]*of|interview|excerpt|short[\s_.-]*film|mini[\s_.-]*doc|recap|review)(?:$|[^a-zA-Z0-9]|(?<=[a-z])(?=[A-Z]))""",
            RegexOption.IGNORE_CASE
        )
        if ((trailerRegex.containsMatchIn(streamLabel) || trailerRegex.containsMatchIn(streamUrl)) &&
            !hints.titles.any { trailerRegex.containsMatchIn(it) }) {
            return VerificationDecision(
                confidence = SourceConfidence.REJECTED,
                score = 0.0,
                reason = "Trailer, teaser, preview, sample or short clip detected on full content request"
            )
        }

        // 1. Movie target vs TV marker contradiction check
        if (hints.type == ContentType.MOVIE) {
            val hasTvMarkers = Regex("""\bS\d{1,2}[\s._-]*E\d{1,3}\b""", RegexOption.IGNORE_CASE).containsMatchIn(streamLabel) ||
                    Regex("""\bSeason\s*\d+\b""", RegexOption.IGNORE_CASE).containsMatchIn(streamLabel)
            if (hasTvMarkers) {
                return VerificationDecision(
                    confidence = SourceConfidence.REJECTED,
                    score = 0.0,
                    reason = "TV season/episode markers found on movie target stream"
                )
            }
        }

        // 2. TV target Season and Episode verification
        if (hints.type != ContentType.MOVIE && hints.season != null && hints.episode != null) {
            val seMatch = Regex("""\bS(\d{1,2})[\s._-]*E(\d{1,3})\b""", RegexOption.IGNORE_CASE).find(streamLabel)
            if (seMatch != null) {
                val streamS = seMatch.groupValues[1].toIntOrNull()
                val streamE = seMatch.groupValues[2].toIntOrNull()
                if (streamS != null && streamS != hints.season) {
                    return VerificationDecision(
                        confidence = SourceConfidence.REJECTED,
                        score = 0.0,
                        reason = "Season mismatch: requested S${hints.season} but found S$streamS"
                    )
                }
                if (streamE != null && streamE != hints.episode && (hints.absoluteEpisode == null || streamE != hints.absoluteEpisode)) {
                    return VerificationDecision(
                        confidence = SourceConfidence.REJECTED,
                        score = 0.0,
                        reason = "Episode mismatch: requested E${hints.episode} but found E$streamE"
                    )
                }
            }
        }

        // 3. Score stream using universal MatchScorer
        val scoreResult = MatchScorer.scoreStream(hints, stream)
        if (!scoreResult.isAccepted) {
            return VerificationDecision(
                confidence = SourceConfidence.REJECTED,
                score = scoreResult.score,
                reason = scoreResult.reason ?: "Identity contradiction rejected"
            )
        }

        // 4. Map numerical score to strict confidence tiers
        return when {
            scoreResult.score >= 0.80 -> VerificationDecision(
                confidence = SourceConfidence.VERIFIED,
                score = scoreResult.score,
                reason = scoreResult.reason ?: "Verified exact match (score: ${scoreResult.score})"
            )
            scoreResult.score >= 0.60 -> VerificationDecision(
                confidence = SourceConfidence.NEEDS_REVIEW,
                score = scoreResult.score,
                reason = "Borderline match (score: ${scoreResult.score}); explicit user selection required"
            )
            else -> VerificationDecision(
                confidence = SourceConfidence.UNVERIFIED,
                score = scoreResult.score,
                reason = "Confidence score below verified threshold (${scoreResult.score})"
            )
        }
    }
}
