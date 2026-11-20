package com.euthopiar.ui.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.euthopiar.core.model.AudioTrackDescriptor
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.model.StreamSource
import com.euthopiar.data.preferences.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackBufferingAndLanguageFixTest {

    private fun getViewModel(): PlayerViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return PlayerViewModel(app)
    }

    @Test
    fun testHindiMoviePlaybackAndSelectionHierarchy() {
        val vm = getViewModel()

        val hindiDirect = ServerSourceOption(
            id = "s_hin_direct",
            serverName = "FastCDN Hindi 1080p",
            resolutionLabel = "1080p",
            label = "FastCDN · 1080p [Hindi]",
            streamSource = StreamSource(
                url = "https://example.com/hin1080.mp4",
                quality = "1080p",
                matchScore = 0.92,
                audioTracks = listOf(AudioTrackDescriptor("Hindi", "hi"))
            )
        )

        val englishForeign = ServerSourceOption(
            id = "s_eng_1080",
            serverName = "FastCDN English 1080p",
            resolutionLabel = "1080p",
            label = "FastCDN · 1080p [English]",
            streamSource = StreamSource(
                url = "https://example.com/eng1080.mp4",
                quality = "1080p",
                matchScore = 0.92,
                audioTracks = listOf(AudioTrackDescriptor("English", "en"))
            )
        )

        // Hindi content with Hindi and foreign streams available: Hindi MUST be selected
        val selected = vm.selectLanguageCompliantServers(listOf(englishForeign, hindiDirect), originalLanguage = "hi")
        assertEquals("Hindi stream must be prioritized for Hindi movie", 1, selected.size)
        assertEquals("s_hin_direct", selected.first().id)
    }

    @Test
    fun testHindiSeriesPlaybackAndEpisodeSwitchingValidation() {
        val vm = getViewModel()
        val isMatchingMethod = PlayerViewModel::class.java.getDeclaredMethod(
            "isMatchingStream",
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Integer::class.java,
            java.lang.Integer::class.java,
            java.lang.Integer::class.java,
            java.lang.Integer::class.java,
            String::class.java
        ).apply { isAccessible = true }

        // Correct target episode: Season 2, Episode 4
        val matchesCorrectEp = isMatchingMethod.invoke(
            vm, "stream_s02e04", "tmdb:9999", "tt9999999", 2, 4, 2, 4, "Sacred Games S02E04 1080p Hindi.mkv"
        ) as Boolean
        assertTrue("Matching season 2 episode 4 must be accepted", matchesCorrectEp)

        // Switched episode mismatch: stream is S02E03 while user is on S02E04
        val rejectsWrongEp = isMatchingMethod.invoke(
            vm, "stream_s02e03", "tmdb:9999", "tt9999999", 2, 3, 2, 4, "Sacred Games S02E03 1080p Hindi.mkv"
        ) as Boolean
        assertFalse("Mismatched episode from previous switch must be rejected", rejectsWrongEp)
    }

    @Test
    fun testLanguagePreferenceChangePreservation() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = PlayerViewModel(app)
        val prefs = PreferencesRepository.getInstance(app)

        // Set initial user preference to Hindi
        prefs.setPreferredAudioLanguage("hi")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()

        val englishTrackOption = AudioTrackOption(
            id = "audio_0_1",
            label = "English (Clean Audio)",
            language = "en",
            isoCode = "en",
            displayName = "English",
            isSelected = true,
            trackIndex = 1,
            groupIndex = 0,
            isAuto = false
        )

        // 1. Automatic stream track discovery: MUST NOT overwrite user's persistent preference
        vm.selectAudioTrack(englishTrackOption, isUserExplicit = false)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        var persistedLang = prefs.preferredAudioLanguage.first()
        assertEquals("Automatic track extraction must NOT overwrite persistent preferred language", "hi", persistedLang)

        // 2. Explicit user selection: MUST persist
        vm.selectAudioTrack(englishTrackOption, isUserExplicit = true)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        val persistedLangAfterExplicit = withTimeout(3000) {
            prefs.preferredAudioLanguage.first { it == "en" }
        }
        assertEquals("Explicit user selection MUST persist preferred language", "en", persistedLangAfterExplicit)

        // Clean up preference
        prefs.setPreferredAudioLanguage("hi")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
    }

    @Test
    fun test720pAnd1080pPlaybackPrioritizationOver4kAndHeavyRemux() {
        val vm = getViewModel()

        val opt4K = ServerSourceOption(
            id = "s_4k",
            serverName = "Fast 4K UHD",
            resolutionLabel = "4K 2160p",
            label = "Fast · 4K UHD",
            streamSource = StreamSource(url = "https://example.com/4k.mkv", quality = "4K", matchScore = 0.95)
        )

        val optHeavyRemux = ServerSourceOption(
            id = "s_remux",
            serverName = "Torrent 1080p REMUX",
            resolutionLabel = "1080p",
            label = "Torrent · 1080p REMUX 28.0 GB",
            streamSource = StreamSource(url = "https://example.com/remux.mkv", quality = "1080p", matchScore = 0.95),
            size = "28.0 GB"
        )

        val opt1080p = ServerSourceOption(
            id = "s_1080p",
            serverName = "Direct 1080p FHD",
            resolutionLabel = "1080p",
            label = "Direct · 1080p FHD 2.8 GB",
            streamSource = StreamSource(url = "https://example.com/1080p.mp4", quality = "1080p", matchScore = 0.95),
            size = "2.8 GB"
        )

        val opt720p = ServerSourceOption(
            id = "s_720p",
            serverName = "Direct 720p HD",
            resolutionLabel = "720p",
            label = "Direct · 720p HD 1.2 GB",
            streamSource = StreamSource(url = "https://example.com/720p.mp4", quality = "720p", matchScore = 0.95),
            size = "1.2 GB"
        )

        assertFalse("4K streams must never be treated as prime candidates for instant playback", vm.isPrimeCandidate(opt4K, hasDebrid = true, prefHeight = 1080))
        assertFalse("Heavy 28GB Remux must never be treated as prime candidates", vm.isPrimeCandidate(optHeavyRemux, hasDebrid = true, prefHeight = 1080))
        assertTrue("Stable 1080p stream must qualify as prime candidate", vm.isPrimeCandidate(opt1080p, hasDebrid = true, prefHeight = 1080))
        assertTrue("Stable 720p stream must qualify as prime candidate", vm.isPrimeCandidate(opt720p, hasDebrid = true, prefHeight = 720))

        val score4K = vm.scoreServerForInitialAutoPlay(opt4K, hasDebrid = true, preferredQualityHeight = 1080)
        val score1080p = vm.scoreServerForInitialAutoPlay(opt1080p, hasDebrid = true, preferredQualityHeight = 1080)
        val score720p = vm.scoreServerForInitialAutoPlay(opt720p, hasDebrid = true, preferredQualityHeight = 720)

        assertTrue("4K stream must receive heavy negative penalty", score4K < 0.0)
        assertTrue("1080p stream must outrank 4K significantly", score1080p > score4K + 1000.0)
        assertTrue("720p stream must score positively", score720p > 0.0)
    }

    @Test
    fun testSourceValidationRejectsTrailersTeasersAndClips() {
        val vm = getViewModel()
        val isMatchingMethod = PlayerViewModel::class.java.getDeclaredMethod(
            "isMatchingStream",
            String::class.java,
            String::class.java,
            String::class.java,
            java.lang.Integer::class.java,
            java.lang.Integer::class.java,
            java.lang.Integer::class.java,
            java.lang.Integer::class.java,
            String::class.java
        ).apply { isAccessible = true }

        // Test trailer rejection
        val isTrailer = isMatchingMethod.invoke(
            vm, "stream_tr", "tmdb:1001", "tt1001", null, null, null, null, "Inception 2010 Official Trailer 1080p.mp4"
        ) as Boolean
        assertFalse("Trailer must be rejected for movie playback", isTrailer)

        // Test sample clip rejection
        val isSample = isMatchingMethod.invoke(
            vm, "stream_sm", "tmdb:1001", "tt1001", null, null, null, null, "Inception.2010.1080p.sample.mkv"
        ) as Boolean
        assertFalse("Sample clip must be rejected for movie playback", isSample)

        // Test teaser rejection
        val isTeaser = isMatchingMethod.invoke(
            vm, "stream_ts", "tmdb:1001", "tt1001", null, null, null, null, "Inception Teaser Preview 720p.mp4"
        ) as Boolean
        assertFalse("Teaser preview must be rejected for movie playback", isTeaser)

        // Test valid movie release
        val isValid = isMatchingMethod.invoke(
            vm, "stream_ok", "tmdb:1001", "tt1001", null, null, null, null, "Inception 2010 1080p BluRay Hindi English.mkv"
        ) as Boolean
        assertTrue("Valid full movie release must be accepted", isValid)
    }

    @Test
    fun testDoNotFilterOutAllValidSourcesWhenHindiUnavailable() {
        val vm = getViewModel()

        val englishServer = ServerSourceOption(
            id = "s_eng",
            serverName = "FastCDN English",
            resolutionLabel = "1080p",
            label = "Movie 1080p English",
            streamSource = StreamSource(
                url = "https://example.com/eng.mp4",
                quality = "1080p",
                audioTracks = listOf(AudioTrackDescriptor("English", "en"))
            )
        )

        // Simulate foreign movie (Korean original) with no Hindi audio available
        val result = vm.selectLanguageCompliantServers(listOf(englishServer), originalLanguage = "ko")
        assertEquals("Valid clean English fallback stream must not be completely dropped", 1, result.size)
        assertEquals("s_eng", result.first().id)
    }
}
