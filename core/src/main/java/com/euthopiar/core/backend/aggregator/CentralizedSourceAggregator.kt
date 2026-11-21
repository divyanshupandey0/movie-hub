package com.euthopiar.core.backend.aggregator

import android.util.Log
import com.euthopiar.core.addons.AddonRepository
import com.euthopiar.core.addons.buildAddonResourceUrl
import com.euthopiar.core.backend.cache.SmartSourceCache
import com.euthopiar.core.backend.matching.StrictContentMatchingEngine
import com.euthopiar.core.backend.model.BackendContentDescriptor
import com.euthopiar.core.backend.model.BackendErrorEntry
import com.euthopiar.core.backend.model.BackendSourceItem
import com.euthopiar.core.backend.model.BackendSourceResponse
import com.euthopiar.core.backend.model.PlayableSourceState
import com.euthopiar.core.backend.ranking.IntelligentSourceRanker
import com.euthopiar.core.backend.validation.BackendUrlValidationEngine
import com.euthopiar.core.js.JsPluginStorage
import com.euthopiar.core.js.PluginRuntime
import com.euthopiar.core.js.network.httpRequestRaw
import com.euthopiar.core.matcher.MatchScorer
import com.euthopiar.core.model.ContentType
import com.euthopiar.core.model.MatchHints
import com.euthopiar.core.provider.StreamingSourceManager
import com.euthopiar.core.provider.source.StreamingSourceRegistry
import com.euthopiar.core.streams.StreamItem
import com.euthopiar.core.streams.StreamParser
import com.euthopiar.core.sync.supabase.RemoteSourceHealthEntry
import com.euthopiar.core.sync.supabase.SupabaseSourceManagementClient
import com.euthopiar.core.streams.toStreamItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Centralized Backend Source Aggregator for Movie Hub.
 *
 * Implements Section 1, 2, 3, 4, 5, 7, 9, 11 guarantees:
 * 1. Integrates all providers (Stremio Addons, JS Scrapers, Native Streaming Services).
 * 2. Fetches provider responses concurrently with independent timeouts.
 * 3. Normalizes disparate provider response formats into standardized BackendSourceItem contract.
 * 4. Isolates failures: one failing or slow provider never blocks or breaks others.
 * 5. Deduplicates sources across providers.
 * 6. Tracks provider response times and records health to Supabase.
 * 7. Filters through StrictContentMatchingEngine and BackendUrlValidationEngine.
 * 8. Ranks through IntelligentSourceRanker (Hindi-first, verified status, health, resolution).
 * 9. Caches with Single-Flight request deduplication.
 */
class CentralizedSourceAggregator private constructor() {

    private val cache = SmartSourceCache.getInstance()
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    companion object {
        private const val TAG = "BackendAggregator"
        private const val PROVIDER_TIMEOUT_MS = 12_000L

        @Volatile
        private var instance: CentralizedSourceAggregator? = null

        fun getInstance(): CentralizedSourceAggregator {
            return instance ?: synchronized(this) {
                instance ?: CentralizedSourceAggregator().also { instance = it }
            }
        }
    }

    /**
     * Resolves, matches, validates, ranks, and delivers reliable streaming sources.
     */
    suspend fun resolveBackendSources(
        mediaId: String,
        mediaType: String,
        title: String? = null,
        year: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        episodeTitle: String? = null,
        imdbId: String? = null,
        originalLanguage: String? = null,
        preferredLanguage: String = "hi",
        forceRefresh: Boolean = false,
        allow4k: Boolean = false
    ): BackendSourceResponse = withContext(Dispatchers.IO) {
        val cleanMediaId = mediaId.trim().removePrefix("tmdb:").removePrefix("tmdb/")
        val isTv = mediaType.equals("tv", true) || mediaType.equals("series", true) || (season != null && episode != null)
        val normType = if (isTv) "tv" else "movie"
        val cacheKey = "$normType::$cleanMediaId::$season::$episode::$year::$imdbId::$preferredLanguage"

        // 1. Check Smart Source Cache
        if (!forceRefresh) {
            val cached = cache.getSourceResponse(cacheKey)
            if (cached != null && cached.sources.isNotEmpty()) {
                Log.d(TAG, "Cache HIT for [$cacheKey] (${cached.sources.size} sources)")
                return@withContext cached
            }
        }

        // 2. Single-Flight request deduplication
        cache.executeSingleFlight(cacheKey) {
            // Re-check cache inside mutex
            if (!forceRefresh) {
                val cached = cache.getSourceResponse(cacheKey)
                if (cached != null && cached.sources.isNotEmpty()) return@executeSingleFlight cached
            }

            val requestId = UUID.randomUUID().toString()
            val contentDescriptor = BackendContentDescriptor(
                id = cleanMediaId,
                type = normType,
                title = title.orEmpty().ifBlank { "Media $cleanMediaId" },
                year = year,
                season = season,
                episode = episode,
                episodeTitle = episodeTitle,
                imdbId = imdbId
            )

            val hints = MatchHints(
                tmdbId = cleanMediaId.toIntOrNull() ?: 0,
                imdbId = imdbId,
                type = if (isTv) ContentType.TV_SERIES else ContentType.MOVIE,
                titles = setOfNotNull(title).filter { it.isNotBlank() }.toSet(),
                year = year,
                season = season,
                episode = episode
            )

            val discoveredItems = CopyOnWriteArrayList<Pair<String, StreamItem>>()
            val errors = CopyOnWriteArrayList<BackendErrorEntry>()

            // 3. Concurrently query all provider systems
            coroutineScope {
                val tasks = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                // 3.1 Query Stremio Addons (Torrentio, MediaFusion, CineStream, PlavioHD, etc.)
                val addonRepo = AddonRepository.getInstanceOrNull()
                val targetAddonType = if (isTv) "series" else "movie"
                val addons = addonRepo?.getEnabledAddonsForType(targetAddonType, cleanMediaId) ?: emptyList()

                addons.forEach { addon ->
                    tasks.add(async {
                        val providerName = addon.displayTitle
                        val startTime = System.currentTimeMillis()
                        try {
                            val streamId = if (isTv && season != null && episode != null) {
                                val base = imdbId ?: cleanMediaId
                                "$base:$season:$episode"
                            } else {
                                imdbId ?: cleanMediaId
                            }

                            val url = buildAddonResourceUrl(
                                manifestUrl = addon.manifestUrl,
                                resource = "stream",
                                type = targetAddonType,
                                id = streamId,
                                extraPathSegment = null
                            )

                            val response = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                httpRequestRaw("GET", url, emptyMap(), "", followRedirects = true)
                            }
                            val duration = System.currentTimeMillis() - startTime

                            if (response != null && response.status in 200..299 && response.body.isNotBlank()) {
                                val parsed = StreamParser.parse(
                                    payload = response.body,
                                    addonName = providerName,
                                    addonId = addon.manifest?.id ?: providerName,
                                    originalLanguage = originalLanguage
                                )
                                parsed.forEach { item -> discoveredItems.add(providerName to item) }
                                recordProviderHealth(addon.manifest?.id ?: providerName, duration, true)
                            } else {
                                val err = if (response == null) "Request timed out (${PROVIDER_TIMEOUT_MS}ms)" else "HTTP ${response.status}"
                                errors.add(BackendErrorEntry(provider = providerName, errorCode = "PROVIDER_ERROR", message = err))
                                recordProviderHealth(addon.manifest?.id ?: providerName, duration, false, err)
                            }
                        } catch (e: Exception) {
                            val duration = System.currentTimeMillis() - startTime
                            val msg = e.localizedMessage ?: "Provider query failed"
                            errors.add(BackendErrorEntry(provider = providerName, errorCode = "EXCEPTION", message = msg))
                            recordProviderHealth(addon.manifest?.id ?: providerName, duration, false, msg)
                        }
                    })
                }

                // 3.2 Query JavaScript Scrapers / Plugins
                val storage = JsPluginStorage.getInstanceOrNull()
                val scrapers = storage?.getEnabledScrapersForType(normType) ?: emptyList()

                scrapers.forEach { scraper ->
                    tasks.add(async {
                        val providerName = scraper.name
                        val startTime = System.currentTimeMillis()
                        try {
                            val results = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                PluginRuntime.executePlugin(
                                    code = scraper.code,
                                    tmdbId = cleanMediaId,
                                    mediaType = if (isTv) "tv" else "movie",
                                    season = season,
                                    episode = episode,
                                    scraperId = scraper.id,
                                    imdbId = imdbId,
                                    title = title,
                                    year = year,
                                    repoId = scraper.canonicalRepoId
                                )
                            }
                            val duration = System.currentTimeMillis() - startTime

                            if (results != null && results.isNotEmpty()) {
                                val items = results.map { res ->
                                    res.toStreamItem(
                                        scraper = scraper,
                                        addonName = providerName,
                                        addonId = "plugin:${scraper.id}",
                                        originalLanguage = originalLanguage
                                    )
                                }
                                items.forEach { item -> discoveredItems.add(providerName to item) }
                                recordProviderHealth(scraper.id, duration, true)
                            } else {
                                val err = if (results == null) "Execution timed out" else "Zero streams found"
                                recordProviderHealth(scraper.id, duration, results != null, if (results == null) err else null)
                            }
                        } catch (e: Exception) {
                            val duration = System.currentTimeMillis() - startTime
                            val msg = e.localizedMessage ?: "Scraper execution failed"
                            errors.add(BackendErrorEntry(provider = providerName, errorCode = "SCRAPER_ERROR", message = msg))
                            recordProviderHealth(scraper.id, duration, false, msg)
                        }
                    })
                }

                // 3.3 Query Native Streaming Source Services
                val nativeServices = StreamingSourceRegistry.getInstance().getEnabledServices()
                nativeServices.forEach { service ->
                    tasks.add(async {
                        val providerName = service.name
                        val startTime = System.currentTimeMillis()
                        try {
                            val playableList = withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
                                service.resolvePlayableStreams(hints)
                            } ?: emptyList()
                            val duration = System.currentTimeMillis() - startTime

                            playableList.forEach { playable ->
                                val item = StreamItem(
                                    name = playable.sourceName,
                                    title = playable.sourceName,
                                    url = playable.url,
                                    quality = playable.quality,
                                    headers = playable.headers,
                                    container = playable.format,
                                    addonName = providerName,
                                    addonId = service.id,
                                    matchScore = playable.matchScore,
                                    infoHash = playable.infoHash,
                                    seeders = playable.seeders,
                                    isCached = playable.isDebridCached
                                )
                                discoveredItems.add(providerName to item)
                            }
                            recordProviderHealth(service.id, duration, true)
                        } catch (e: Exception) {
                            val duration = System.currentTimeMillis() - startTime
                            val msg = e.localizedMessage ?: "Native service query failed"
                            errors.add(BackendErrorEntry(provider = providerName, errorCode = "SERVICE_ERROR", message = msg))
                            recordProviderHealth(service.id, duration, false, msg)
                        }
                    })
                }

                // Await all concurrent tasks
                tasks.awaitAll()
            }

            // 4. Deduplicate sources by URL or InfoHash
            val seenKeys = mutableSetOf<String>()
            val uniqueItems = mutableListOf<Pair<String, StreamItem>>()
            for (pair in discoveredItems) {
                val item = pair.second
                val key = item.url?.trim()?.lowercase() ?: item.infoHash?.trim()?.lowercase() ?: continue
                if (seenKeys.add(key)) {
                    uniqueItems.add(pair)
                }
            }

            Log.d(TAG, "Aggregated ${uniqueItems.size} unique candidate sources (raw: ${discoveredItems.size})")

            // 5. Strict Content Matching & Technical URL Validation
            val validatedSources = mutableListOf<BackendSourceItem>()

            for ((providerName, item) in uniqueItems) {
                val rawUrl = item.playableDirectUrl ?: item.url ?: continue

                // 5.1 Strict Content Identity Matching
                val matchResult = StrictContentMatchingEngine.match(hints, item)
                if (!matchResult.isMatch || matchResult.state == PlayableSourceState.CONTENT_MISMATCH || matchResult.state == PlayableSourceState.INVALID) {
                    Log.d(TAG, "Rejected candidate from [$providerName]: ${matchResult.reason}")
                    continue
                }

                // 5.2 Technical URL & Manifest Validation
                val isP2p = item.isTorrentStream || item.infoHash != null || rawUrl.startsWith("magnet:")
                val valResult = BackendUrlValidationEngine.validateUrl(
                    rawUrl = rawUrl,
                    headers = item.headers ?: emptyMap(),
                    isTorrent = isP2p,
                    hasDebrid = item.isCached
                )

                // Only eligible sources proceed
                val finalState = when {
                    !valResult.isValid -> valResult.state
                    matchResult.state == PlayableSourceState.PARTIALLY_VERIFIED -> PlayableSourceState.PARTIALLY_VERIFIED
                    else -> PlayableSourceState.VERIFIED
                }

                val requiresHeaders = (item.headers?.isNotEmpty() == true) || (valResult.requiredHeaders.isNotEmpty())
                val effectiveHeaders = (item.headers ?: emptyMap()) + valResult.requiredHeaders

                val sourceItem = BackendSourceItem(
                    id = "src_${UUID.randomUUID().toString().substring(0, 8)}",
                    provider = providerName,
                    status = finalState.name.lowercase(),
                    contentMatch = matchResult.isMatch,
                    url = valResult.resolvedUrl,
                    format = valResult.detectedFormat.name.lowercase(),
                    language = matchResult.detectedLanguages,
                    requiresHeaders = requiresHeaders,
                    headers = effectiveHeaders,
                    quality = item.quality ?: "1080p",
                    matchScore = matchResult.score,
                    verificationReason = matchResult.reason,
                    validationLatencyMs = valResult.latencyMs,
                    hasHindi = matchResult.hasHindi,
                    isHindiDubbed = matchResult.isHindiDubbed,
                    isMultiAudio = matchResult.isMultiAudio,
                    hasHindiEnglishMulti = matchResult.hasHindiEnglishMulti,
                    hasHindiOtherMulti = matchResult.hasHindiOtherMulti,
                    isOriginalLanguage = matchResult.isOriginalLanguage,
                    audioLanguages = matchResult.audioLanguages,
                    subtitleLanguages = matchResult.subtitleLanguages,
                    isDebrid = item.isCached,
                    isP2p = isP2p,
                    infoHash = item.infoHash,
                    seeders = item.seeders,
                    sourceName = item.streamLabel.ifBlank { "$providerName • ${item.quality ?: "1080p"}" },
                    state = finalState
                )

                if (finalState.isPlayableEligible) {
                    validatedSources.add(sourceItem)
                }
            }

            // 6. Intelligent Source Ranking
            val rankedSources = IntelligentSourceRanker.rankSources(
                sources = validatedSources,
                preferredLanguage = preferredLanguage,
                allow4k = allow4k
            )

            val finalResponse = BackendSourceResponse(
                success = rankedSources.isNotEmpty(),
                content = contentDescriptor,
                sources = rankedSources,
                errors = errors.toList(),
                requestId = requestId
            )

            // 7. Store in Smart Source Cache
            if (rankedSources.isNotEmpty()) {
                cache.putSourceResponse(cacheKey, finalResponse)
            }

            finalResponse
        }
    }

    private fun recordProviderHealth(providerId: String, durationMs: Long, isSuccess: Boolean, error: String? = null) {
        StreamingSourceManager.getInstance().recordRequestOutcome(providerId, durationMs, isSuccess, error)
        // Asynchronously update Supabase health metrics
        scope.launch {
            try {
                val health = RemoteSourceHealthEntry(
                    providerId = providerId,
                    status = if (isSuccess) "ACTIVE" else if (error?.contains("timeout", true) == true) "DEGRADED" else "OFFLINE",
                    latencyMs = durationMs,
                    successRate = if (isSuccess) 1.0 else 0.0,
                    errorMessage = error
                )
                SupabaseSourceManagementClient.getInstance().recordOperationalAnalytics(
                    eventType = "provider_query",
                    providerId = providerId,
                    durationMs = durationMs,
                    success = isSuccess
                )
            } catch (_: Exception) {}
        }
    }
}
