package com.euthopiar.core.provider.cloudstream

import com.euthopiar.core.model.MediaType
import com.euthopiar.core.network.DohDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Audit registry and discovery catalog for CloudStream ecosystem repositories.
 * Audits CNC Verse and Phisher repositories and maintains accurate compatibility and authorization statuses.
 */
object CloudStreamEcosystemCatalog {

    const val CNC_VERSE_REPO_URL = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/refs/heads/builds/CNC.json"
    const val CNC_VERSE_PLUGINS_URL = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/plugins.json"

    const val PHISHER_REPO_URL = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json"
    const val PHISHER_PLUGINS_URL = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/plugins.json"

    // Hardened audit catalog for all target extension packages
    val AUDITED_TARGET_EXTENSIONS: List<CloudStreamExtensionMetadata> = listOf(
        CloudStreamExtensionMetadata(
            id = "cs_castletv",
            name = "Castle TV",
            internalName = "CastleTvProvider",
            repositoryName = "CNC Verse",
            packageUrl = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/CastleTvProvider.cs3",
            version = 1,
            authors = listOf("NivinCNC"),
            description = "Castle TV movies and series (Requires private symmetric encryption & proprietary API deciphering)",
            supportedTypes = setOf(MediaType.MOVIE, MediaType.TV_SERIES),
            status = CloudStreamIntegrationStatus.BLOCKED,
            statusReason = "API responses (api.hlowb.com) encrypted with proprietary private key; .cs3 Dalvik runtime incompatible"
        ),
        CloudStreamExtensionMetadata(
            id = "cs_vegamovies",
            name = "VegaMovies",
            internalName = "VegaMoviesProvider",
            repositoryName = "CNC Verse",
            packageUrl = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/VegaMoviesProvider.cs3",
            version = 1,
            authors = listOf("NivinCNC"),
            description = "Bollywood & Hollywood movie scraper",
            supportedTypes = setOf(MediaType.MOVIE),
            status = CloudStreamIntegrationStatus.BLOCKED,
            statusReason = "Protected by Cloudflare Turnstile anti-bot gates; requires .cs3 Dalvik engine; unlicensed content"
        ),
        CloudStreamExtensionMetadata(
            id = "cs_hdhub4u",
            name = "HDhub4u",
            internalName = "HDhub4u",
            repositoryName = "Phisher",
            packageUrl = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/HDhub4u.cs3",
            version = 1,
            authors = listOf("Phisher98"),
            description = "Dual audio movie provider",
            supportedTypes = setOf(MediaType.MOVIE),
            status = CloudStreamIntegrationStatus.BLOCKED,
            statusReason = "Compiled .cs3 Dalvik package without public source; relies on unlicensed mirrors and link shorteners"
        ),
        CloudStreamExtensionMetadata(
            id = "cs_cricify",
            name = "Cricify",
            internalName = "CricifyProvider",
            repositoryName = "CNC Verse",
            packageUrl = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/CricifyProvider.cs3",
            version = 1,
            authors = listOf("NivinCNC"),
            description = "Live sports and cricket matches",
            supportedTypes = setOf(MediaType.LIVE_TV),
            status = CloudStreamIntegrationStatus.BLOCKED,
            statusReason = "Unlicensed live sports broadcasts with volatile token authorization; incompatible .cs3 format"
        ),
        CloudStreamExtensionMetadata(
            id = "cs_daddylive",
            name = "DaddyLive",
            internalName = "DaddyLiveProvider",
            repositoryName = "CNC Verse",
            packageUrl = "https://raw.githubusercontent.com/NivinCNC/CNCVerse-Cloud-Stream-Extension/builds/DaddyLiveProvider.cs3",
            version = 1,
            authors = listOf("NivinCNC"),
            description = "Live TV streaming channels",
            supportedTypes = setOf(MediaType.LIVE_TV),
            status = CloudStreamIntegrationStatus.BLOCKED,
            statusReason = "Unlicensed rebroadcasts requiring referer token spoofing and private origin headers; .cs3 format"
        ),
        CloudStreamExtensionMetadata(
            id = "cs_hianime",
            name = "HiAnime",
            internalName = "HiAnime",
            repositoryName = "Phisher",
            packageUrl = "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/HiAnime.cs3",
            version = 1,
            authors = listOf("Phisher98"),
            description = "Anime streaming catalog",
            supportedTypes = setOf(MediaType.ANIME),
            status = CloudStreamIntegrationStatus.BLOCKED,
            statusReason = "Compiled .cs3 binary relying on CloudStream internal extraction and unlicensed video hosts"
        )
    )

    /**
     * Dynamically discovers and fetches plugin metadata from a CloudStream repository's plugins.json.
     */
    suspend fun fetchRepositoryPlugins(
        pluginsUrl: String,
        repoName: String,
        client: OkHttpClient = DohDns.metadataClient
    ): List<CloudStreamExtensionMetadata> = withContext(Dispatchers.IO) {
        val results = mutableListOf<CloudStreamExtensionMetadata>()
        try {
            val req = Request.Builder().url(pluginsUrl).header("User-Agent", "MOVIE-HUB/2.0.2").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val body = resp.body?.string() ?: return@withContext emptyList()
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val name = obj.optString("name", "Unknown")
                    val internalName = obj.optString("internalName", name)
                    val url = obj.optString("url", "")
                    val desc = obj.optString("description", "")
                    val authors = mutableListOf<String>()
                    val authorsArr = obj.optJSONArray("authors")
                    if (authorsArr != null) {
                        for (a in 0 until authorsArr.length()) {
                            authors.add(authorsArr.getString(a))
                        }
                    }

                    results.add(
                        CloudStreamExtensionMetadata(
                            id = "cs_${internalName.lowercase()}",
                            name = name,
                            internalName = internalName,
                            repositoryName = repoName,
                            packageUrl = url,
                            authors = authors,
                            description = desc,
                            status = CloudStreamIntegrationStatus.BLOCKED,
                            statusReason = "CloudStream .cs3 compiled Dalvik package; requires CloudStream runtime"
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        results
    }
}
