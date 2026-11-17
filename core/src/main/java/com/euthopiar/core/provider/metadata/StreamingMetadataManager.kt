package com.euthopiar.core.provider.metadata

import com.euthopiar.core.model.MediaDetail
import com.euthopiar.core.model.MediaType
import com.euthopiar.core.network.DohDns
import com.euthopiar.core.sync.trakt.TraktClient
import com.euthopiar.core.util.TmdbBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Unified metadata coordinator bridging TMDB, AniList GraphQL, and Trakt v2.
 * Provides unified enrichment for movie details, anime titles, series mapping, and tracking.
 */
class StreamingMetadataManager(
    private val client: OkHttpClient = DohDns.metadataClient,
    private val traktClient: TraktClient = TraktClient()
) {
    /**
     * Enriches media details using TMDB canonical data and AniList anime enhancements.
     */
    suspend fun resolveEnrichedMetadata(
        mediaId: String,
        title: String? = null,
        year: Int? = null,
        isTv: Boolean = false,
        isAnime: Boolean = false
    ): TmdbBridge.EnrichedMetadata? = withContext(Dispatchers.IO) {
        val cleanId = mediaId.trim()
        val canonicalTmdbId = when {
            cleanId.startsWith("tt") -> {
                TmdbBridge.findTmdbIdByImdb(client, cleanId, isTv) ?: cleanId
            }
            cleanId.all { it.isDigit() } -> cleanId
            !title.isNullOrBlank() -> {
                TmdbBridge.searchTmdbId(client, title, year, isTv) ?: cleanId
            }
            else -> cleanId
        }

        TmdbBridge.fetchEnrichedDetails(
            client = client,
            tmdbId = canonicalTmdbId,
            isTv = isTv,
            providerName = "tmdb"
        )
    }

    /**
     * Resolves anime-specific title mappings (Romaji, English, native) from AniList.
     */
    suspend fun resolveAniListDetails(
        anilistId: Int? = null,
        malId: Int? = null,
        searchTitle: String? = null
    ): TmdbBridge.AnimeEnrichmentResult? = withContext(Dispatchers.IO) {
        TmdbBridge.fetchAniListGraphQL(
            client = client,
            anilistId = anilistId,
            malId = malId,
            searchTitle = searchTitle
        )
    }

    /**
     * Trakt integration: checks if user has Trakt client configured.
     */
    fun getTraktClient(): TraktClient = traktClient

    companion object {
        @Volatile
        private var instance: StreamingMetadataManager? = null

        fun getInstance(): StreamingMetadataManager {
            return instance ?: synchronized(this) {
                instance ?: StreamingMetadataManager().also { instance = it }
            }
        }
    }
}
