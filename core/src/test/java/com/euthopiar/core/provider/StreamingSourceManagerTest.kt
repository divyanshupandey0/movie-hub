package com.euthopiar.core.provider

import com.euthopiar.core.model.MediaType
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.provider.archive.InternetArchiveProvider
import com.euthopiar.core.provider.custom.CustomStreamProvider
import com.euthopiar.core.provider.metadata.StreamingMetadataManager
import com.euthopiar.core.provider.wikimedia.WikimediaCommonsProvider
import com.euthopiar.core.registry.PluginRegistry
import com.euthopiar.core.streams.StreamItem
import com.euthopiar.core.streams.StreamSubtitle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class StreamingSourceManagerTest {

    private lateinit var manager: StreamingSourceManager
    private lateinit var customProvider: CustomStreamProvider

    @Before
    fun setUp() {
        manager = StreamingSourceManager.getInstance()
        customProvider = manager.customStreamProvider
        customProvider.clearAll()
    }

    @Test
    fun testDefaultProvidersRegistration() {
        val providers = manager.getProviders()
        assertTrue("Should have default providers registered", providers.size >= 4)
        assertTrue("Should contain Internet Archive", providers.any { it.id == "internet_archive" })
        assertTrue("Should contain Wikimedia Commons", providers.any { it.id == "wikimedia_commons" })
        assertTrue("Should contain Custom Stream", providers.any { it.id == "custom_stream" })
        assertTrue("Should contain Castle TV", providers.any { it.id == "castle_tv" })

        val castle = providers.first { it.id == "castle_tv" }
        assertFalse("Castle TV should remain disabled by default due to encryption/auth restrictions", castle.isEnabled)

        // Check PluginRegistry integration
        val plugins = PluginRegistry.Default.plugins.value
        assertTrue("Internet Archive should be registered as UniversalPlugin", plugins.any { it.name == "Internet Archive" })
        assertTrue("Wikimedia Commons should be registered as UniversalPlugin", plugins.any { it.name == "Wikimedia Commons" })
    }

    @Test
    fun testProviderEnableDisable() {
        val providerId = "custom_stream"
        assertTrue(manager.isProviderEnabled(providerId))

        manager.setProviderEnabled(providerId, false)
        assertFalse(manager.isProviderEnabled(providerId))
        assertFalse(manager.getEnabledProviders().any { it.id == providerId })

        manager.setProviderEnabled(providerId, true)
        assertTrue(manager.isProviderEnabled(providerId))
        assertTrue(manager.getEnabledProviders().any { it.id == providerId })
    }

    @Test
    fun testCustomStreamProviderContainerDetection() {
        assertEquals(StreamContainer.HLS, CustomStreamProvider.detectContainer("https://example.com/stream/index.m3u8"))
        assertEquals(StreamContainer.HLS, CustomStreamProvider.detectContainer("https://example.com/live.m3u8?token=123"))
        assertEquals(StreamContainer.DASH, CustomStreamProvider.detectContainer("https://example.com/manifest.mpd"))
        assertEquals(StreamContainer.MP4, CustomStreamProvider.detectContainer("https://example.com/video.mp4"))
        assertEquals(StreamContainer.WEBM, CustomStreamProvider.detectContainer("https://example.com/clip.webm"))
        assertEquals(StreamContainer.MKV, CustomStreamProvider.detectContainer("https://example.com/movie.mkv"))
        assertEquals(StreamContainer.TS, CustomStreamProvider.detectContainer("https://example.com/segment.ts"))
        assertEquals(StreamContainer.AUTO, CustomStreamProvider.detectContainer("https://example.com/unknown_stream"))
    }

    @Test
    fun testCustomStreamRegistrationAndRetrieval() = runBlocking {
        val entry = customProvider.registerStream(
            title = "Test Big Buck Bunny",
            url = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/BigBuckBunny.mp4",
            mediaId = "test_bbb",
            quality = "1080p",
            headers = mapOf("User-Agent" to "MovieHub/2.0"),
            subtitles = listOf(StreamSubtitle(url = "https://example.com/sub.vtt", language = "English"))
        )

        assertNotNull(entry.id)
        assertEquals(StreamContainer.MP4, entry.container)

        // Retrieve streams by mediaId
        val streamsByMediaId = customProvider.getStreamItems(mediaId = "test_bbb")
        assertEquals(1, streamsByMediaId.size)
        assertEquals("Test Big Buck Bunny [1080p]", streamsByMediaId[0].name)
        assertEquals(StreamContainer.MP4, streamsByMediaId[0].container)
        assertEquals(1, streamsByMediaId[0].subtitles.size)

        // Retrieve streams by title
        val streamsByTitle = customProvider.getStreamItems(mediaId = "other_id", title = "Big Buck")
        assertEquals(1, streamsByTitle.size)

        // Search
        val searchResults = customProvider.search("Buck")
        assertEquals(1, searchResults.size)
        assertEquals("Test Big Buck Bunny", searchResults[0].title)

        // Details
        val detail = customProvider.getDetails("custom:${entry.id}")
        assertNotNull(detail)
        assertEquals("Test Big Buck Bunny", detail?.title)

        // Removal
        val removed = customProvider.removeStream(entry.id)
        assertTrue(removed)
        assertTrue(customProvider.getStreamItems(mediaId = "test_bbb").isEmpty())
    }

    @Test
    fun testSelectBestStreamPriority() {
        val stream4k = StreamItem(
            name = "4K Stream",
            url = "https://example.com/4k.m3u8",
            quality = "2160p",
            addonName = "Provider A",
            addonId = "p_a",
            container = StreamContainer.HLS,
            matchScore = 0.95
        )
        val stream1080 = StreamItem(
            name = "1080p Stream",
            url = "https://example.com/1080.mp4",
            quality = "1080p",
            addonName = "Provider B",
            addonId = "p_b",
            container = StreamContainer.MP4,
            matchScore = 0.95
        )
        val stream720 = StreamItem(
            name = "720p Stream",
            url = "https://example.com/720.mp4",
            quality = "720p",
            addonName = "Provider C",
            addonId = "p_c",
            container = StreamContainer.MP4,
            matchScore = 0.90
        )

        val best = manager.selectBestStream(listOf(stream720, stream1080, stream4k))
        assertNotNull(best)
        assertEquals("4K Stream", best?.name)
        assertEquals("2160p", best?.quality)

        // Without 4K
        val secondBest = manager.selectBestStream(listOf(stream720, stream1080))
        assertNotNull(secondBest)
        assertEquals("1080p Stream", secondBest?.name)
    }

    @Test
    fun testAutomaticFallbackStreamSelection() {
        val failedStream = StreamItem(
            name = "Primary 1080p Stream",
            url = "https://example.com/primary_failed.mp4",
            quality = "1080p",
            addonName = "Provider 1",
            addonId = "p1",
            container = StreamContainer.MP4
        )
        val backupStream1 = StreamItem(
            name = "Backup 720p HLS Stream",
            url = "https://example.com/backup_hls.m3u8",
            quality = "720p",
            addonName = "Provider 2",
            addonId = "p2",
            container = StreamContainer.HLS
        )
        val backupStream2 = StreamItem(
            name = "Backup 480p Stream",
            url = "https://example.com/backup_sd.mp4",
            quality = "480p",
            addonName = "Provider 3",
            addonId = "p3",
            container = StreamContainer.MP4
        )

        val allStreams = listOf(failedStream, backupStream1, backupStream2)

        val fallback = manager.getNextFallbackStream(
            failedUrl = "https://example.com/primary_failed.mp4",
            allStreams = allStreams
        )

        assertNotNull(fallback)
        assertEquals("https://example.com/backup_hls.m3u8", fallback?.url)
        assertEquals("Backup 720p HLS Stream", fallback?.name)

        // If backup1 also fails
        val fallback2 = manager.getNextFallbackStream(
            failedUrl = "https://example.com/backup_hls.m3u8",
            allStreams = listOf(backupStream1, backupStream2)
        )
        assertNotNull(fallback2)
        assertEquals("https://example.com/backup_sd.mp4", fallback2?.url)
    }

    @Test
    fun testProviderCapabilitiesAndMetadata() {
        val ia = InternetArchiveProvider()
        assertEquals("internet_archive", ia.id)
        assertEquals("Internet Archive", ia.name)
        assertTrue(ia.supportedTypes.contains(MediaType.MOVIE))
        assertTrue(ia.capabilities.supportsDirectPlayback)
        assertTrue(ia.capabilities.supportsSearch)
        assertTrue(ia.capabilities.supportsSubtitles)

        val wiki = WikimediaCommonsProvider()
        assertEquals("wikimedia_commons", wiki.id)
        assertEquals("Wikimedia Commons", wiki.name)
        assertTrue(wiki.supportedTypes.contains(MediaType.MOVIE))
        assertTrue(wiki.capabilities.supportsDirectPlayback)
        assertTrue(wiki.capabilities.supportsSearch)

        val metaManager = StreamingMetadataManager.getInstance()
        assertNotNull(metaManager)
        assertNotNull(metaManager.getTraktClient())
    }
}
