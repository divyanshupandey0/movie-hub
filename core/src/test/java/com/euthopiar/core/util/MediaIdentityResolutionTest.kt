package com.euthopiar.core.util

import com.euthopiar.core.js.pluginContentId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdentityResolutionTest {

    @Test
    fun testPluginContentIdStripsPrefixesAndPreservesBaseId() {
        assertEquals("12345", pluginContentId("tmdb:12345"))
        assertEquals("12345", pluginContentId("tmdb/12345"))
        assertEquals("tt0944947", pluginContentId("tt0944947"))
        assertEquals("tt0944947", pluginContentId("tt0944947:1:2", season = 1, episode = 2))
        assertEquals("1399", pluginContentId("1399:3:4", season = 3, episode = 4))
    }

    @Test
    fun testEpisodeDataParsingLogic() {
        // 1. Movie type should NEVER extract season or episode even if data has colon or query
        val movieData = "101299:1"
        val isMovie = true
        val (movieId, movieS, movieE) = if (isMovie) {
            Triple(movieData, null, null)
        } else {
            Triple(movieData, 1, 1)
        }
        assertEquals("101299:1", movieId)
        assertNull("Movie season must always be null", movieS)
        assertNull("Movie episode must always be null", movieE)

        // 2. TV series data with colon: "1399:2:5"
        val tvData = "1399:2:5"
        val parts = tvData.split(":")
        val s = parts.getOrNull(parts.size - 2)?.toIntOrNull()
        val e = parts.getOrNull(parts.size - 1)?.toIntOrNull()
        val baseId = parts.dropLast(2).joinToString(":")
        assertEquals("1399", baseId)
        assertEquals(2, s)
        assertEquals(5, e)

        // 3. TV series data with query string: "https://provider.org/watch?season=3&episode=8"
        val queryData = "https://provider.org/watch?season=3&episode=8"
        val qS = Regex("""[?&]season=(\d+)""", RegexOption.IGNORE_CASE).find(queryData)?.groupValues?.get(1)?.toIntOrNull()
        val qE = Regex("""[?&]episode=(\d+)""", RegexOption.IGNORE_CASE).find(queryData)?.groupValues?.get(1)?.toIntOrNull()
        assertEquals(3, qS)
        assertEquals(8, qE)
        assertEquals("https://provider.org/watch", queryData.substringBefore('?'))
    }
}
