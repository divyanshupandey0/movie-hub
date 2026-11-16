package com.euthopiar.core.js

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class JsRepoManifestParsingTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @Test
    fun testParseManifestWithSpaceSeparatedFields() {
        val payload = """
            {
                "name": "All-in-One-Nuvio",
                "version": "1.0.0",
                "scrapers": [
                    {
                        "id": "allanime",
                        "name": "AllAnime",
                        "description": "AllAnime - Anime and Manga content streaming",
                        "version": "1.0.0",
                        "author": "",
                        "supportedTypes": "movie tv",
                        "filename": "providers/allanime.js",
                        "enabled": true,
                        "hasSettings": true,
                        "formats": "mp4 mkv m3u8",
                        "logo": "https://i.postimg.cc/DwpqLJWV/allanime.png",
                        "contentLanguage": "en ja"
                    },
                    {
                        "id": "4khdhub",
                        "name": "4KHDHub",
                        "description": "4KHDHub direct links",
                        "version": "1.0.3",
                        "author": "Nuvio Team",
                        "supportedTypes": "movie tv",
                        "filename": "providers/4khdhub.js",
                        "enabled": true,
                        "formats": "mkv",
                        "contentLanguage": "en"
                    }
                ]
            }
        """.trimIndent()

        val manifest = json.decodeFromString<JsRepoManifest>(payload)
        assertEquals("All-in-One-Nuvio", manifest.name)
        assertEquals(2, manifest.scrapers.size)

        val s1 = manifest.scrapers[0]
        assertEquals("allanime", s1.id)
        assertEquals(listOf("movie", "tv"), s1.supportedTypes)
        assertEquals(listOf("mp4", "mkv", "m3u8"), s1.formats)
        assertEquals(listOf("en", "ja"), s1.contentLanguage)

        val s2 = manifest.scrapers[1]
        assertEquals("4khdhub", s2.id)
        assertEquals(listOf("movie", "tv"), s2.supportedTypes)
        assertEquals(listOf("mkv"), s2.formats)
        assertEquals(listOf("en"), s2.contentLanguage)
    }

    @Test
    fun testParseManifestWithArrayFields() {
        val payload = """
            {
                "name": "Yoru's Repo",
                "version": "1.0.0",
                "scrapers": [
                    {
                        "id": "animepahe",
                        "name": "AnimePahe",
                        "supportedTypes": ["movie", "tv", "anime"],
                        "filename": "providers/animepahe.js",
                        "formats": ["m3u8"],
                        "contentLanguage": ["en"]
                    }
                ]
            }
        """.trimIndent()

        val manifest = json.decodeFromString<JsRepoManifest>(payload)
        assertEquals("Yoru's Repo", manifest.name)
        assertEquals(1, manifest.scrapers.size)
        val s = manifest.scrapers[0]
        assertEquals(listOf("movie", "tv", "anime"), s.supportedTypes)
        assertEquals(listOf("m3u8"), s.formats)
        assertEquals(listOf("en"), s.contentLanguage)
    }

    @Test
    fun testInferRepoNameFromUrl() {
        assertEquals("All-in-One-Nuvio", inferRepoNameFromUrl("https://raw.githubusercontent.com/D3adlyRocket/All-in-One-Nuvio/refs/heads/main/manifest.json"))
        assertEquals("Yoru's Repo", inferRepoNameFromUrl("https://raw.githubusercontent.com/yoruix/nuvio-providers/refs/heads/main/manifest.json"))
        assertEquals("Sample Providers", inferRepoNameFromUrl("https://example.com/sample-providers/manifest.json"))
        assertNotEquals("manifest", inferRepoNameFromUrl("https://raw.githubusercontent.com/user/test-repo/main/manifest.json"))
    }
}
