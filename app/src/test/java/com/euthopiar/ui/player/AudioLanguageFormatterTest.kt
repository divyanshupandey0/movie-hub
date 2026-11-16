package com.euthopiar.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLanguageFormatterTest {

    @Test
    fun `resolveLanguageName resolves ISO 639-1 and 639-2 codes correctly`() {
        assertEquals("Hindi", AudioLanguageFormatter.resolveLanguageName("hi", null))
        assertEquals("Hindi", AudioLanguageFormatter.resolveLanguageName("hin", null))
        assertEquals("Tamil", AudioLanguageFormatter.resolveLanguageName("ta", null))
        assertEquals("Tamil", AudioLanguageFormatter.resolveLanguageName("tam", null))
        assertEquals("Telugu", AudioLanguageFormatter.resolveLanguageName("te", null))
        assertEquals("Telugu", AudioLanguageFormatter.resolveLanguageName("tel", null))
        assertEquals("Malayalam", AudioLanguageFormatter.resolveLanguageName("ml", null))
        assertEquals("Malayalam", AudioLanguageFormatter.resolveLanguageName("mal", null))
        assertEquals("Bengali", AudioLanguageFormatter.resolveLanguageName("bn", null))
        assertEquals("Bengali", AudioLanguageFormatter.resolveLanguageName("ben", null))
        assertEquals("English", AudioLanguageFormatter.resolveLanguageName("en", null))
        assertEquals("English", AudioLanguageFormatter.resolveLanguageName("eng", null))
        assertEquals("Japanese", AudioLanguageFormatter.resolveLanguageName("ja", null))
        assertEquals("Japanese", AudioLanguageFormatter.resolveLanguageName("jpn", null))
        assertEquals("Korean", AudioLanguageFormatter.resolveLanguageName("ko", null))
        assertEquals("Korean", AudioLanguageFormatter.resolveLanguageName("kor", null))
    }

    @Test
    fun `resolveLanguageName parses raw container labels with embedded language keywords`() {
        assertEquals("Hindi", AudioLanguageFormatter.resolveLanguageName("und", "audio_0_hin_stereo"))
        assertEquals("Hindi", AudioLanguageFormatter.resolveLanguageName(null, "Hindi (Clean Audio)"))
        assertEquals("English", AudioLanguageFormatter.resolveLanguageName("", "eng-5.1-surround"))
        assertEquals("Tamil", AudioLanguageFormatter.resolveLanguageName(null, "Tamil Dubbed DD 5.1"))
        assertEquals("Telugu", AudioLanguageFormatter.resolveLanguageName(null, "Movie.Telugu.audio.aac"))
        assertEquals("Japanese", AudioLanguageFormatter.resolveLanguageName("und", "Japanese [Original Audio]"))
    }

    @Test
    fun `resolveLanguageName returns Unknown for unrecognized or missing metadata`() {
        assertEquals("Unknown", AudioLanguageFormatter.resolveLanguageName(null, null))
        assertEquals("Unknown", AudioLanguageFormatter.resolveLanguageName("", ""))
        assertEquals("Unknown", AudioLanguageFormatter.resolveLanguageName("und", "track_1"))
        assertEquals("Unknown", AudioLanguageFormatter.resolveLanguageName("auto", "audio_stream_0"))
    }

    @Test
    fun `formatTrackDisplayName formats Hindi dubbed tracks when content is non-Hindi`() {
        val displayName = AudioLanguageFormatter.formatTrackDisplayName(
            isoCode = "hi",
            rawLabel = "hin_audio",
            channelCount = 6,
            sampleMimeType = "audio/eac3",
            trackIndex = 0,
            originalLanguage = "en"
        )
        assertTrue("Expected Hindi and Dubbed in: $displayName", displayName.contains("Hindi") && displayName.contains("[Dubbed]"))
        assertTrue("Expected 5.1 and DDP in: $displayName", displayName.contains("5.1") && displayName.contains("DDP"))
    }

    @Test
    fun `formatTrackDisplayName formats clean channel count and codec names`() {
        val stereoAac = AudioLanguageFormatter.formatTrackDisplayName(
            isoCode = "en",
            rawLabel = "English",
            channelCount = 2,
            sampleMimeType = "audio/mp4a-latm",
            trackIndex = 0
        )
        assertEquals("English (Stereo \u00B7 AAC)", stereoAac)

        val surround71 = AudioLanguageFormatter.formatTrackDisplayName(
            isoCode = "ja",
            rawLabel = "Japanese [Original]",
            channelCount = 8,
            sampleMimeType = "audio/vnd.dts",
            trackIndex = 0
        )
        assertTrue(surround71.contains("Japanese [Original]"))
        assertTrue(surround71.contains("7.1 \u00B7 DTS"))
    }

    @Test
    fun `formatTrackDisplayName provides numbered fallback for Unknown tracks`() {
        val track1 = AudioLanguageFormatter.formatTrackDisplayName(
            isoCode = "und",
            rawLabel = null,
            trackIndex = 0
        )
        assertEquals("Unknown (Track 1)", track1)

        val track2 = AudioLanguageFormatter.formatTrackDisplayName(
            isoCode = null,
            rawLabel = "xyz_unknown_raw",
            trackIndex = 1
        )
        assertEquals("Unknown (Track 2)", track2)
    }
}
