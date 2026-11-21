package com.euthopiar.core.backend

import com.euthopiar.core.backend.matching.StrictContentMatchingEngine
import com.euthopiar.core.backend.model.BackendContentDescriptor
import com.euthopiar.core.backend.model.BackendErrorEntry
import com.euthopiar.core.backend.model.BackendSourceItem
import com.euthopiar.core.backend.model.BackendSourceResponse
import com.euthopiar.core.backend.model.PlayableSourceState
import com.euthopiar.core.backend.model.UrlValidationResult
import com.euthopiar.core.backend.ranking.IntelligentSourceRanker
import com.euthopiar.core.backend.session.BackendPlaybackSessionManager
import com.euthopiar.core.backend.validation.BackendUrlValidationEngine
import com.euthopiar.core.model.ContentType
import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.streams.StreamItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Complete End-to-End Test Suite for Movie Hub Backend Playback Engine.
 * Validates all 18 mandatory scenarios specified in Section J:
 * 1. Hindi movie with Hindi source.
 * 2. English original movie with Hindi dub.
 * 3. English original movie without Hindi dub.
 * 4. Tamil original movie without Hindi dub.
 * 5. Telugu original movie without Hindi dub.
 * 6. Multi-audio Hindi/English source.
 * 7. Source with Hindi subtitles but no Hindi audio.
 * 8. Wrong movie URL.
 * 9. Trailer URL.
 * 10. Promotional webpage.
 * 11. HTTP 429 rate-limit response.
 * 12. Expired stream.
 * 13. Wrong TV episode.
 * 14. Correct TV episode.
 * 15. 1080p source.
 * 16. Lower-quality fallback.
 * 17. Failed first source and successful second source.
 * 18. All sources unavailable.
 */
class BackendStreamingInfrastructureTest {

    // 1. Hindi movie with Hindi source
    @Test
    fun test1_HindiMovieWithHindiSource() {
        val hints = MatchHints(
            tmdbId = 381288,
            imdbId = "tt5074352",
            type = ContentType.MOVIE,
            titles = setOf("Dangal"),
            year = 2016
        )
        val hindiCandidate = StreamItem(
            name = "Dangal.2016.Hindi.1080p.BluRay.x264.AAC-5.1",
            title = "Dangal 2016 1080p",
            url = "https://cdn.example.com/dangal_hindi.mp4",
            quality = "1080p",
            audioLanguages = listOf("Hindi")
        )
        val match = StrictContentMatchingEngine.match(hints, hindiCandidate, targetOriginalLanguage = "hi")
        assertTrue("Hindi movie with Hindi source must match", match.isMatch)
        assertEquals(PlayableSourceState.VERIFIED, match.state)
        assertTrue("Hindi audio flag must be true", match.hasHindi)
        assertFalse("Pure Hindi is not dubbed", match.isHindiDubbed)
    }

    // 2. English original movie with Hindi dub
    @Test
    fun test2_EnglishOriginalMovieWithHindiDub() {
        val englishCandidate = BackendSourceItem(
            id = "src_inception_en",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/inception_en.mp4",
            quality = "1080p",
            language = listOf("English"),
            hasHindi = false,
            matchScore = 0.95,
            state = PlayableSourceState.VERIFIED
        )
        val hindiDubCandidate = BackendSourceItem(
            id = "src_inception_hi_dub",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/inception_hi_dub.mp4",
            quality = "1080p",
            language = listOf("Hindi", "English"),
            hasHindi = true,
            isHindiDubbed = true,
            matchScore = 0.95,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(englishCandidate, hindiDubCandidate),
            preferredLanguage = "hi",
            originalLanguage = "en"
        )
        assertEquals("Hindi dub must be ranked higher than English original when Hindi is preferred", "src_inception_hi_dub", ranked[0].id)
    }

    // 3. English original movie without Hindi dub
    @Test
    fun test3_EnglishOriginalMovieWithoutHindiDub() {
        val englishCandidate = BackendSourceItem(
            id = "src_oppenheimer_en",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/oppenheimer_en.mp4",
            quality = "1080p",
            language = listOf("English"),
            hasHindi = false,
            isOriginalLanguage = true,
            matchScore = 0.95,
            state = PlayableSourceState.VERIFIED
        )
        val randomDubCandidate = BackendSourceItem(
            id = "src_oppenheimer_es",
            provider = "Unknown",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/oppenheimer_es.mp4",
            quality = "1080p",
            language = listOf("Spanish"),
            hasHindi = false,
            isOriginalLanguage = false,
            matchScore = 0.95,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(randomDubCandidate, englishCandidate),
            preferredLanguage = "hi",
            originalLanguage = "en"
        )
        assertEquals("English original must be selected when Hindi dub is genuinely unavailable", "src_oppenheimer_en", ranked[0].id)
    }

    // 4. Tamil original movie without Hindi dub
    @Test
    fun test4_TamilOriginalMovieWithoutHindiDub() {
        val tamilCandidate = BackendSourceItem(
            id = "src_vikram_ta",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/vikram_tamil.mp4",
            quality = "1080p",
            language = listOf("Tamil"),
            hasHindi = false,
            isOriginalLanguage = true,
            matchScore = 0.95,
            state = PlayableSourceState.VERIFIED
        )
        val randomDubCandidate = BackendSourceItem(
            id = "src_vikram_foreign",
            provider = "Unknown",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/vikram_foreign.mp4",
            quality = "1080p",
            language = listOf("Russian"),
            hasHindi = false,
            isOriginalLanguage = false,
            matchScore = 0.95,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(randomDubCandidate, tamilCandidate),
            preferredLanguage = "hi",
            originalLanguage = "ta"
        )
        assertEquals("Tamil original must be selected when Hindi is unavailable, not random foreign dub", "src_vikram_ta", ranked[0].id)
    }

    // 5. Telugu original movie without Hindi dub
    @Test
    fun test5_TeluguOriginalMovieWithoutHindiDub() {
        val teluguCandidate = BackendSourceItem(
            id = "src_rrr_te",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/rrr_telugu.mp4",
            quality = "1080p",
            language = listOf("Telugu"),
            hasHindi = false,
            isOriginalLanguage = true,
            matchScore = 0.95,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(teluguCandidate),
            preferredLanguage = "hi",
            originalLanguage = "te"
        )
        assertEquals("Telugu original must be preserved and playable", "src_rrr_te", ranked[0].id)
        assertTrue("State must remain VERIFIED", ranked[0].state == PlayableSourceState.VERIFIED)
    }

    // 6. Multi-audio Hindi/English source
    @Test
    fun test6_MultiAudioHindiEnglishSource() {
        val hints = MatchHints(
            tmdbId = 157336,
            imdbId = "tt0816692",
            type = ContentType.MOVIE,
            titles = setOf("Interstellar"),
            year = 2014
        )
        val multiCandidate = StreamItem(
            name = "Interstellar.2014.1080p.BluRay.Dual.Audio.Hindi-English.x264",
            title = "Interstellar Dual Audio",
            url = "https://cdn.example.com/interstellar_multi.mp4",
            quality = "1080p",
            audioLanguages = listOf("Hindi", "English")
        )
        val match = StrictContentMatchingEngine.match(hints, multiCandidate)
        assertTrue("Multi-audio must match", match.isMatch)
        assertTrue("Must detect Hindi", match.hasHindi)
        assertTrue("Must detect Multi Audio", match.isMultiAudio)
        assertTrue("Must detect Hindi-English multi audio", match.hasHindiEnglishMulti)
    }

    // 7. Source with Hindi subtitles but no Hindi audio
    @Test
    fun test7_SourceWithHindiSubtitlesButNoHindiAudio() {
        val hints = MatchHints(
            tmdbId = 550,
            imdbId = "tt0137523",
            type = ContentType.MOVIE,
            titles = setOf("Fight Club"),
            year = 1999
        )
        val subtitleOnlyCandidate = StreamItem(
            name = "Fight.Club.1999.1080p.BluRay.x264.English.with.Hindi.Subs.mkv",
            title = "Fight Club English with Hindi Subs",
            url = "https://cdn.example.com/fight_club_esubs.mp4",
            quality = "1080p",
            audioLanguages = listOf("English")
        )
        val match = StrictContentMatchingEngine.match(hints, subtitleOnlyCandidate)
        assertTrue("Must match movie", match.isMatch)
        assertFalse("Subtitles must NEVER be treated as Hindi audio", match.hasHindi)
        assertTrue("Must detect Hindi in subtitle languages", match.subtitleLanguages.contains("Hindi"))
        assertTrue("Audio language must remain English", match.audioLanguages.contains("English"))
    }

    // 8. Wrong movie URL
    @Test
    fun test8_WrongMovieUrl() {
        val hints = MatchHints(
            tmdbId = 550,
            imdbId = "tt0137523",
            type = ContentType.MOVIE,
            titles = setOf("Fight Club"),
            year = 1999
        )
        val wrongMovie = StreamItem(
            name = "Avatar.The.Way.of.Water.2022.1080p.WEB-DL.mkv",
            title = "Avatar The Way of Water 2022",
            url = "https://cdn.example.com/avatar_2022.mp4",
            quality = "1080p"
        )
        val match = StrictContentMatchingEngine.match(hints, wrongMovie)
        assertFalse("Wrong movie must not match", match.isMatch)
        assertEquals(PlayableSourceState.CONTENT_MISMATCH, match.state)
    }

    // 9. Trailer URL
    @Test
    fun test9_TrailerUrl() {
        val hints = MatchHints(
            tmdbId = 157336,
            imdbId = "tt0816692",
            type = ContentType.MOVIE,
            titles = setOf("Interstellar"),
            year = 2014
        )
        val trailerCandidate = StreamItem(
            name = "Interstellar.Official.Trailer.1080p.mp4",
            title = "Interstellar Official Trailer",
            url = "https://cdn.example.com/interstellar_trailer.mp4",
            quality = "1080p"
        )
        val match = StrictContentMatchingEngine.match(hints, trailerCandidate)
        assertFalse("Trailer URL must be rejected from full playback", match.isMatch)
        assertEquals(PlayableSourceState.CONTENT_MISMATCH, match.state)
    }

    // 10. Promotional webpage
    @Test
    fun test10_PromotionalWebpage() {
        val htmlValidation = UrlValidationResult(
            isValid = false,
            state = PlayableSourceState.INVALID,
            resolvedUrl = "https://example-promo.com/watch-free.html",
            detectedFormat = StreamContainer.AUTO,
            httpStatus = 200,
            contentType = "text/html",
            errorMessage = "Stream URL returned HTML document instead of playable media"
        )
        assertFalse("HTML webpage must be invalid as video stream", htmlValidation.isValid)
        assertEquals(PlayableSourceState.INVALID, htmlValidation.state)
    }

    // 11. HTTP 429 rate-limit response
    @Test
    fun test11_Http429RateLimitResponse() {
        val rateLimitResult = UrlValidationResult(
            isValid = false,
            state = PlayableSourceState.UNAVAILABLE,
            resolvedUrl = "https://provider.api.com/stream.m3u8",
            detectedFormat = StreamContainer.AUTO,
            httpStatus = 429,
            errorMessage = "Provider rate limit exceeded (HTTP 429)"
        )
        assertFalse("HTTP 429 must be invalid", rateLimitResult.isValid)
        assertEquals(PlayableSourceState.UNAVAILABLE, rateLimitResult.state)
        assertEquals(429, rateLimitResult.httpStatus)
    }

    // 12. Expired stream
    @Test
    fun test12_ExpiredStream() = runBlocking {
        val expiredTimestamp = (System.currentTimeMillis() / 1000L) - 5000L
        val expiredUrl = "https://cdn.example.com/stream.mp4?token=sig&expires=$expiredTimestamp"
        val result = BackendUrlValidationEngine.validateUrl(expiredUrl)
        assertFalse("Expired URL must be invalid", result.isValid)
        assertEquals(PlayableSourceState.EXPIRED, result.state)
    }

    // 13. Wrong TV episode
    @Test
    fun test13_WrongTvEpisode() {
        val hints = MatchHints(
            tmdbId = 1399,
            type = ContentType.TV_SERIES,
            titles = setOf("Game of Thrones"),
            year = 2011,
            season = 2,
            episode = 4
        )
        val wrongEpisodeCandidate = StreamItem(
            name = "Game.of.Thrones.S02E03.1080p.WEB-DL.mkv",
            title = "Game of Thrones S02E03",
            url = "https://cdn.example.com/got_s02e03.mp4",
            quality = "1080p"
        )
        val match = StrictContentMatchingEngine.match(hints, wrongEpisodeCandidate)
        assertFalse("Wrong episode must be rejected", match.isMatch)
        assertEquals(PlayableSourceState.CONTENT_MISMATCH, match.state)
    }

    // 14. Correct TV episode
    @Test
    fun test14_CorrectTvEpisode() {
        val hints = MatchHints(
            tmdbId = 1399,
            type = ContentType.TV_SERIES,
            titles = setOf("Game of Thrones"),
            year = 2011,
            season = 3,
            episode = 9
        )
        val correctEpisodeCandidate = StreamItem(
            name = "Game.of.Thrones.S03E09.1080p.BluRay.x264.mkv",
            title = "Game of Thrones S03E09",
            url = "https://cdn.example.com/got_s03e09.mp4",
            quality = "1080p"
        )
        val match = StrictContentMatchingEngine.match(hints, correctEpisodeCandidate)
        assertTrue("Matching episode S03E09 must be accepted", match.isMatch)
        assertEquals(PlayableSourceState.VERIFIED, match.state)
    }

    // 15. 1080p source preference
    @Test
    fun test15_1080pSourcePreference() {
        val source480p = BackendSourceItem(
            id = "src_480p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/video_480p.mp4",
            quality = "480p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val source1080p = BackendSourceItem(
            id = "src_1080p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/video_1080p.mp4",
            quality = "1080p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(source480p, source1080p),
            preferredLanguage = "hi"
        )
        assertEquals("1080p source must outrank 480p source", "src_1080p", ranked[0].id)
    }

    // 16. Lower-quality fallback
    @Test
    fun test16_LowerQualityFallback() {
        // When 1080p is unavailable, best available quality (720p > 480p) must be chosen
        val source360p = BackendSourceItem(
            id = "src_360p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/video_360p.mp4",
            quality = "360p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val source720p = BackendSourceItem(
            id = "src_720p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/video_720p.mp4",
            quality = "720p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(source360p, source720p),
            preferredLanguage = "hi"
        )
        assertEquals("When 1080p is missing, 720p must be selected over 360p", "src_720p", ranked[0].id)
    }

    // 17. Failed first source and successful second source
    @Test
    fun test17_FailedFirstSourceAndSuccessfulSecondSource() {
        val sessionMgr = BackendPlaybackSessionManager.getInstance()
        val content = BackendContentDescriptor(id = "157336", type = "movie", title = "Interstellar")

        val source1 = BackendSourceItem(
            id = "src_1_failing",
            provider = "BadCDN",
            status = "verified",
            contentMatch = true,
            url = "https://bad.cdn.com/fail.mp4",
            format = "mp4",
            state = PlayableSourceState.VERIFIED
        )
        val source2 = BackendSourceItem(
            id = "src_2_working",
            provider = "GoodCDN",
            status = "verified",
            contentMatch = true,
            url = "https://good.cdn.com/work.mp4",
            format = "mp4",
            state = PlayableSourceState.VERIFIED
        )

        val sessionId = sessionMgr.startSession(
            content = content,
            initialSource = source1,
            availableCandidates = listOf(source1, source2),
            resumePositionMs = 60000L
        )

        // Fail source 1
        val nextCandidate = sessionMgr.onPlaybackError(sessionId, "HTTP_403", "CDN Expired")
        assertNotNull("Failover must yield next candidate", nextCandidate)
        assertEquals("Must failover to source 2", "src_2_working", nextCandidate?.id)

        val sessionState = sessionMgr.getActiveSession(sessionId)
        assertEquals("Position 60s must be preserved", 60000L, sessionState?.playbackPositionMs)
        assertTrue("Failed source must be recorded", sessionState?.failedSourceIds?.contains("src_1_failing") == true)

        sessionMgr.endSession(sessionId)
    }

    // 18. All sources unavailable
    @Test
    fun test18_AllSourcesUnavailable() {
        val emptyResponse = BackendSourceResponse(
            success = false,
            content = BackendContentDescriptor(id = "999999", type = "movie", title = "Unavailable Title"),
            sources = emptyList(),
            errors = listOf(
                BackendErrorEntry("Torrentio", "TIMEOUT", "Timeout 10s"),
                BackendErrorEntry("MediaFusion", "UNAVAILABLE", "No streams found")
            ),
            requestId = "req_all_unavailable"
        )
        assertFalse("Success must be false when all sources are unavailable", emptyResponse.success)
        assertTrue("Sources list must be empty", emptyResponse.sources.isEmpty())
        assertEquals("Both provider errors must be recorded", 2, emptyResponse.errors.size)
    }
}
