package com.euthopiar.ui.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.euthopiar.core.model.StreamSource
import com.euthopiar.data.preferences.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TorrentioAutoPlayAndPlaybackTest {

    private lateinit var application: Application
    private lateinit var viewModel: PlayerViewModel
    private lateinit var preferencesRepo: PreferencesRepository

    @Before
    fun setUp() = runBlocking {
        application = ApplicationProvider.getApplicationContext()
        preferencesRepo = PreferencesRepository.getInstance(application)
        preferencesRepo.clearAll()
        preferencesRepo.initializeDefaultAppearancePreferences()
        viewModel = PlayerViewModel(application)
    }

    @After
    fun tearDown() = runBlocking {
        preferencesRepo.clearAll()
    }

    @Test
    fun testTorrentioStreamWith95PeersAndHindiAudioRanksHighestOverDead4k() {
        // Stream 1: 4K HDR stream with 0 seeders from Torrentio (dead swarm)
        val dead4kStream = ServerSourceOption(
            id = "torrentio_4k_dead",
            serverName = "Torrentio · 4K HDR",
            resolutionLabel = "4K 2160p",
            label = "Torrentio · 4K 2160p",
            streamSource = StreamSource(
                url = "magnet:?xt=urn:btih:dead4khash000000000000000000000000000000",
                quality = "4K 2160p",
                infoHash = "dead4khash000000000000000000000000000000",
                seeders = 0,
                matchScore = 0.95,
                addonName = "Torrentio"
            ),
            seeders = 0,
            isTorrent = true,
            addonNames = listOf("Torrentio")
        )

        // Stream 2: 1080P HEVC stream with Hindi + English audio and 95 peers from Torrentio (the working stream)
        val working1080pStream = ServerSourceOption(
            id = "torrentio_1080p_hevc_hindi",
            serverName = "Torrentio · 1080p HEVC Hindi Eng",
            resolutionLabel = "1080p FHD",
            label = "Torrentio · 1080p FHD • HIN • ENG",
            streamSource = StreamSource(
                url = "magnet:?xt=urn:btih:working1080phash11111111111111111111111111",
                quality = "1080p FHD",
                infoHash = "working1080phash11111111111111111111111111",
                seeders = 95,
                matchScore = 0.95,
                addonName = "Torrentio",
                codec = "HEVC"
            ),
            seeders = 95,
            isTorrent = true,
            codec = "HEVC",
            audioBadge = "HIN • ENG",
            addonNames = listOf("Torrentio")
        )

        // Stream 3: Another 1080p stream with low seeders (1 seeder)
        val lowSeedStream = ServerSourceOption(
            id = "torrentio_1080p_low",
            serverName = "Torrentio · 1080p Low Seeds",
            resolutionLabel = "1080p FHD",
            label = "Torrentio · 1080p FHD",
            streamSource = StreamSource(
                url = "magnet:?xt=urn:btih:lowseedhash222222222222222222222222222222",
                quality = "1080p FHD",
                infoHash = "lowseedhash222222222222222222222222222222",
                seeders = 1,
                matchScore = 0.95,
                addonName = "Torrentio"
            ),
            seeders = 1,
            isTorrent = true,
            addonNames = listOf("Torrentio")
        )

        // Score comparisons
        val dead4kScore = viewModel.scoreServerForInitialAutoPlay(dead4kStream, hasDebrid = false, preferredQualityHeight = 1080)
        val working1080pScore = viewModel.scoreServerForInitialAutoPlay(working1080pStream, hasDebrid = false, preferredQualityHeight = 1080)
        val lowSeedScore = viewModel.scoreServerForInitialAutoPlay(lowSeedStream, hasDebrid = false, preferredQualityHeight = 1080)

        assertTrue(
            "Working 1080p HEVC Hindi stream (score=$working1080pScore) must drastically outscore dead 4K stream (score=$dead4kScore)",
            working1080pScore > dead4kScore + 500.0
        )
        assertTrue(
            "Working 1080p HEVC Hindi stream must outscore low-seed 1080p stream (score=$lowSeedScore)",
            working1080pScore > lowSeedScore + 400.0
        )

        // Deduplicate and sort
        val sorted = viewModel.deduplicateAndSortServers(listOf(dead4kStream, working1080pStream, lowSeedStream), hasDebrid = false)

        assertEquals("Top ranked candidate must be the healthy 95-peer 1080p Hindi stream", "torrentio_1080p_hevc_hindi", sorted.first().id)
        assertEquals("Lowest ranked candidate must be the 0-seed dead 4K stream", "torrentio_4k_dead", sorted.last().id)
    }

    @Test
    fun testPrimeCandidateExcludesDeadSwarmsWithoutDebrid() {
        val dead4kStream = ServerSourceOption(
            id = "dead_4k",
            serverName = "Movie 4K",
            resolutionLabel = "4K 2160p",
            streamSource = StreamSource(
                url = "magnet:?xt=urn:btih:dead4k0000000000000000000000000000000000",
                quality = "4K 2160p",
                infoHash = "dead4k0000000000000000000000000000000000",
                seeders = 0,
                matchScore = 0.95
            ),
            seeders = 0,
            isTorrent = true
        )

        val healthy1080p = ServerSourceOption(
            id = "healthy_1080p",
            serverName = "Movie 1080p",
            resolutionLabel = "1080p FHD",
            streamSource = StreamSource(
                url = "magnet:?xt=urn:btih:healthy1080p1111111111111111111111111111",
                quality = "1080p FHD",
                infoHash = "healthy1080p1111111111111111111111111111",
                seeders = 95,
                matchScore = 0.95
            ),
            seeders = 95,
            isTorrent = true
        )

        // Without debrid, dead 4k stream with 0 seeders can never be a prime candidate
        assertFalse("0-seeder stream must NOT be prime candidate", viewModel.isPrimeCandidate(dead4kStream, hasDebrid = false, prefHeight = 2160))
        assertTrue("95-peer 1080p stream must be prime candidate for 1080p", viewModel.isPrimeCandidate(healthy1080p, hasDebrid = false, prefHeight = 1080))
    }

    @Test
    fun testDefaultPreferencesForNewUsers() = runBlocking {
        assertEquals("Torrentio", preferencesRepo.activeProvider.first())
        assertEquals("hi", preferencesRepo.preferredAudioLanguage.first())
        assertEquals("en", preferencesRepo.secondaryAudioLanguage.first())
        assertEquals("1080p", preferencesRepo.defaultQuality.first())
        assertTrue(preferencesRepo.autoSourceSelection.first())
        assertTrue(preferencesRepo.smartFailoverEnabled.first())
        assertTrue(viewModel.uiState.value.smartFailoverEnabled)
    }
}
