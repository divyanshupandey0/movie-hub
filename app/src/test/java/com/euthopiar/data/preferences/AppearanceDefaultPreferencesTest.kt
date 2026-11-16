package com.euthopiar.data.preferences

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.euthopiar.core.model.ContentRegionPreset
import com.euthopiar.core.util.TmdbBridge
import com.euthopiar.ui.longpress.LongPressSheetStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppearanceDefaultPreferencesTest {

    private lateinit var context: Context
    private lateinit var repository: PreferencesRepository

    @Before
    fun setup() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        repository = PreferencesRepository.getInstance(context)
        repository.clearAll()
        TmdbBridge.activeRegionPreset = ContentRegionPreset.INDIA_ALL
    }

    @After
    fun tearDown() = runBlocking {
        repository.clearAll()
        TmdbBridge.activeRegionPreset = ContentRegionPreset.INDIA_ALL
    }

    @Test
    fun testDefaultAppearanceFallbacksBeforeDataStoreInit() = runBlocking {
        // Even before initializeDefaultAppearancePreferences() writes to disk,
        // in-memory flow fallbacks must match the exact required defaults
        assertEquals(ContentRegionPreset.INDIA_ALL, repository.contentRegionPreset.first())
        assertEquals(CardScaleMode.COMPACT, repository.cardScaleMode.first())
        assertEquals(SectionLabelStyle.AccentBar, repository.sectionLabelStyle.first())
        assertEquals(Top10Style.PosterTinted, repository.top10Style.first())
        assertEquals(LongPressSheetStyle.GLASS_MENU, repository.longPressStyle.first())
        assertFalse(repository.reduceMotion.first())
        assertTrue(repository.amoledMode.first())
        assertEquals("Torrentio", repository.activeProvider.first())
        assertEquals("hi", repository.preferredAudioLanguage.first())
        assertEquals("en", repository.secondaryAudioLanguage.first())
        assertTrue(repository.autoSourceSelection.first())
        assertTrue(repository.smartFailoverEnabled.first())
        assertEquals(ContentRegionPreset.INDIA_ALL, TmdbBridge.activeRegionPreset)
    }

    @Test
    fun testInitializeDefaultAppearancePreferences_freshUser() = runBlocking {
        repository.initializeDefaultAppearancePreferences()

        assertEquals(ContentRegionPreset.INDIA_ALL, repository.contentRegionPreset.first())
        assertEquals(CardScaleMode.COMPACT, repository.cardScaleMode.first())
        assertEquals(SectionLabelStyle.AccentBar, repository.sectionLabelStyle.first())
        assertEquals(Top10Style.PosterTinted, repository.top10Style.first())
        assertEquals(LongPressSheetStyle.GLASS_MENU, repository.longPressStyle.first())
        assertFalse(repository.reduceMotion.first())
        assertTrue(repository.amoledMode.first())
        assertEquals("Torrentio", repository.activeProvider.first())
        assertEquals("hi", repository.preferredAudioLanguage.first())
        assertEquals("en", repository.secondaryAudioLanguage.first())
        assertTrue(repository.autoSourceSelection.first())
        assertTrue(repository.smartFailoverEnabled.first())
        assertEquals(ContentRegionPreset.INDIA_ALL, TmdbBridge.activeRegionPreset)
    }

    @Test
    fun testInitializeDefaultAppearancePreferences_existingUserPreserved() = runBlocking {
        // Pre-configure existing user preferences
        repository.setContentRegionPreset(ContentRegionPreset.UNITED_STATES)
        repository.setCardScaleMode(CardScaleMode.FLAGSHIP)
        repository.setSectionLabelStyle(SectionLabelStyle.Eyebrow)
        repository.setTop10Style(Top10Style.GlassTab)
        repository.setLongPressStyle(LongPressSheetStyle.HERO_PEEK)
        repository.setReduceMotion(true)
        repository.setAmoledMode(false)
        repository.setActiveProvider("Comet")
        repository.setPreferredAudioLanguage("ja")
        repository.setSecondaryAudioLanguage("es")
        repository.setAutoSourceSelection(false)
        repository.setSmartFailoverEnabled(false)

        // Call initializeDefaultAppearancePreferences() (e.g. app update or restart)
        repository.initializeDefaultAppearancePreferences()

        // Existing preferences must NOT be overwritten
        assertEquals(ContentRegionPreset.UNITED_STATES, repository.contentRegionPreset.first())
        assertEquals(CardScaleMode.FLAGSHIP, repository.cardScaleMode.first())
        assertEquals(SectionLabelStyle.Eyebrow, repository.sectionLabelStyle.first())
        assertEquals(Top10Style.GlassTab, repository.top10Style.first())
        assertEquals(LongPressSheetStyle.HERO_PEEK, repository.longPressStyle.first())
        assertTrue(repository.reduceMotion.first())
        assertFalse(repository.amoledMode.first())
        assertEquals("Comet", repository.activeProvider.first())
        assertEquals("ja", repository.preferredAudioLanguage.first())
        assertEquals("es", repository.secondaryAudioLanguage.first())
        assertFalse(repository.autoSourceSelection.first())
        assertFalse(repository.smartFailoverEnabled.first())
        assertEquals(ContentRegionPreset.UNITED_STATES, TmdbBridge.activeRegionPreset)
    }

    @Test
    fun testInitializeDefaultAppearancePreferences_partialCustomization() = runBlocking {
        // User customized only CardScaleMode and ReduceMotion
        repository.setCardScaleMode(CardScaleMode.BALANCED)
        repository.setReduceMotion(true)

        // Call initializeDefaultAppearancePreferences()
        repository.initializeDefaultAppearancePreferences()

        // Customized values must be preserved
        assertEquals(CardScaleMode.BALANCED, repository.cardScaleMode.first())
        assertTrue(repository.reduceMotion.first())

        // Missing values must receive default settings
        assertEquals(ContentRegionPreset.INDIA_ALL, repository.contentRegionPreset.first())
        assertEquals(SectionLabelStyle.AccentBar, repository.sectionLabelStyle.first())
        assertEquals(Top10Style.PosterTinted, repository.top10Style.first())
        assertEquals(LongPressSheetStyle.GLASS_MENU, repository.longPressStyle.first())
        assertTrue(repository.amoledMode.first())
    }
}
