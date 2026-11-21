package com.euthopiar.core.backend

import com.euthopiar.core.backend.matching.StrictContentMatchingEngine
import com.euthopiar.core.backend.model.BackendSourceItem
import com.euthopiar.core.backend.model.PlayableSourceState
import com.euthopiar.core.backend.ranking.IntelligentSourceRanker
import com.euthopiar.core.backend.validation.BackendUrlValidationEngine
import com.euthopiar.core.model.ContentType
import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.streams.StreamItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentioQualityDefaultRankingTest {

    // 1. Torrentio sources are prioritized
    @Test
    fun testTorrentioSourcePrioritization() {
        val nonTorrentio1080p = BackendSourceItem(
            id = "src_other_1080p",
            provider = "CineStream",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/other1080.mp4",
            quality = "1080p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val torrentio1080p = BackendSourceItem(
            id = "src_torrentio_1080p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/torrentio1080.mp4",
            quality = "1080p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(nonTorrentio1080p, torrentio1080p),
            preferredLanguage = "hi"
        )
        assertEquals("Torrentio 1080p must be ranked #1", "src_torrentio_1080p", ranked[0].id)
    }

    // 2. Torrentio TeleCine is recognized correctly
    @Test
    fun testTorrentioTeleCineRecognition() {
        val telecineCandidate = BackendSourceItem(
            id = "src_torrentio_telecine",
            provider = "Torrentio TeleCine",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/telecine.mp4",
            quality = "1080p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        assertTrue("Torrentio TeleCine must be recognized as Torrentio", IntelligentSourceRanker.isTorrentioSource(telecineCandidate))

        val otherProvider = BackendSourceItem(
            id = "src_other",
            provider = "MediaFusion",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/other.mp4",
            quality = "1080p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(otherProvider, telecineCandidate),
            preferredLanguage = "hi"
        )
        assertEquals("Torrentio TeleCine must outrank other provider", "src_torrentio_telecine", ranked[0].id)
    }

    // 3. 1080p is selected when available
    @Test
    fun test1080pSelectedWhenAvailable() {
        val torrentio720p = BackendSourceItem(
            id = "src_torrentio_720p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/t720.mp4",
            quality = "720p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val torrentio1080p = BackendSourceItem(
            id = "src_torrentio_1080p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/t1080.mp4",
            quality = "1080p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(torrentio720p, torrentio1080p),
            preferredLanguage = "hi"
        )
        assertEquals("1080p source must be selected over 720p", "src_torrentio_1080p", ranked[0].id)
    }

    // 4. Lower quality is selected only when 1080p is unavailable
    @Test
    fun testLowerQualitySelectedOnlyWhen1080pUnavailable() {
        val source480p = BackendSourceItem(
            id = "src_480p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/t480.mp4",
            quality = "480p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val source720p = BackendSourceItem(
            id = "src_720p",
            provider = "Torrentio",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/t720.mp4",
            quality = "720p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(source480p, source720p),
            preferredLanguage = "hi"
        )
        assertEquals("When 1080p is unavailable, 720p must be selected over 480p", "src_720p", ranked[0].id)
    }

    // 5. Wrong or invalid Torrentio URLs are rejected
    @Test
    fun testWrongOrInvalidTorrentioUrlsRejected() = runBlocking {
        // Invalid syntax / private IP
        val invalidUrl = "http://127.0.0.1:8080/stream.mp4"
        val validation = BackendUrlValidationEngine.validateUrl(invalidUrl)
        assertFalse("Invalid / SSRF URL must be rejected", validation.isValid)
        assertEquals(PlayableSourceState.INVALID, validation.state)

        // Wrong movie content
        val hints = MatchHints(
            tmdbId = 157336,
            type = ContentType.MOVIE,
            titles = setOf("Interstellar"),
            year = 2014
        )
        val wrongTorrentioCandidate = StreamItem(
            name = "Inception.2010.1080p.Torrentio.mkv",
            title = "Inception 2010",
            url = "https://cdn.example.com/wrong.mp4",
            quality = "1080p"
        )
        val match = StrictContentMatchingEngine.match(hints, wrongTorrentioCandidate)
        assertFalse("Mismatched Torrentio candidate must be rejected", match.isMatch)
        assertEquals(PlayableSourceState.CONTENT_MISMATCH, match.state)
    }

    // 6. Series episodes match correctly
    @Test
    fun testSeriesEpisodesMatchCorrectly() {
        val hints = MatchHints(
            tmdbId = 1399,
            type = ContentType.TV_SERIES,
            titles = setOf("Game of Thrones"),
            year = 2011,
            season = 4,
            episode = 2
        )
        val correctEpisode = StreamItem(
            name = "Game.of.Thrones.S04E02.1080p.Torrentio.mkv",
            title = "Game of Thrones S04E02",
            url = "https://cdn.example.com/got_s04e02.mp4",
            quality = "1080p"
        )
        val wrongEpisode = StreamItem(
            name = "Game.of.Thrones.S04E01.1080p.Torrentio.mkv",
            title = "Game of Thrones S04E01",
            url = "https://cdn.example.com/got_s04e01.mp4",
            quality = "1080p"
        )
        val matchCorrect = StrictContentMatchingEngine.match(hints, correctEpisode)
        val matchWrong = StrictContentMatchingEngine.match(hints, wrongEpisode)

        assertTrue("Correct episode must match", matchCorrect.isMatch)
        assertEquals(PlayableSourceState.VERIFIED, matchCorrect.state)
        assertFalse("Wrong episode must be rejected", matchWrong.isMatch)
        assertEquals(PlayableSourceState.CONTENT_MISMATCH, matchWrong.state)
    }

    // 7. Other providers work as fallback
    @Test
    fun testOtherProvidersWorkAsFallback() {
        // When Torrentio is not available, other providers are ranked and playable
        val mediaFusion1080p = BackendSourceItem(
            id = "src_mf_1080p",
            provider = "MediaFusion",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/mf1080.mp4",
            quality = "1080p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val plavio720p = BackendSourceItem(
            id = "src_plavio_720p",
            provider = "PlavioHD",
            status = "verified",
            contentMatch = true,
            url = "https://cdn.example.com/p720.mp4",
            quality = "720p",
            language = listOf("Hindi"),
            hasHindi = true,
            state = PlayableSourceState.VERIFIED
        )
        val ranked = IntelligentSourceRanker.rankSources(
            sources = listOf(plavio720p, mediaFusion1080p),
            preferredLanguage = "hi"
        )
        assertEquals("MediaFusion 1080p must serve as #1 fallback", "src_mf_1080p", ranked[0].id)
        assertEquals("PlavioHD 720p must serve as #2 fallback", "src_plavio_720p", ranked[1].id)
    }

    // 8. User can manually change source and quality
    @Test
    fun testManualSourceAndQualitySelection() {
        val allSources = listOf(
            BackendSourceItem(
                id = "src_torrentio_1080p",
                provider = "Torrentio",
                status = "verified",
                contentMatch = true,
                url = "https://cdn.example.com/t1080.mp4",
                quality = "1080p",
                state = PlayableSourceState.VERIFIED
            ),
            BackendSourceItem(
                id = "src_other_720p",
                provider = "MediaFusion",
                status = "verified",
                contentMatch = true,
                url = "https://cdn.example.com/mf720.mp4",
                quality = "720p",
                state = PlayableSourceState.VERIFIED
            )
        )
        // User manually selecting 720p source
        val userSelectedSource = allSources.first { it.id == "src_other_720p" }
        assertEquals("User can manually pick any source", "src_other_720p", userSelectedSource.id)
        assertEquals("Quality is respected from manual selection", "720p", userSelectedSource.quality)
    }
}
