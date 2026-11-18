package com.euthopiar.core.provider

import com.euthopiar.core.network.PlayerPlaybackNetworking
import com.euthopiar.core.streams.AudioLanguageDiscovery
import com.euthopiar.core.streams.StreamItem
import com.euthopiar.core.streams.deduplicated
import com.euthopiar.core.streams.sortedForGroupedDisplay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MultilingualStreamingOptimizationTest {

    @Test
    fun testLanguagePriorityHierarchy() {
        val verifiedHindi = AudioLanguageDiscovery.getLanguagePriorityScore(listOf("HIN"), listOf("HIN"))
        val metaHindi = AudioLanguageDiscovery.getLanguagePriorityScore(listOf("HIN"), emptyList())
        val english = AudioLanguageDiscovery.getLanguagePriorityScore(listOf("ENG"), emptyList())
        val tamil = AudioLanguageDiscovery.getLanguagePriorityScore(listOf("TAM"), emptyList())
        val telugu = AudioLanguageDiscovery.getLanguagePriorityScore(listOf("TEL"), emptyList())
        val malayalam = AudioLanguageDiscovery.getLanguagePriorityScore(listOf("MAL"), emptyList())
        val bengali = AudioLanguageDiscovery.getLanguagePriorityScore(listOf("BEN"), emptyList())
        val other = AudioLanguageDiscovery.getLanguagePriorityScore(listOf("FRA"), emptyList())

        // Priority assertion: Verified Hindi > Meta Hindi > English > Tamil > Telugu > Malayalam > Bengali > Other
        assertTrue("Verified Hindi must rank above Metadata Hindi", verifiedHindi > metaHindi)
        assertTrue("Hindi must rank above English", metaHindi > english)
        assertTrue("English must rank above Tamil", english > tamil)
        assertTrue("Tamil must rank above Telugu", tamil > telugu)
        assertTrue("Telugu must rank above Malayalam", telugu > malayalam)
        assertTrue("Malayalam must rank above Bengali", malayalam > bengali)
        assertTrue("Bengali must rank above Other", bengali > other)
    }

    @Test
    fun testStrictHindiVerificationReconciliation() {
        // If a title claims "Hindi Dubbed", but actual container stream analysis only contains English/Japanese:
        val metadataLanguages = listOf("HIN", "ENG")
        val verifiedTracksOnlyEnglish = listOf("ENG", "JPN")

        val reconciled = AudioLanguageDiscovery.reconcileAudioLanguages(metadataLanguages, verifiedTracksOnlyEnglish)
        assertFalse("Must NOT mark as Hindi when verified tracks contradict title", reconciled.contains("HIN"))
        assertTrue("Must contain genuine verified English track", reconciled.contains("ENG"))
        assertTrue("Must contain genuine verified Japanese track", reconciled.contains("JPN"))

        // When verified tracks confirm Hindi:
        val verifiedTracksWithHindi = listOf("HIN", "ENG")
        val reconciledWithHindi = AudioLanguageDiscovery.reconcileAudioLanguages(metadataLanguages, verifiedTracksWithHindi)
        assertTrue("Must contain Hindi when verified by stream probe", reconciledWithHindi.contains("HIN"))
    }

    @Test
    fun testStreamDeduplication() {
        val stream1 = StreamItem(name = "Stream 1", url = "https://cdn.example.com/video.mp4?token=123", addonName = "Addon A", addonId = "addon_a")
        val stream2 = StreamItem(name = "Stream 1 Mirror", url = "https://cdn.example.com/video.mp4?token=456", addonName = "Addon B", addonId = "addon_b")
        val stream3 = StreamItem(name = "Stream 2", url = "https://cdn.example.com/other.mkv", addonName = "Addon A", addonId = "addon_a")

        val list = listOf(stream1, stream2, stream3)
        val deduplicated = list.deduplicated()

        assertEquals("Duplicate stream with same clean URL must be eliminated", 2, deduplicated.size)
        assertEquals("https://cdn.example.com/video.mp4?token=123", deduplicated[0].url)
        assertEquals("https://cdn.example.com/other.mkv", deduplicated[1].url)
    }

    @Test
    fun testHindiFirstStreamSorting() {
        val engStream = StreamItem(name = "English FHD", quality = "1080p FHD", audioLanguages = listOf("ENG"), addonName = "A", addonId = "a")
        val hinStream = StreamItem(name = "Hindi 720p", quality = "720p HD", audioLanguages = listOf("HIN"), addonName = "B", addonId = "b")
        val verifiedHinStream = StreamItem(name = "Verified Hindi FHD", quality = "1080p FHD", audioLanguages = listOf("HIN"), verifiedAudioLanguages = listOf("HIN"), addonName = "C", addonId = "c")

        val sorted = listOf(engStream, hinStream, verifiedHinStream).sortedForGroupedDisplay()

        assertEquals("Verified Hindi stream must be first", "Verified Hindi FHD", sorted[0].name)
        assertEquals("Hindi stream must be second (ahead of English)", "Hindi 720p", sorted[1].name)
        assertEquals("English stream must be third", "English FHD", sorted[2].name)
    }

    @Test
    fun testProviderManagementStats() {
        val manager = StreamingSourceManager.getInstance()
        val testProviderId = "test_provider_123"

        // Initial stats
        val initialStats = manager.getProviderStats(testProviderId)
        assertEquals(0L, initialStats.totalRequests)

        // Record successful request (150ms)
        manager.recordRequestOutcome(testProviderId, 150L, true)
        val statsAfterSuccess = manager.getProviderStats(testProviderId)
        assertEquals(1L, statsAfterSuccess.totalRequests)
        assertEquals(1L, statsAfterSuccess.successfulRequests)
        assertEquals(150L, statsAfterSuccess.averageResponseTimeMs)
        assertEquals(ProviderStatus.ACTIVE, statsAfterSuccess.health.status)

        // Record failed request (500ms, timeout)
        manager.recordRequestOutcome(testProviderId, 500L, false, "Connection timeout")
        val statsAfterFail = manager.getProviderStats(testProviderId)
        assertEquals(2L, statsAfterFail.totalRequests)
        assertEquals(1L, statsAfterFail.successfulRequests)
        assertEquals(325L, statsAfterFail.averageResponseTimeMs) // (150 + 500) / 2
        assertEquals(ProviderStatus.DEGRADED, statsAfterFail.health.status)
        assertEquals("Connection timeout", statsAfterFail.lastError)
    }

    @Test
    fun testStreamValidationExpiredTokenDetection() = runBlocking {
        // Expired unix timestamp: 1000000000 (year 2001)
        val expiredUrl = "https://cdn.example.com/stream.mkv?exp=1000000000"
        val result = PlayerPlaybackNetworking.validateStreamSource(expiredUrl)

        assertFalse("Expired token URL must fail validation", result.isValid)
        assertEquals(410, result.statusCode)
        assertTrue(result.errorMessage?.contains("Expired") == true)
    }
}
