package com.euthopiar.ui.player.sequential

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@UnstableApi
class UniversalSafeExtractorsFactoryTest {

    @Test
    fun `UniversalSafeSeekMap is always seekable`() {
        val dummyDelegate = object : SeekMap {
            override fun isSeekable(): Boolean = false
            override fun getDurationUs(): Long = C.TIME_UNSET
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                SeekMap.SeekPoints(SeekPoint.START)
        }

        val safeSeekMap = UniversalSafeSeekMap(dummyDelegate) { 120_000L }
        assertTrue(safeSeekMap.isSeekable)
    }

    @Test
    fun `UniversalSafeSeekMap falls back to durationMsProvider when delegate duration is unset`() {
        val dummyDelegate = object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = C.TIME_UNSET
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                SeekMap.SeekPoints(SeekPoint.START)
        }

        val safeSeekMap = UniversalSafeSeekMap(dummyDelegate) { 60_000L } // 60 seconds
        assertEquals(60_000_000L, safeSeekMap.durationUs)
    }

    @Test
    fun `UniversalSafeSeekMap never returns zero seek point for non-zero seek requests`() {
        // Simulating the PlavioHD MatroskaExtractor bug:
        // Cues are at tail/missing, so delegate returns SeekPoint.START (0, 0) for forward skip (e.g. 15s)
        val faultyDelegate = object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = 100_000_000L
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                SeekMap.SeekPoints(SeekPoint.START)
        }

        val safeSeekMap = UniversalSafeSeekMap(faultyDelegate) { 100_000L }
        val targetSeekTimeUs = 15_000_000L // 15 seconds

        val points = safeSeekMap.getSeekPoints(targetSeekTimeUs)
        val p1 = points.first

        // Must NOT snap back to 0!
        assertTrue("Seek timeUs must not be zero for a non-zero seek request", p1.timeUs > 0L)
        assertEquals(targetSeekTimeUs, p1.timeUs)
        assertTrue("Seek position must be positive to prevent stream rewind", p1.position >= 1L)
    }

    @Test
    fun `UniversalSafeSeekMap allows zero seek when explicitly seeking to beginning`() {
        val normalDelegate = object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = 100_000_000L
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                SeekMap.SeekPoints(SeekPoint.START)
        }

        val safeSeekMap = UniversalSafeSeekMap(normalDelegate) { 100_000L }
        val points = safeSeekMap.getSeekPoints(0L)
        assertEquals(0L, points.first.timeUs)
    }

    @Test
    fun `UniversalSafeSeekMap preserves valid non-zero seek points from healthy extractors`() {
        val expectedPoint = SeekPoint(30_000_000L, 5_000_000L)
        val healthyDelegate = object : SeekMap {
            override fun isSeekable(): Boolean = true
            override fun getDurationUs(): Long = 100_000_000L
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints =
                SeekMap.SeekPoints(expectedPoint)
        }

        val safeSeekMap = UniversalSafeSeekMap(healthyDelegate) { 100_000L }
        val points = safeSeekMap.getSeekPoints(30_000_000L)
        assertEquals(expectedPoint.timeUs, points.first.timeUs)
        assertEquals(expectedPoint.position, points.first.position)
    }

    @Test
    fun `UniversalSafeExtractorsFactory creates extractors array successfully`() {
        val factory = UniversalSafeExtractorsFactory { 60_000L }
        val extractors = factory.createExtractors()
        assertNotNull(extractors)
        assertTrue("Extractors array should not be empty", extractors.isNotEmpty())
    }
}
