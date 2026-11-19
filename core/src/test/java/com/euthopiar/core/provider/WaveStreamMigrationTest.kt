package com.euthopiar.core.provider

import com.euthopiar.core.model.ContentType
import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.provider.catalog.AuditedInventoryCatalog
import com.euthopiar.core.provider.catalog.ContentProviderRegistry
import com.euthopiar.core.provider.catalog.IntegrationHealthStatus
import com.euthopiar.core.provider.catalog.ProviderCategory
import com.euthopiar.core.provider.extractor.DirectMediaExtractor
import com.euthopiar.core.provider.source.ContentIdentityVerifier
import com.euthopiar.core.provider.source.PlayableStream
import com.euthopiar.core.provider.source.SourceConfidence
import com.euthopiar.core.provider.source.StreamingSourceRegistry
import com.euthopiar.core.streams.StreamItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive verification suite for the WaveStream provider & streaming source migration.
 */
class WaveStreamMigrationTest {

    private lateinit var contentRegistry: ContentProviderRegistry
    private lateinit var sourceRegistry: StreamingSourceRegistry

    @Before
    fun setUp() {
        contentRegistry = ContentProviderRegistry.getInstance()
        sourceRegistry = StreamingSourceRegistry.getInstance()
    }

    @Test
    fun testContentProviderRegistryCategoriesAndCount() {
        val allProviders = contentRegistry.getAllProviders()
        assertTrue("Content registry must contain all 47+ inventory entries + open providers", allProviders.size >= 49)

        // Verify all 5 categories are represented
        for (category in ProviderCategory.values()) {
            val providersInCat = contentRegistry.getProvidersByCategory(category)
            assertTrue("Category $category must have registered providers", providersInCat.isNotEmpty())
        }

        // Verify specific category counts from AuditedInventoryCatalog
        val cat1 = contentRegistry.getProvidersByCategory(ProviderCategory.MOVIES_WEB_SERIES)
        val cat2 = contentRegistry.getProvidersByCategory(ProviderCategory.ANIME_DONGHUA)
        val cat3 = contentRegistry.getProvidersByCategory(ProviderCategory.KIDS_CARTOONS)
        val cat4 = contentRegistry.getProvidersByCategory(ProviderCategory.REGIONAL_DRAMA)
        val cat5 = contentRegistry.getProvidersByCategory(ProviderCategory.MULTI_SOURCE_TOOLS)

        assertTrue(cat1.size >= 18)
        assertTrue(cat2.size >= 13)
        assertTrue(cat3.size >= 6)
        assertTrue(cat4.size >= 6)
        assertTrue(cat5.size >= 4)
    }

    @Test
    fun testStreamingSourceRegistryArchitecture() {
        val allServices = sourceRegistry.getAllServices()
        // Registry must strictly contain streaming source services, not catalog scrapers
        val allowedServiceIds = setOf(
            "src_plaviohd",
            "src_torrentio",
            "src_mediafusion",
            "src_wikimedia_commons",
            "src_custom_stream",
            "src_jellyfin"
        )

        assertEquals("StreamingSourceRegistry must strictly contain the 6 authorized stream source services", 6, allServices.size)
        allServices.forEach { service ->
            assertTrue("Service ${service.id} must be in authorized whitelist", allowedServiceIds.contains(service.id))
        }

        val enabledServices = sourceRegistry.getEnabledServices()
        assertTrue("Active services must be available for stream resolution", enabledServices.isNotEmpty())
        // Castle TV or scraper .cs3 files must NEVER be registered in StreamingSourceRegistry
        assertFalse(allServices.any { it.id.contains("castle") || it.id.contains("vegamovies") })
    }

    @Test
    fun testStrictContentIdentityVerifier_MovieContradiction() {
        val movieHints = MatchHints(
            tmdbId = 27205,
            imdbId = "tt1375666",
            type = ContentType.MOVIE,
            titles = setOf("Inception"),
            year = 2010
        )

        // Contradiction: TV markers in stream title for a movie target
        val tvMarkerStream = StreamItem(
            name = "Inception S01E01 1080p WEB-DL",
            url = "https://example.com/inception_s01e01.mp4",
            addonName = "TestAddon",
            addonId = "test_addon"
        )
        val tvMarkerDecision = ContentIdentityVerifier.verify(movieHints, tvMarkerStream)
        assertEquals(SourceConfidence.REJECTED, tvMarkerDecision.confidence)
        assertTrue(tvMarkerDecision.reason.contains("TV season/episode markers found"))

        // Contradiction: Unrelated movie (wrong title similarity)
        val wrongMovieStream = StreamItem(
            name = "Interstellar (2014) 1080p BluRay",
            url = "https://example.com/interstellar.mp4",
            addonName = "TestAddon",
            addonId = "test_addon"
        )
        val wrongMovieDecision = ContentIdentityVerifier.verify(movieHints, wrongMovieStream)
        assertEquals(SourceConfidence.REJECTED, wrongMovieDecision.confidence)

        // Exact match
        val exactMovieStream = StreamItem(
            name = "Inception.2010.1080p.BluRay.x264.Hindi.English",
            url = "https://example.com/inception_2010.m3u8",
            addonName = "TestAddon",
            addonId = "test_addon"
        )
        val exactDecision = ContentIdentityVerifier.verify(movieHints, exactMovieStream)
        assertEquals(SourceConfidence.VERIFIED, exactDecision.confidence)
        assertTrue(exactDecision.score >= 0.80)
    }

    @Test
    fun testStrictContentIdentityVerifier_SeriesAndEpisodeContradiction() {
        val tvHints = MatchHints(
            tmdbId = 66732,
            imdbId = "tt4574334",
            type = ContentType.TV_SERIES,
            titles = setOf("Stranger Things"),
            year = 2016,
            season = 2,
            episode = 4
        )

        // Season mismatch: requested S2 but stream is S1
        val wrongSeasonStream = StreamItem(
            name = "Stranger Things S01E04 Chapter Four 1080p",
            url = "https://example.com/st_s01e04.mp4",
            addonName = "TestAddon",
            addonId = "test_addon"
        )
        val seasonDecision = ContentIdentityVerifier.verify(tvHints, wrongSeasonStream)
        assertEquals(SourceConfidence.REJECTED, seasonDecision.confidence)
        assertTrue(seasonDecision.reason.contains("Season mismatch"))

        // Episode mismatch: requested E4 but stream is E2
        val wrongEpStream = StreamItem(
            name = "Stranger Things S02E02 Chapter Two 1080p",
            url = "https://example.com/st_s02e02.mp4",
            addonName = "TestAddon",
            addonId = "test_addon"
        )
        val epDecision = ContentIdentityVerifier.verify(tvHints, wrongEpStream)
        assertEquals(SourceConfidence.REJECTED, epDecision.confidence)
        assertTrue(epDecision.reason.contains("Episode mismatch"))

        // Exact TV episode match
        val exactEpStream = StreamItem(
            name = "Stranger Things S02E04 Will the Wise 1080p NF WEB-DL Dual Audio Hindi-English",
            url = "https://example.com/st_s02e04.m3u8",
            addonName = "TestAddon",
            addonId = "test_addon"
        )
        val exactEpDecision = ContentIdentityVerifier.verify(tvHints, exactEpStream)
        assertEquals(SourceConfidence.VERIFIED, exactEpDecision.confidence)
    }

    @Test
    fun testPlayableStreamAutoplayEligibilityTiers() {
        val verifiedStream = PlayableStream(
            url = "https://example.com/verified.m3u8",
            format = StreamContainer.HLS,
            verificationConfidence = SourceConfidence.VERIFIED,
            matchScore = 0.95
        )
        assertTrue(verifiedStream.isPlayable)
        assertTrue("VERIFIED stream must be eligible for autoplay", verifiedStream.isAutoplayEligible)

        val needsReviewStream = PlayableStream(
            url = "https://example.com/needs_review.mp4",
            format = StreamContainer.MP4,
            verificationConfidence = SourceConfidence.NEEDS_REVIEW,
            matchScore = 0.65
        )
        assertTrue(needsReviewStream.isPlayable)
        assertFalse("NEEDS_REVIEW stream must NOT autoplay without user review", needsReviewStream.isAutoplayEligible)

        val unverifiedStream = PlayableStream(
            url = "https://example.com/unverified.mp4",
            format = StreamContainer.MP4,
            verificationConfidence = SourceConfidence.UNVERIFIED,
            matchScore = 0.40
        )
        assertTrue(unverifiedStream.isPlayable)
        assertFalse("UNVERIFIED stream must NOT autoplay", unverifiedStream.isAutoplayEligible)

        val rejectedStream = PlayableStream(
            url = "https://example.com/rejected.mp4",
            format = StreamContainer.MP4,
            verificationConfidence = SourceConfidence.REJECTED,
            matchScore = 0.0
        )
        assertFalse("REJECTED stream must not be playable", rejectedStream.isPlayable)
        assertFalse("REJECTED stream must NOT autoplay", rejectedStream.isAutoplayEligible)
    }

    @Test
    fun testAuditedInventoryStatusesAndReasons() {
        val entries = AuditedInventoryCatalog.ALL_ENTRIES
        assertTrue("Inventory must have at least 47 audited entries", entries.size >= 47)

        // Castle TV verification
        val castle = entries.firstOrNull { it.id == "prov_castle_tv" }
        assertNotNull(castle)
        assertEquals(IntegrationHealthStatus.DISABLED, castle?.technicalStatus)
        assertTrue(castle?.reasonForStatus?.contains("encryption") == true)

        // Check that only natively supported aggregator (Stremio Addon Engine) is ACTIVE; all scrapers are not ACTIVE
        val activeEntries = entries.filter { it.technicalStatus == IntegrationHealthStatus.ACTIVE }
        assertEquals(1, activeEntries.size)
        assertEquals("Stremio Addon Engine", activeEntries[0].name)

        val scraperEntries = entries.filter { it.category != ProviderCategory.MULTI_SOURCE_TOOLS }
        assertTrue("Audited scrapers must not be marked ACTIVE without verification", scraperEntries.none { it.technicalStatus == IntegrationHealthStatus.ACTIVE })

        // Verify that entries have documented reasons
        entries.forEach { entry ->
            assertTrue("Entry ${entry.name} must have a non-empty reason for status", entry.reasonForStatus.isNotBlank())
            assertTrue("Entry ${entry.name} must have a non-empty upstream endpoint or site", entry.upstreamEndpointOrSite.isNotBlank())
        }
    }

    @Test
    fun testMediaExtractorDecoupling() = runBlocking {
        val extractor = DirectMediaExtractor()
        assertTrue(extractor.canExtract("https://example.com/video/master.m3u8"))
        assertTrue(extractor.canExtract("https://example.com/stream.mp4?token=abc"))
        assertTrue(extractor.canExtract("https://example.com/manifest.mpd"))
        assertFalse(extractor.canExtract("https://example.com/landing_page.html"))

        val results = extractor.extract("https://example.com/video/master.m3u8")
        assertEquals(1, results.size)
        assertEquals(StreamContainer.HLS, results[0].container)
        assertEquals("https://example.com/video/master.m3u8", results[0].url)
    }
}
