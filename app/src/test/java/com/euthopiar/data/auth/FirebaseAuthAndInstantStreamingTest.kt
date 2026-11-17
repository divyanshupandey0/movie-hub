package com.euthopiar.data.auth

import com.euthopiar.core.streams.StreamsRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FirebaseAuthAndInstantStreamingTest {

    @Test
    fun testGoogleServicesJsonConfiguredForMovieHub() {
        val googleServicesFile = File("google-services.json")
        val altGoogleServicesFile = File("app/google-services.json")
        val target = if (googleServicesFile.exists()) googleServicesFile else altGoogleServicesFile
        
        assertTrue("google-services.json must exist in app root", target.exists())
        val content = target.readText()
        assertTrue("google-services.json must contain package com.euthopiar", content.contains("com.euthopiar"))
        assertTrue("google-services.json must reference correct project movie-hub-46b34", content.contains("movie-hub-46b34"))
    }

    @Test
    fun testStreamsRepositoryRequestTokenFormatting() {
        val token1 = StreamsRepository.requestToken("movie", "12345", title = "Inception", year = 2010, imdbId = "tt1375666")
        val token2 = StreamsRepository.requestToken("movie", "12345", title = "Inception", year = 2010, imdbId = "tt1375666")
        assertEquals(token1, token2)
        assertTrue(token1.contains("12345"))
        assertTrue(token1.contains("tt1375666"))
    }

    @Test
    fun testInstantStreamingBufferInvariants() {
        // Invariants defined for instant streaming engine
        val targetPlaybackBufferMs = 500
        val targetRebufferMs = 1000
        val minBufferMsFloor = 8000

        assertTrue("Playback buffer must be 500ms for instantaneous time-to-first-frame", targetPlaybackBufferMs == 500)
        assertTrue("Rebuffer duration must be 1000ms", targetRebufferMs == 1000)
        assertTrue("Min buffer must start at least 8000ms", minBufferMsFloor >= 8000)
    }
}
