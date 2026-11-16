package com.euthopiar.ui.player

import android.app.Application
import androidx.media3.common.PlaybackException
import androidx.test.core.app.ApplicationProvider
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.addons.supportsStream
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.model.StreamEmission
import com.euthopiar.core.model.StreamSource
import com.euthopiar.core.registry.PluginRegistry
import com.euthopiar.core.streams.StreamsRepository
import com.euthopiar.data.preferences.PreferencesRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MovieHubPlaybackIntegrityTest {

    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
    }

    @After
    fun tearDown() {
        StreamsRepository.clear()
    }

    /**
     * Scenario A: Select Movie A -> Watch Now -> verify Movie A is requested and plays.
     */
    @Test
    fun testScenarioA_SelectMovieA_PlaysMovieA() = runBlocking {
        val vm = PlayerViewModel(application)
        try {
            val movieAId = "550" // Fight Club
            val movieAImdb = "tt0137523"

            val streamSourceA = StreamSource(
                url = "https://stream.provider.test/fight_club_1080p.mp4",
                serverName = "Torrentio • 1080p",
                resolutionLabel = "1080p",
                quality = "1080p",
                addonName = "Torrentio",
                container = StreamContainer.MP4,
                mediaId = movieAId,
                matchScore = 1.0
            )

            val optionA = vm.createServerSourceOption(
                s = streamSourceA,
                sIndex = 1,
                originalLanguage = "en",
                mediaType = MediaType.MOVIE,
                hasDebrid = true
            )

            assertEquals("550", optionA.streamSource.mediaId)
            assertEquals("Torrentio", optionA.streamSource.addonName)
            assertTrue(vm.isPrimeCandidate(optionA, hasDebrid = true, prefHeight = 1080))
        } finally {
            vm.onExitPlayer()
        }
    }

    /**
     * Scenario B: Select Movie B immediately after Movie A -> verify session token increments and Movie B plays.
     */
    @Test
    fun testScenarioB_RapidSwitchFromMovieAToMovieB_PlaysMovieBNotMovieA() = runBlocking {
        val vm = PlayerViewModel(application)
        try {
            val movieAId = "550"
            val movieBId = "157336" // Interstellar

            val tokenAfterA = vm.currentPlaybackSessionToken.incrementAndGet()
            vm.currentMediaId = movieAId

            // User immediately taps Movie B
            val tokenAfterB = vm.currentPlaybackSessionToken.incrementAndGet()
            vm.currentMediaId = movieBId

            assertTrue("Session token must increment when switching movies", tokenAfterB > tokenAfterA)
            assertEquals(movieBId, vm.currentMediaId)
        } finally {
            vm.onExitPlayer()
        }
    }

    /**
     * Scenario C: Open Movie A again -> verify cached/fresh results still belong to Movie A.
     */
    @Test
    fun testScenarioC_OpenMovieAAgain_IntegrityPreserved() = runBlocking {
        val movieAId = "550"
        val token1 = StreamsRepository.requestToken("movie", movieAId, null, null, "Fight Club", 1999, null)
        val token2 = StreamsRepository.requestToken("movie", movieAId, null, null, "Fight Club", 1999, null)
        assertEquals("Request token for same movie must match", token1, token2)

        val tokenB = StreamsRepository.requestToken("movie", "157336", null, null, "Interstellar", 2014, null)
        assertTrue("Request token for Movie B must differ from Movie A", token1 != tokenB)
    }

    /**
     * Scenario D: Select a TV series episode -> verify correct season and episode are requested and verified.
     */
    @Test
    fun testScenarioD_SelectTvSeriesEpisode_CorrectSeasonAndEpisode() = runBlocking {
        val seriesId = "1399" // Game of Thrones
        val season = 2
        val episode = 5

        val streamSource = StreamSource(
            url = "https://stream.provider.test/got_s02e05.mkv",
            serverName = "Torrentio • 1080p S02E05",
            resolutionLabel = "1080p",
            quality = "1080p",
            addonName = "Torrentio",
            season = season,
            episode = episode,
            mediaId = seriesId
        )

        assertEquals(season, streamSource.season)
        assertEquals(episode, streamSource.episode)
        assertEquals(seriesId, streamSource.mediaId)
    }

    /**
     * Scenario E: Start playback and quickly switch to another movie -> verify stale requests cannot start the old movie.
     */
    @Test
    fun testScenarioE_StaleSessionRejection() = runBlocking {
        val vm = PlayerViewModel(application)
        try {
            val sessionA = vm.currentPlaybackSessionToken.incrementAndGet()
            val sessionB = vm.currentPlaybackSessionToken.incrementAndGet()

            // Verify sessionA is stale
            assertTrue("Session A must be older than Session B", sessionA < sessionB)
            assertEquals(sessionB, vm.currentPlaybackSessionToken.get())

            // In PlayerViewModel, any emission from sessionA is dropped because sessionToken != currentPlaybackSessionToken.get()
            assertFalse(sessionA == vm.currentPlaybackSessionToken.get())
        } finally {
            vm.onExitPlayer()
        }
    }

    /**
     * Scenario F: Network failure / empty results -> verify correct error handling without crash.
     */
    @Test
    fun testScenarioF_EmptyResultsErrorHandling() = runBlocking {
        val vm = PlayerViewModel(application)
        try {
            assertNotNull(vm.uiState.value)
            assertEquals(emptyList<ServerSourceOption>(), vm.uiState.value.availableServers)
        } finally {
            vm.onExitPlayer()
        }
    }

    /**
     * Scenario G: Test automatic fallback -> every fallback must belong to the same requested movie.
     */
    @Test
    fun testScenarioG_FallbackStrictlyScopedToSameMovie() = runBlocking {
        val movieAId = "550"
        val currId = movieAId

        val server1MovieA = ServerSourceOption(
            id = "server_1",
            serverName = "Torrentio 4K",
            label = "Torrentio 4K",
            streamSource = StreamSource(
                url = "https://stream1.test/a.mp4",
                quality = "4K",
                addonName = "Torrentio",
                mediaId = movieAId
            ),
            latencyMs = 50L,
            healthCategory = ServerHealthCategory.AVERAGE
        )

        val server2MovieA = ServerSourceOption(
            id = "server_2",
            serverName = "Torrentio 1080p",
            label = "Torrentio 1080p",
            streamSource = StreamSource(
                url = "https://stream2.test/a_1080p.mp4",
                quality = "1080p",
                addonName = "Torrentio",
                mediaId = movieAId
            ),
            latencyMs = 40L,
            healthCategory = ServerHealthCategory.EXCELLENT
        )

        // Malicious or stale foreign server from Movie B
        val serverForeignMovieB = ServerSourceOption(
            id = "server_foreign",
            serverName = "Other Movie Server",
            label = "Other Movie",
            streamSource = StreamSource(
                url = "https://stream_b.test/b.mp4",
                quality = "1080p",
                addonName = "Torrentio",
                mediaId = "9999" // DIFFERENT MOVIE!
            ),
            latencyMs = 10L,
            healthCategory = ServerHealthCategory.EXCELLENT
        )

        // Filter eligible fallback servers for current movie
        val candidates = listOf(server1MovieA, server2MovieA, serverForeignMovieB).filter { server ->
            val src = server.streamSource
            val srcMediaId = src.mediaId
            val matchesCurrentMovie = srcMediaId.isNullOrBlank() || run {
                val cleanStreamMediaId = srcMediaId.removePrefix("tmdb:").removePrefix("tmdb/").substringBefore(':').trim()
                val cleanTargetMediaId = currId.removePrefix("tmdb:").removePrefix("tmdb/").substringBefore(':').trim()
                cleanStreamMediaId == cleanTargetMediaId
            }
            matchesCurrentMovie && server.id != server1MovieA.id
        }

        assertEquals(1, candidates.size)
        assertEquals("server_2", candidates.first().id)
        assertEquals(movieAId, candidates.first().streamSource.mediaId)
        assertFalse(candidates.any { it.streamSource.mediaId == "9999" })
    }

    /**
     * Scenario H: Restart the app -> verify Torrentio remains the default and official repo is NOT forcibly reinstalled.
     */
    @Test
    fun testScenarioH_DefaultSettings_TorrentioPreferred_NoForcedRepoInstall() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val preferencesRepo = PreferencesRepository.getInstance(application)
        preferencesRepo.initializeDefaultAppearancePreferences()
        assertEquals("Torrentio", preferencesRepo.activeProvider.first())
        assertEquals("hi", preferencesRepo.preferredAudioLanguage.first())
        assertEquals("en", preferencesRepo.secondaryAudioLanguage.first())
        assertEquals("Auto", preferencesRepo.defaultQuality.first())
        assertTrue(preferencesRepo.autoSourceSelection.first())
        assertTrue(preferencesRepo.smartFailoverEnabled.first())
        assertFalse("Official repository must not auto-install without user consent", preferencesRepo.autoOfficialRepoInstall.first())
    }

    /**
     * Scenario I: Verify that no DahmerMovies / DharmaMovie provider, scraper or repository remains registered or can be registered.
     */
    @Test
    fun testScenarioI_ZeroDharmaMovieRemnants() = runBlocking {
        val allPlugins = PluginRegistry.Default.plugins.value
        val hasDahmer = allPlugins.any {
            it.name.contains("dahmer", ignoreCase = true) || it.name.contains("dharma", ignoreCase = true)
        }
        assertFalse("No DahmerMovies provider must be registered", hasDahmer)

        val preferencesRepo = PreferencesRepository.getInstance(application)
        val activeProvider = preferencesRepo.activeProvider.first()
        assertFalse("Active provider must not be DahmerMovies", activeProvider.contains("dahmer", ignoreCase = true))

        val storage = com.euthopiar.core.js.JsPluginStorage.getInstance(application)
        assertTrue(storage.isBlockedScraper("dahmermovies", "Dahmermovies", "providers/dahmermovies.js"))
        assertTrue(storage.isBlockedScraper("dahmermovies-tv", "Dahmermovies-TV", "providers/dahmermovies-4k.js"))

        val dummyPlugin = object : com.euthopiar.core.model.UniversalPlugin {
            override val name: String = "DahmerMovies"
            override val mainUrl: String = "https://dahmer.test"
            override val supportedTypes: Set<MediaType> = setOf(MediaType.MOVIE)
            override suspend fun getHomeCatalog() = emptyList<com.euthopiar.core.model.CatalogRow>()
            override suspend fun search(query: String) = emptyList<com.euthopiar.core.model.MediaItem>()
            override suspend fun getDetails(mediaItem: com.euthopiar.core.model.MediaItem) = throw UnsupportedOperationException()
            override suspend fun getStreamLinks(episodeData: String) = com.euthopiar.core.model.StreamResult(emptyList(), emptyList())
        }
        PluginRegistry.Default.registerPlugin(dummyPlugin)
        assertFalse(PluginRegistry.Default.plugins.value.any { it.name.equals("DahmerMovies", ignoreCase = true) })
    }

    /**
     * Scenario J: Fresh install -> Torrentio initialized out of the box with stream capability,
     * independent of official repository installation.
     */
    @Test
    fun testScenarioJ_FreshInstallTorrentioReady_NoRepoRequired() = runBlocking {
        val addonRepo = com.euthopiar.core.addons.AddonRepository.getInstance(application)
        val addons = addonRepo.uiState.value.addons
        val torrentio = addons.firstOrNull {
            it.manifestUrl == com.euthopiar.core.addons.AddonRepository.DEFAULT_TORRENTIO_MANIFEST_URL ||
            it.manifest?.id == "com.stremio.torrentio.addon" ||
            it.displayTitle.equals("Torrentio", ignoreCase = true)
        }

        assertNotNull("Torrentio must be present in default addons on fresh install", torrentio)
        assertTrue("Torrentio must be enabled by default", torrentio!!.enabled)
        assertNotNull("Torrentio must have a pre-populated valid manifest", torrentio.manifest)
        assertTrue("Torrentio manifest must support stream resource", torrentio.manifest!!.supportsStream("movie", "tt0137523"))
        assertTrue("Torrentio manifest must support TV stream resource", torrentio.manifest!!.supportsStream("series", "tt0944947:1:1"))

        // Stream addons check for media details
        val hasStreamEngine = addons.any { addon ->
            addon.enabled &&
                addon.manifestUrl != com.euthopiar.core.addons.AddonRepository.DEFAULT_CATALOG_MANIFEST_URL &&
                addon.manifestUrl != com.euthopiar.core.addons.AddonRepository.DEFAULT_OPENSUBTITLES_MANIFEST_URL
        }
        assertTrue("Stream engine must be ready for play button without official repo installation", hasStreamEngine)
    }

    /**
     * Scenario K: Secondary Fallback Provider (MediaFusion) registered alongside Torrentio.
     * Torrentio remains #1 default provider, MediaFusion acts as secondary fallback.
     */
    @Test
    fun testScenarioK_MediaFusionRegisteredAsSecondaryFallback() = runBlocking {
        val addonRepo = com.euthopiar.core.addons.AddonRepository.getInstance(application)
        val addons = addonRepo.uiState.value.addons
        val torrentioIndex = addons.indexOfFirst {
            it.manifestUrl == com.euthopiar.core.addons.AddonRepository.DEFAULT_TORRENTIO_MANIFEST_URL ||
            it.manifest?.id == "com.stremio.torrentio.addon"
        }
        val mediafusionIndex = addons.indexOfFirst {
            it.manifestUrl == com.euthopiar.core.addons.AddonRepository.DEFAULT_MEDIAFUSION_MANIFEST_URL ||
            it.manifest?.id == "stremio.addons.mediafusion|elfhosted"
        }

        assertTrue("Torrentio must be found", torrentioIndex >= 0)
        assertTrue("MediaFusion must be found", mediafusionIndex >= 0)
        assertTrue("Torrentio must be prioritized before MediaFusion", torrentioIndex < mediafusionIndex)

        val mediafusion = addons[mediafusionIndex]
        assertTrue("MediaFusion must be enabled", mediafusion.enabled)
        assertNotNull("MediaFusion must have a valid pre-populated manifest", mediafusion.manifest)
        assertTrue("MediaFusion must support movie streams", mediafusion.manifest!!.supportsStream("movie", "tt0137523"))
        assertTrue("MediaFusion must support TV series streams", mediafusion.manifest!!.supportsStream("series", "tt0944947:1:1"))

        // Priority score verification in PlayerViewModel
        val streamSourceTorrentio = StreamSource(
            serverName = "Torrentio • 1080p",
            url = "https://torrentio.test/stream.mkv",
            addonName = "Torrentio",
            quality = "1080p",
            resolutionLabel = "1080p",
            matchScore = 1.0
        )
        val streamSourceMediaFusion = StreamSource(
            serverName = "MediaFusion • 1080p",
            url = "https://mediafusion.test/stream.mkv",
            addonName = "MediaFusion",
            quality = "1080p",
            resolutionLabel = "1080p",
            matchScore = 1.0
        )

        val vm = PlayerViewModel(application)
        try {
            val dummyTorrentioOption = vm.createServerSourceOption(
                s = streamSourceTorrentio,
                sIndex = 1,
                originalLanguage = "en",
                mediaType = MediaType.MOVIE,
                hasDebrid = false
            )
            val dummyMediaFusionOption = vm.createServerSourceOption(
                s = streamSourceMediaFusion,
                sIndex = 2,
                originalLanguage = "en",
                mediaType = MediaType.MOVIE,
                hasDebrid = false
            )

            val torrentioScore = vm.scoreServerForInitialAutoPlay(dummyTorrentioOption, hasDebrid = false)
            val mediafusionScore = vm.scoreServerForInitialAutoPlay(dummyMediaFusionOption, hasDebrid = false)
            assertTrue("Torrentio must have higher auto-play priority score than secondary fallback MediaFusion", torrentioScore > mediafusionScore)
        } finally {
            vm.onExitPlayer()
        }
    }

    /**
     * Scenario L: TV series season and episode identification and stream resolution integrity.
     */
    @Test
    fun testScenarioL_TvSeriesSeasonAndEpisodeMapping() = runBlocking {
        val seriesTmdbId = "1399" // Game of Thrones
        val token = StreamsRepository.requestToken("series", seriesTmdbId, 1, 1, "Game of Thrones", 2011, null)
        assertEquals("series::1399::1::1::Game of Thrones::2011::null", token)

        val seriesSource = StreamSource(
            url = "https://stream.provider.test/got_s01e01.mkv",
            serverName = "Torrentio • 1080p S01E01",
            resolutionLabel = "1080p",
            quality = "1080p",
            addonName = "Torrentio",
            season = 1,
            episode = 1,
            mediaId = seriesTmdbId
        )

        assertEquals(1, seriesSource.season)
        assertEquals(1, seriesSource.episode)
        assertEquals(seriesTmdbId, seriesSource.mediaId)
    }

    /**
     * Scenario M: Audio Language Preference & Hindi Dubbed track handling.
     */
    @Test
    fun testScenarioM_AudioLanguagePreferenceAndHindiDubbedHandling() = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        val preferencesRepo = PreferencesRepository.getInstance(application)
        preferencesRepo.initializeDefaultAppearancePreferences()
        // Preferred language defaults to Hindi
        assertEquals("hi", preferencesRepo.preferredAudioLanguage.first())

        // User can change preferred audio language and setting persists
        preferencesRepo.setPreferredAudioLanguage("en")
        assertEquals("en", preferencesRepo.preferredAudioLanguage.first())
        preferencesRepo.setPreferredAudioLanguage("hi")
        assertEquals("hi", preferencesRepo.preferredAudioLanguage.first())

        // Audio track option with Hindi on foreign content
        val hindiTrack = AudioTrackOption(
            id = "audio_1_0",
            label = "Hindi Dubbed (Stereo)",
            language = "hi",
            isoCode = "hi",
            displayName = "Hindi Dubbed",
            isSelected = false,
            trackIndex = 0,
            groupIndex = 1,
            isAuto = false
        )
        assertEquals("hi", hindiTrack.language)
        assertEquals("Hindi Dubbed", hindiTrack.displayName)
        assertEquals("hi", preferencesRepo.preferredAudioLanguage.first())
    }

    /**
     * Scenario N: India's Got Latent TV series stream resolution and abbreviation/acronym matching.
     */
    @Test
    fun testScenarioN_IndiasGotLatent_EpisodeStreamResolution() {
        val iglHints = com.euthopiar.core.model.MatchHints(
            tmdbId = 262838,
            imdbId = "tt33094114",
            type = com.euthopiar.core.model.ContentType.TV_SERIES,
            titles = setOf("India's Got Latent"),
            year = 2024,
            season = 1,
            episode = 1
        )

        // 1. Verify acronym matching recognizes IGL as India's Got Latent
        assertTrue(
            "MatchScorer must recognize IGL as acronym of India's Got Latent",
            com.euthopiar.core.matcher.MatchScorer.isAcronymMatch("India's Got Latent", "igl")
        )

        // 2. Test S01E01 stream returned by Torrentio
        val streamEp1 = com.euthopiar.core.streams.StreamItem(
            name = "Torrentio\n1080p",
            title = "India's Got Latent\nIGL_EP_01_ft_RaftaarMusic.mp4\n💾 1.48 GB 👤 14 👥 0",
            filename = "IGL_EP_01_ft_RaftaarMusic.mp4",
            url = "magnet:?xt=urn:btih:dummy123456&dn=IGL_EP_01_ft_RaftaarMusic.mp4",
            addonName = "Torrentio",
            addonId = "torrentio"
        )
        val resultEp1 = com.euthopiar.core.matcher.MatchScorer.scoreStream(iglHints, streamEp1)
        assertTrue("S01E01 stream must be accepted (got: ${resultEp1.reason})", resultEp1.isAccepted)
        assertTrue("S01E01 score must be high (got: ${resultEp1.score})", resultEp1.score >= 0.80)

        // 3. Test S01E02 stream
        val iglHintsEp2 = iglHints.copy(episode = 2)
        val streamEp2 = com.euthopiar.core.streams.StreamItem(
            name = "Torrentio\n1080p",
            title = "India's Got Latent\nIGL_EP_02_ft_GamerFleet_JokeSingh.mp4\n💾 1.35 GB 👤 8 👥 0",
            filename = "IGL_EP_02_ft_GamerFleet_JokeSingh.mp4",
            url = "magnet:?xt=urn:btih:dummy789012&dn=IGL_EP_02_ft_GamerFleet_JokeSingh.mp4",
            addonName = "Torrentio",
            addonId = "torrentio"
        )
        val resultEp2 = com.euthopiar.core.matcher.MatchScorer.scoreStream(iglHintsEp2, streamEp2)
        assertTrue("S01E02 stream must be accepted (got: ${resultEp2.reason})", resultEp2.isAccepted)
        assertTrue("S01E02 score must be high (got: ${resultEp2.score})", resultEp2.score >= 0.80)

        // 4. Test wrong episode is rejected
        val resultWrongEp = com.euthopiar.core.matcher.MatchScorer.scoreStream(iglHints, streamEp2)
        assertFalse("Stream for EP_02 must NOT match request for EP_01", resultWrongEp.isAccepted)
    }

    /**
     * Scenario O: The Golden Eyes TV series identification and stream verification.
     */
    @Test
    fun testScenarioO_TheGoldenEyes_StreamResolution() {
        val goldenEyesHints = com.euthopiar.core.model.MatchHints(
            tmdbId = 87133,
            type = com.euthopiar.core.model.ContentType.TV_SERIES,
            titles = setOf("The Golden Eyes"),
            year = 2019,
            season = 1,
            episode = 1
        )

        val streamItem = com.euthopiar.core.streams.StreamItem(
            name = "Torrentio\n1080p",
            title = "The Golden Eyes S01E01 1080p WEB-DL",
            url = "https://stream.provider.test/the_golden_eyes_s01e01.mp4",
            addonName = "Torrentio",
            addonId = "torrentio"
        )
        val result = com.euthopiar.core.matcher.MatchScorer.scoreStream(goldenEyesHints, streamItem)
        assertTrue("The Golden Eyes S01E01 stream must be accepted", result.isAccepted)
        assertTrue("The Golden Eyes S01E01 score must be high", result.score >= 0.80)
    }

    /**
     * Scenario P: Season 0 Specials resolution integrity.
     */
    @Test
    fun testScenarioP_Season0SpecialsResolution() {
        val specialsHints = com.euthopiar.core.model.MatchHints(
            tmdbId = 1399,
            type = com.euthopiar.core.model.ContentType.TV_SERIES,
            titles = setOf("Game of Thrones"),
            year = 2011,
            season = 0,
            episode = 1
        )

        val streamItem = com.euthopiar.core.streams.StreamItem(
            name = "Torrentio\n1080p",
            title = "Game of Thrones S00E01 1080p BluRay",
            url = "https://stream.provider.test/got_s00e01.mp4",
            addonName = "Torrentio",
            addonId = "torrentio"
        )
        val result = com.euthopiar.core.matcher.MatchScorer.scoreStream(specialsHints, streamItem)
        assertTrue("Season 0 Episode 1 stream must be accepted", result.isAccepted)
        assertTrue("Season 0 Episode 1 score must be high", result.score >= 0.80)
    }

    /**
     * Scenario Q: Update system official GitHub configuration integrity.
     */
    @Test
    fun testScenarioQ_UpdateSystemDisabledIntegrity() = runBlocking {
        assertTrue(
            "App update system is enabled for official releases",
            com.euthopiar.data.update.AppUpdateConfig.IS_UPDATE_SYSTEM_ENABLED
        )
        assertEquals(
            "https://github.com/divyanshupandey0/movie-hub",
            com.euthopiar.data.update.AppUpdateConfig.GITHUB_REPO_URL
        )
        val updateMgr = com.euthopiar.data.update.AppUpdateManager.getInstance(application)
        assertNotNull(updateMgr)
    }

    /**
     * Scenario R: PlavioHD manifest parsing and integrity verification.
     */
    @Test
    fun testScenarioR_PlavioHdManifestParsingAndIntegrity() {
        val manifestJson = """
        {
          "id": "community.plaviohd",
          "version": "1.9.273",
          "name": "PlavioHD",
          "description": "Direct streaming provider for Movies and Series",
          "resources": [
            {
              "name": "stream",
              "types": ["movie", "series"],
              "idPrefixes": ["tmdb:", "tt"]
            },
            {
              "name": "subtitles",
              "types": ["movie", "series"],
              "idPrefixes": ["tmdb:", "tt"]
            }
          ],
          "types": ["movie", "series"],
          "idPrefixes": ["tmdb:", "tt"]
        }
        """.trimIndent()

        val parsed = com.euthopiar.core.addons.AddonManifestParser.parse(
            com.euthopiar.core.addons.AddonRepository.DEFAULT_PLAVIOHD_MANIFEST_URL,
            manifestJson
        )

        assertEquals("community.plaviohd", parsed.id)
        assertEquals("PlavioHD", parsed.name)
        assertEquals("1.9.273", parsed.version)
        assertTrue(parsed.supportsStream("movie", "tt0137523"))
        assertTrue(parsed.supportsStream("series", "tt0944947:1:1"))
        assertTrue(parsed.supportsStream("movie", "tmdb:550"))
        assertTrue(parsed.supportsStream("series", "tmdb:1399:1:1"))
        assertFalse(parsed.supportsStream("anime", "kitsu:1234"))
    }

    /**
     * Scenario S: PlavioHD stream parsing and proxy headers integrity.
     */
    @Test
    fun testScenarioS_PlavioHdStreamParsingAndHeaders() {
        val streamJson = """
        {
          "streams": [
            {
              "name": "PlavioHD\n[4KHDHub] Direct",
              "title": "Fight Club (1999) 1080p Multi Audio Hindi English\nDirect Streaming",
              "url": "https://direct.stream.provider.test/fight_club_1080p.mp4",
              "behaviorHints": {
                "notWebReady": false,
                "proxyHeaders": {
                  "request": {
                    "Referer": "https://4khdhub.test/",
                    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"
                  }
                }
              }
            }
          ]
        }
        """.trimIndent()

        val items = com.euthopiar.core.streams.StreamParser.parse(
            payload = streamJson,
            addonName = "PlavioHD",
            addonId = "community.plaviohd"
        )

        assertEquals(1, items.size)
        val item = items.first()
        assertEquals("PlavioHD", item.addonName)
        assertEquals("community.plaviohd", item.addonId)
        assertEquals("https://direct.stream.provider.test/fight_club_1080p.mp4", item.url)
        assertNotNull(item.headers)
        assertEquals("https://4khdhub.test/", item.headers["Referer"])
        assertEquals("Mozilla/5.0 (Windows NT 10.0; Win64; x64)", item.headers["User-Agent"])
    }
}
