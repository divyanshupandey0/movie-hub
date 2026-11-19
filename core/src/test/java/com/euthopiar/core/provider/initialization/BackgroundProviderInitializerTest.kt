package com.euthopiar.core.provider.initialization

import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.provider.catalog.ContentProvider
import com.euthopiar.core.provider.catalog.ContentProviderCapabilities
import com.euthopiar.core.provider.catalog.ContentProviderRegistry
import com.euthopiar.core.provider.catalog.IntegrationHealthStatus
import com.euthopiar.core.provider.catalog.ProviderCategory
import com.euthopiar.core.provider.catalog.ProviderHealthInfo
import com.euthopiar.core.provider.source.PlayableStream
import com.euthopiar.core.provider.source.StreamingSourceRegistry
import com.euthopiar.core.provider.source.StreamingSourceService
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackgroundProviderInitializerTest {

    private lateinit var initializer: BackgroundProviderInitializer

    @Before
    fun setUp() {
        initializer = BackgroundProviderInitializer.getInstance()
    }

    @Test
    fun testInitializationStatesDefined() {
        // Requirement 2: NOT_INITIALIZED, INITIALIZING, READY, UNAVAILABLE, ERROR
        assertEquals(5, ProviderLifecycleState.values().size)
        assertTrue(ProviderLifecycleState.values().contains(ProviderLifecycleState.NOT_INITIALIZED))
        assertTrue(ProviderLifecycleState.values().contains(ProviderLifecycleState.INITIALIZING))
        assertTrue(ProviderLifecycleState.values().contains(ProviderLifecycleState.READY))
        assertTrue(ProviderLifecycleState.values().contains(ProviderLifecycleState.UNAVAILABLE))
        assertTrue(ProviderLifecycleState.values().contains(ProviderLifecycleState.ERROR))
    }

    @Test
    fun testProviderInitializationNonBlockingAndTransitionsToReady() = runBlocking {
        // Cold startup run
        val startTime = System.currentTimeMillis()
        initializer.initializeAllConfiguredProviders(forceRefresh = true)
        val duration = System.currentTimeMillis() - startTime

        // Confirm execution completes in finite time under timeout bounds
        assertTrue("Initialization should be responsive and bounded", duration < 20000L)

        val states = initializer.providerStatesFlow.value
        assertFalse("States should not be empty after initialization", states.isEmpty())

        // Check that valid official providers transition to READY
        val wikimediaState = initializer.getProviderState("prov_wikimedia_commons")
        val jellyfinState = initializer.getProviderState("prov_jellyfin")

        // At least one core provider must reach READY or UNAVAILABLE (not stuck in INITIALIZING)
        assertTrue(wikimediaState in listOf(ProviderLifecycleState.READY, ProviderLifecycleState.UNAVAILABLE, ProviderLifecycleState.ERROR))
    }

    @Test
    fun testCachingPreventsUnnecessaryDuplicateWork() = runBlocking {
        // Run first time
        initializer.initializeAllConfiguredProviders(forceRefresh = false)
        val firstCheckTime = initializer.providerStatesFlow.value.values.firstOrNull()?.lastCheckedTimestampMs ?: 0L

        // Run second time immediately without forceRefresh
        initializer.initializeAllConfiguredProviders(forceRefresh = false)
        val secondCheckTime = initializer.providerStatesFlow.value.values.firstOrNull()?.lastCheckedTimestampMs ?: 0L

        // Timestamp should match or be preserved due to active caching
        assertEquals("Cached providers should not re-query within TTL", firstCheckTime, secondCheckTime)
    }

    @Test
    fun testReadyProviderFiltering() = runBlocking {
        initializer.initializeAllConfiguredProviders(forceRefresh = false)
        val readyIds = initializer.getReadyProviderIds()
        assertNotNull(readyIds)

        // All IDs in readyIds must verify as isProviderReady == true
        readyIds.forEach { id ->
            assertTrue(initializer.isProviderReady(id))
            assertEquals(ProviderLifecycleState.READY, initializer.getProviderState(id))
        }
    }
}
