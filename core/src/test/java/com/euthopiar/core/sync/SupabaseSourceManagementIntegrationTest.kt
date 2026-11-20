package com.euthopiar.core.sync

import com.euthopiar.core.sync.supabase.AppConfigEntry
import com.euthopiar.core.sync.supabase.RemotePlaybackReportEntry
import com.euthopiar.core.sync.supabase.RemotePlaybackSessionEntry
import com.euthopiar.core.sync.supabase.RemoteSourceAnalyticsEntry
import com.euthopiar.core.sync.supabase.RemoteSourceEntry
import com.euthopiar.core.sync.supabase.RemoteSourceFailureEntry
import com.euthopiar.core.sync.supabase.RemoteSourceHealthEntry
import com.euthopiar.core.sync.supabase.SupabaseSourceManagementClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseSourceManagementIntegrationTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testAppConfigEntrySerialization() {
        val configObj = buildJsonObject {
            put("max_resolution", "1080p")
            put("allow_4k", false)
            put("hindi_first_priority", true)
        }
        val entry = AppConfigEntry(
            key = "streaming_policy",
            value = configObj,
            description = "Global streaming policy"
        )
        val serialized = json.encodeToString(entry)
        assertTrue(serialized.contains("streaming_policy"))
        assertTrue(serialized.contains("1080p"))

        val deserialized = json.decodeFromString<AppConfigEntry>(serialized)
        assertEquals("streaming_policy", deserialized.key)
        assertEquals("1080p", deserialized.value["max_resolution"]?.toString()?.replace("\"", ""))
    }

    @Test
    fun testRemoteSourceEntryHindiAttributesSerialization() {
        val source = RemoteSourceEntry(
            id = "src_123",
            providerId = "torrentio",
            mediaId = "tt1234567",
            season = 1,
            episode = 2,
            title = "Sample Show S01E02",
            url = "https://cdn.example.com/stream.m3u8",
            format = "hls",
            quality = "1080p",
            audioLanguages = listOf("hi", "en"),
            hasHindiAudio = true,
            isHindiDubbed = true,
            isMultiAudio = true,
            isEnabled = true,
            priorityScore = 100
        )
        val encoded = json.encodeToString(source)
        assertTrue(encoded.contains("\"has_hindi_audio\":true"))
        assertTrue(encoded.contains("\"is_hindi_dubbed\":true"))
        assertTrue(encoded.contains("\"priority_score\":100"))

        val decoded = json.decodeFromString<RemoteSourceEntry>(encoded)
        assertEquals("src_123", decoded.id)
        assertTrue(decoded.hasHindiAudio)
        assertTrue(decoded.isHindiDubbed)
        assertEquals(1, decoded.season)
        assertEquals(2, decoded.episode)
    }

    @Test
    fun testRemotePlaybackSessionEntrySourceLocking() {
        val session = RemotePlaybackSessionEntry(
            id = "sess_987654321",
            mediaId = "movie_555",
            activeSourceId = "src_cdn_1",
            activeProviderId = "plaviohd",
            selectedAudioLanguage = "hi",
            selectedQuality = "1080p",
            playbackPositionMs = 45000L,
            durationMs = 7200000L,
            isLocked = true
        )
        val encoded = json.encodeToString(session)
        assertTrue(encoded.contains("\"is_locked\":true"))
        assertTrue(encoded.contains("\"active_source_id\":\"src_cdn_1\""))

        val decoded = json.decodeFromString<RemotePlaybackSessionEntry>(encoded)
        assertEquals("sess_987654321", decoded.id)
        assertTrue(decoded.isLocked)
        assertEquals(45000L, decoded.playbackPositionMs)
    }

    @Test
    fun testRemoteOperationalTelemetrySerialization() {
        val failure = RemoteSourceFailureEntry(
            providerId = "castle_tv",
            mediaId = "tt9999999",
            errorCode = "HTTP_404",
            errorMessage = "Root endpoint not found; restricted encryption"
        )
        val failureJson = json.encodeToString(failure)
        assertTrue(failureJson.contains("HTTP_404"))

        val analytics = RemoteSourceAnalyticsEntry(
            eventType = "discovery_timing",
            providerId = "torrentio",
            durationMs = 1250L,
            success = true,
            networkType = "4G_STRONG",
            effectiveBandwidthKbps = 15000L
        )
        val analyticsJson = json.encodeToString(analytics)
        assertTrue(analyticsJson.contains("discovery_timing"))
        assertTrue(analyticsJson.contains("4G_STRONG"))

        val report = RemotePlaybackReportEntry(
            sessionId = "sess_1",
            mediaId = "movie_1",
            bufferingCount = 0,
            totalBufferingTimeMs = 0L,
            switchedSources = false
        )
        val reportJson = json.encodeToString(report)
        assertTrue(reportJson.contains("\"switched_sources\":false"))
    }

    @Test
    fun testSupabaseSourceManagementClientOfflineFallback() = runBlocking {
        val client = SupabaseSourceManagementClient.getInstance()
        assertNotNull(client)

        // Offline / default provider check returns true for approved providers and false for unsupported
        val isTorrentioEnabled = client.isProviderEnabledRemotely("torrentio")
        val isCastleEnabled = client.isProviderEnabledRemotely("castle_tv")
        val isHdHubEnabled = client.isProviderEnabledRemotely("hdhub")

        assertTrue("Torrentio should be enabled by default", isTorrentioEnabled)
        assertFalse("Castle TV must be disabled by default due to proprietary encryption", isCastleEnabled)
        assertFalse("HDHub must be disabled by default due to Cloudflare anti-bot checks", isHdHubEnabled)
    }
}
