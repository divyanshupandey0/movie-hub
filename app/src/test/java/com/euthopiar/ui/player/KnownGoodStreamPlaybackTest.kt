package com.euthopiar.ui.player

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.euthopiar.core.model.StreamContainer
import com.euthopiar.core.model.StreamSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class KnownGoodStreamPlaybackTest {

    private lateinit var application: Application
    private lateinit var viewModel: PlayerViewModel

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        viewModel = PlayerViewModel(application)
    }

    @Test
    fun testKnownGoodHlsStreamSelectionAndMediaSourcePreparation() {
        val hlsServer = ServerSourceOption(
            id = "known_good_hls",
            label = "Test HLS 1080p",
            serverName = "MuxHls",
            resolutionLabel = "1080p",
            streamSource = StreamSource(
                url = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8",
                quality = "1080p",
                isM3u8 = true,
                container = StreamContainer.HLS,
                headers = mapOf("User-Agent" to "ExoPlayerTest")
            )
        )

        viewModel.selectServer(hlsServer, isUserExplicit = true)

        val selected = viewModel.uiState.value.selectedServer
        assertNotNull("Server should be selected", selected)
        assertEquals("MuxHls", selected?.serverName)
        assertEquals("https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8", selected?.streamSource?.url)
        assertEquals(true, selected?.streamSource?.isM3u8)
        assertNull("No user error should be displayed on selection", viewModel.uiState.value.userError)
    }

    @Test
    fun testKnownGoodMp4ProgressiveStreamSelection() {
        val mp4Server = ServerSourceOption(
            id = "known_good_mp4",
            label = "Google MP4 720p",
            serverName = "GoogleStorage",
            resolutionLabel = "720p",
            streamSource = StreamSource(
                url = "https://storage.googleapis.com/exoplayer-test-media-1/mp4/dizzy-with-tx3g.mp4",
                quality = "720p",
                isM3u8 = false,
                container = StreamContainer.MP4
            )
        )

        viewModel.selectServer(mp4Server, isUserExplicit = true)

        val selected = viewModel.uiState.value.selectedServer
        assertNotNull("Server should be selected", selected)
        assertEquals("GoogleStorage", selected?.serverName)
        assertEquals(StreamContainer.MP4, selected?.streamSource?.container)
        assertNull("No user error should be displayed on selection", viewModel.uiState.value.userError)
    }

    @Test
    fun testKnownGoodDashStreamSelection() {
        val dashServer = ServerSourceOption(
            id = "known_good_dash",
            label = "Akamai DASH",
            serverName = "AkamaiTest",
            resolutionLabel = "1080p",
            streamSource = StreamSource(
                url = "https://dash.akamaized.net/akamai/test/sample.mpd",
                quality = "1080p",
                isM3u8 = false,
                container = StreamContainer.DASH
            )
        )

        viewModel.selectServer(dashServer, isUserExplicit = true)

        val selected = viewModel.uiState.value.selectedServer
        assertNotNull("Server should be selected", selected)
        assertEquals("AkamaiTest", selected?.serverName)
        assertEquals(StreamContainer.DASH, selected?.streamSource?.container)
    }
}
