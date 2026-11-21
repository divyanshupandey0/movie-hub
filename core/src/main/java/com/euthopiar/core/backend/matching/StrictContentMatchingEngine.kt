package com.euthopiar.core.backend.matching

import com.euthopiar.core.backend.model.ContentMatchResult
import com.euthopiar.core.backend.model.PlayableSourceState
import com.euthopiar.core.matcher.MatchScorer
import com.euthopiar.core.model.ContentType
import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.streams.StreamItem

/**
 * Strict Content Matching Engine for Movie Hub Backend.
 *
 * Enforces Section 2 guarantees:
 * 1. Movies: Validates TMDB ID, original title, release year, alternative titles, and rejects TV episode markers.
 * 2. TV Series: Validates series identity, season number, episode number, and rejects movie markers.
 * 3. Never mixes: Movies with TV episodes, different movies with similar names, different release years without verification, or different seasons/episodes.
 * 4. Strictly rejects trailers, teasers, promos, samples, and promotional clips from full playback.
 * 5. Accurately detects Hindi audio, Hindi dubs, and multi-language tracks without substring collisions.
 */
object StrictContentMatchingEngine {

    private val PROMOTIONAL_REGEX = Regex(
        """(?:^|[^a-zA-Z0-9]|(?<=[a-z])(?=[A-Z]))(trailer|teaser|preview|promo|sample|behind[\s_.-]*the[\s_.-]*scenes|bts|featurette|clip|scene|making[\s_.-]*of|interview|excerpt|short[\s_.-]*film|mini[\s_.-]*doc|recap|review|notice|warning|abuse|rate[\s_.-]*limit)(?:$|[^a-zA-Z0-9]|(?<=[a-z])(?=[A-Z]))""",
        RegexOption.IGNORE_CASE
    )

    private val TV_EPISODE_MARKER_REGEX = Regex(
        """\b(?:S(\d{1,2})[\s._-]*E(\d{1,3})|Season[\s._-]*(\d{1,2})[\s._-]*Episode[\s._-]*(\d{1,3})|(\d{1,2})x(\d{1,3}))\b""",
        RegexOption.IGNORE_CASE
    )

    private val TV_SEASON_ONLY_MARKER_REGEX = Regex(
        """\b(?:Season\s*(\d{1,2})|S(\d{1,2}))\b""",
        RegexOption.IGNORE_CASE
    )

    private val HINDI_REGEX = Regex("""\b(?:hi|hin|hindi)\b""", RegexOption.IGNORE_CASE)
    private val HINDI_DUBBED_REGEX = Regex("""\b(?:hindi[\s._-]*dubbed|hin[\s._-]*dub)\b""", RegexOption.IGNORE_CASE)
    private val MULTI_AUDIO_REGEX = Regex("""\b(?:multi|dual[\s._-]*audio|multi[\s._-]*audio|tri[\s._-]*audio)\b""", RegexOption.IGNORE_CASE)
    private val HINDI_SUBTITLE_ONLY_REGEX = Regex(
        """\b(?:hindi[\s._-]*(?:sub|subs|subbed|subtitle|subtitles|esub|esubs|hardsub|altyazı|altyazılı)|(?:sub|subs|subbed|subtitle|subtitles|esub|esubs|altyazı|altyazılı)[\s._-]*hindi)\b""",
        RegexOption.IGNORE_CASE
    )
    private val HINDI_AUDIO_EXPLICIT_REGEX = Regex(
        """\b(?:hindi[\s._-]*(?:audio|dub|dubbed|clean|org|original|line)|(?:dual|multi|tri)[\s._-]*audio|hindi[\s._-]*dd[\s._-]*\d|hindi[\s._-]*ac3|hindi[\s._-]*aac)\b""",
        RegexOption.IGNORE_CASE
    )
    private val ENGLISH_AUDIO_REGEX = Regex("""\b(?:eng|english)\b""", RegexOption.IGNORE_CASE)
    private val TAMIL_AUDIO_REGEX = Regex("""\b(?:tam|tamil)\b""", RegexOption.IGNORE_CASE)
    private val TELUGU_AUDIO_REGEX = Regex("""\b(?:tel|telugu)\b""", RegexOption.IGNORE_CASE)

    fun match(hints: MatchHints, candidate: StreamItem, targetOriginalLanguage: String? = null): ContentMatchResult {
        val streamLabel = listOfNotNull(candidate.title, candidate.name, candidate.filename, candidate.streamLabel).joinToString(" ").trim()
        val streamUrl = candidate.url.orEmpty().lowercase()

        // 1. Placeholder & Wikimedia Commons rejection
        if (streamUrl.contains("commons.wikimedia.org") || streamUrl.contains("upload.wikimedia.org")) {
            return ContentMatchResult(
                isMatch = false,
                state = PlayableSourceState.CONTENT_MISMATCH,
                score = 0.0,
                reason = "Wikimedia Commons does not host licensed commercial movies or television streams"
            )
        }

        // 2. Promotional content filtering (Trailers, teasers, promos, samples)
        val isTitleExplicitlyTrailer = hints.titles.any { PROMOTIONAL_REGEX.containsMatchIn(it) }
        if (!isTitleExplicitlyTrailer && (PROMOTIONAL_REGEX.containsMatchIn(streamLabel) || PROMOTIONAL_REGEX.containsMatchIn(streamUrl))) {
            return ContentMatchResult(
                isMatch = false,
                state = PlayableSourceState.CONTENT_MISMATCH,
                score = 0.0,
                reason = "Promotional trailer, teaser, preview, sample, or clip rejected from full content request"
            )
        }

        // 3. YouTube link rejection for full movie/series playback
        if (streamUrl.contains("youtube.com") || streamUrl.contains("youtu.be")) {
            return ContentMatchResult(
                isMatch = false,
                state = PlayableSourceState.INVALID,
                score = 0.0,
                reason = "YouTube link detected; not an authorized full stream"
            )
        }

        // 4. Movie target vs TV marker contradiction
        if (hints.type == ContentType.MOVIE) {
            val tvMatch = TV_EPISODE_MARKER_REGEX.find(streamLabel)
            if (tvMatch != null) {
                return ContentMatchResult(
                    isMatch = false,
                    state = PlayableSourceState.CONTENT_MISMATCH,
                    score = 0.0,
                    reason = "Television season/episode markers found on movie target stream (${tvMatch.value})"
                )
            }
        }

        // 5. TV target Season and Episode verification
        if (hints.type != ContentType.MOVIE && hints.season != null && hints.episode != null) {
            val seMatch = TV_EPISODE_MARKER_REGEX.find(streamLabel)
            if (seMatch != null) {
                val foundSeason = seMatch.groupValues[1].toIntOrNull()
                    ?: seMatch.groupValues[3].toIntOrNull()
                    ?: seMatch.groupValues[5].toIntOrNull()
                val foundEpisode = seMatch.groupValues[2].toIntOrNull()
                    ?: seMatch.groupValues[4].toIntOrNull()
                    ?: seMatch.groupValues[6].toIntOrNull()

                if (foundSeason != null && foundSeason != hints.season) {
                    return ContentMatchResult(
                        isMatch = false,
                        state = PlayableSourceState.CONTENT_MISMATCH,
                        score = 0.0,
                        reason = "Season mismatch: requested Season ${hints.season} but stream contains Season $foundSeason"
                    )
                }

                val targetEpisode = hints.episode
                val altTargetEpisode = hints.absoluteEpisode
                if (foundEpisode != null && foundEpisode != targetEpisode && (altTargetEpisode == null || foundEpisode != altTargetEpisode)) {
                    return ContentMatchResult(
                        isMatch = false,
                        state = PlayableSourceState.CONTENT_MISMATCH,
                        score = 0.0,
                        reason = "Episode mismatch: requested Episode $targetEpisode but stream contains Episode $foundEpisode"
                    )
                }
            } else if (hints.season != null && hints.season > 1) {
                // If stream is marked S01 or Season 1 while user is on Season 2+, reject
                val s1Match = TV_SEASON_ONLY_MARKER_REGEX.find(streamLabel)
                val sFound = s1Match?.groupValues?.getOrNull(1)?.toIntOrNull() ?: s1Match?.groupValues?.getOrNull(2)?.toIntOrNull()
                if (sFound != null && sFound != hints.season) {
                    return ContentMatchResult(
                        isMatch = false,
                        state = PlayableSourceState.CONTENT_MISMATCH,
                        score = 0.0,
                        reason = "Season mismatch: stream specifies Season $sFound while target is Season ${hints.season}"
                    )
                }
            }
        }

        // 6. Year verification for Movies (Prevent same-title different-year mismatches)
        val targetYear = hints.year
        if (hints.type == ContentType.MOVIE && targetYear != null && targetYear > 1900) {
            val yearMatches = Regex("""\b(19\d\d|20\d\d)\b""").findAll(streamLabel).mapNotNull { it.value.toIntOrNull() }.toList()
            val distinctYears = yearMatches.filter { it in 1900..2040 }
            if (distinctYears.isNotEmpty()) {
                val hasMatchingYear = distinctYears.any { kotlin.math.abs(it - targetYear) <= 1 }
                if (!hasMatchingYear) {
                    // Title has an explicit different release year (e.g. 1984 vs 2020)
                    return ContentMatchResult(
                        isMatch = false,
                        state = PlayableSourceState.CONTENT_MISMATCH,
                        score = 0.15,
                        reason = "Release year mismatch: requested year $targetYear but stream specifies ${distinctYears.joinToString()}"
                    )
                }
            }
        }

        // 7. Title similarity scoring via MatchScorer
        val scoreResult = MatchScorer.scoreStream(hints, candidate)
        if (!scoreResult.isAccepted || scoreResult.score < 0.60) {
            return ContentMatchResult(
                isMatch = false,
                state = PlayableSourceState.CONTENT_MISMATCH,
                score = scoreResult.score,
                reason = scoreResult.reason ?: "Content similarity score below acceptable threshold (${scoreResult.score})"
            )
        }

        // 8. Audio language and Subtitle verification
        val allText = "$streamLabel $streamUrl ${candidate.audioLanguages.joinToString(" ")}"
        val isExplicitHindiSubtitleOnly = HINDI_SUBTITLE_ONLY_REGEX.containsMatchIn(allText) &&
                !HINDI_AUDIO_EXPLICIT_REGEX.containsMatchIn(allText) &&
                !candidate.audioLanguages.any { it.equals("hi", true) || it.contains("hindi", true) }

        val hasVerifiedHindiAudio = candidate.audioLanguages.any { it.equals("hi", true) || it.contains("hindi", true) }
        val hasMetadataHindiAudio = !isExplicitHindiSubtitleOnly && (HINDI_AUDIO_EXPLICIT_REGEX.containsMatchIn(allText) || HINDI_REGEX.containsMatchIn(allText))
        val hasHindi = (hasVerifiedHindiAudio || hasMetadataHindiAudio) && !isExplicitHindiSubtitleOnly

        val isHindiDubbed = hasHindi && HINDI_DUBBED_REGEX.containsMatchIn(allText)
        val hasEnglish = candidate.audioLanguages.any { it.equals("en", true) || it.contains("english", true) } || ENGLISH_AUDIO_REGEX.containsMatchIn(allText)
        val hasTamil = candidate.audioLanguages.any { it.equals("ta", true) || it.contains("tamil", true) } || TAMIL_AUDIO_REGEX.containsMatchIn(allText)
        val hasTelugu = candidate.audioLanguages.any { it.equals("te", true) || it.contains("telugu", true) } || TELUGU_AUDIO_REGEX.containsMatchIn(allText)

        val isMultiAudio = MULTI_AUDIO_REGEX.containsMatchIn(allText) || candidate.audioLanguages.size > 1 || (hasHindi && hasEnglish)
        val hasHindiEnglishMulti = hasHindi && hasEnglish
        val hasHindiOtherMulti = hasHindi && isMultiAudio && !hasHindiEnglishMulti

        // Determine original language match
        val isOrigLang = when {
            targetOriginalLanguage != null -> {
                val orig = targetOriginalLanguage.lowercase()
                (orig == "hi" && hasHindi) ||
                (orig == "en" && hasEnglish) ||
                (orig == "ta" && hasTamil) ||
                (orig == "te" && hasTelugu) ||
                candidate.audioLanguages.any { it.equals(orig, true) } ||
                allText.contains(orig, true)
            }
            hints.type == ContentType.MOVIE && !hasHindi && hasEnglish -> true // Default fallback for Hollywood
            else -> false
        }

        val detectedAudioLanguages = mutableListOf<String>()
        if (hasHindi) detectedAudioLanguages.add("Hindi")
        if (hasEnglish && !detectedAudioLanguages.contains("English")) detectedAudioLanguages.add("English")
        if (hasTamil && !detectedAudioLanguages.contains("Tamil")) detectedAudioLanguages.add("Tamil")
        if (hasTelugu && !detectedAudioLanguages.contains("Telugu")) detectedAudioLanguages.add("Telugu")
        candidate.audioLanguages.forEach { lang ->
            val clean = lang.trim().replaceFirstChar { it.uppercase() }
            if (!detectedAudioLanguages.contains(clean)) detectedAudioLanguages.add(clean)
        }
        if (detectedAudioLanguages.isEmpty()) {
            detectedAudioLanguages.add("UNKNOWN")
        }

        val detectedSubtitleLanguages = mutableListOf<String>()
        if (isExplicitHindiSubtitleOnly || candidate.subtitles.any { it.language.contains("hi", true) || it.language.contains("hindi", true) }) {
            detectedSubtitleLanguages.add("Hindi")
        }
        candidate.subtitles.forEach { sub ->
            val clean = sub.language.trim().replaceFirstChar { it.uppercase() }
            if (!detectedSubtitleLanguages.contains(clean)) detectedSubtitleLanguages.add(clean)
        }

        // 9. Assign exact verification state based on confidence score
        val state = if (scoreResult.score >= 0.80) {
            PlayableSourceState.VERIFIED
        } else {
            PlayableSourceState.PARTIALLY_VERIFIED
        }

        return ContentMatchResult(
            isMatch = true,
            state = state,
            score = scoreResult.score,
            reason = scoreResult.reason ?: "Strict content match verified (score: ${scoreResult.score})",
            hasHindi = hasHindi,
            isHindiDubbed = isHindiDubbed,
            isMultiAudio = isMultiAudio,
            hasHindiEnglishMulti = hasHindiEnglishMulti,
            hasHindiOtherMulti = hasHindiOtherMulti,
            isOriginalLanguage = isOrigLang,
            audioLanguages = detectedAudioLanguages,
            subtitleLanguages = detectedSubtitleLanguages,
            detectedLanguages = detectedAudioLanguages
        )
    }
}
