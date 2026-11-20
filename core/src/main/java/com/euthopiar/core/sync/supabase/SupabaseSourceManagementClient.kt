package com.euthopiar.core.sync.supabase

import android.util.Log
import com.euthopiar.core.BuildConfig
import com.euthopiar.core.cache.CacheCategory
import com.euthopiar.core.cache.SmartCacheManager
import com.euthopiar.core.network.DohDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * High-performance, resilient Supabase client for remote Source Management,
 * Dynamic App Configuration, Playback Sessions, and Operational Telemetry.
 *
 * Guarantees:
 * 1. Safe credential handling: Uses public SUPABASE_ANON_KEY exclusively.
 * 2. Non-blocking: All operations run asynchronously with 5-second timeouts.
 * 3. Graceful offline fallback: Falls back to SmartCacheManager and hardcoded defaults
 *    if Supabase is unreachable. Playback is NEVER delayed or interrupted.
 */
class SupabaseSourceManagementClient private constructor(
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL.ifBlank { "https://rssszrbwjusfguqnzisp.supabase.co" }.trimEnd('/'),
    private val anonKey: String = BuildConfig.SUPABASE_ANON_KEY.ifBlank { "sb_publishable_70ijdSvmbujxAn3eU4Fw8w_02jqHW8V" },
    private val httpClient: OkHttpClient = DohDns.metadataClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    // In-memory hot cache for app_config and provider directory
    private val dynamicConfigCache = ConcurrentHashMap<String, JsonObject>()

    companion object {
        private const val TAG = "SupabaseSourceManager"

        @Volatile
        private var instance: SupabaseSourceManagementClient? = null

        fun getInstance(): SupabaseSourceManagementClient {
            return instance ?: synchronized(this) {
                instance ?: SupabaseSourceManagementClient().also { instance = it }
            }
        }
    }

    private fun buildRequest(path: String): Request.Builder {
        return Request.Builder()
            .url("$supabaseUrl/rest/v1/$path")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
    }

    /**
     * Fetches remote dynamic app configuration (e.g., 'streaming_policy', 'provider_directory').
     * Automatically caches valid responses in SmartCacheManager and memory.
     */
    suspend fun getAppConfig(configKey: String): JsonObject? = withContext(Dispatchers.IO) {
        val memoryHit = dynamicConfigCache[configKey]
        if (memoryHit != null) return@withContext memoryHit

        // Check local persistent cache
        val localCached = SmartCacheManager.getInstance().get(CacheCategory.PROVIDER_REGISTRY, "cfg_$configKey")
        if (!localCached.isNullOrBlank()) {
            try {
                val parsed = json.decodeFromString<AppConfigEntry>(localCached).value
                dynamicConfigCache[configKey] = parsed
                return@withContext parsed
            } catch (_: Exception) {}
        }

        // Fetch from Supabase (try app_config first, then app_settings)
        for (endpoint in listOf("app_config", "app_settings")) {
            try {
                val req = buildRequest("$endpoint?key=eq.$configKey&select=*").get().build()
                httpClient.newCall(req).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        val entries = json.decodeFromString<List<AppConfigEntry>>(body)
                        val entry = entries.firstOrNull()
                        if (entry != null) {
                            dynamicConfigCache[configKey] = entry.value
                            SmartCacheManager.getInstance().put(
                                CacheCategory.PROVIDER_REGISTRY,
                                "cfg_$configKey",
                                json.encodeToString(entry),
                                customTtlMs = 60 * 60 * 1000L // 1 hour
                            )
                            Log.d(TAG, "Loaded remote $endpoint [$configKey]")
                            return@withContext entry.value
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to fetch $endpoint [$configKey] from Supabase: ${e.message}")
            }
        }
        null
    }

    /**
     * Checks if a provider is enabled according to Supabase 'provider_directory'.
     * Defaults to true for standard approved providers if offline.
     */
    suspend fun isProviderEnabledRemotely(providerId: String): Boolean = withContext(Dispatchers.IO) {
        val directory = getAppConfig("provider_directory") ?: return@withContext when (providerId.lowercase()) {
            "castle_tv", "hdhub", "netmirror" -> false
            else -> true
        }

        val providerObj = directory[providerId.lowercase()]?.jsonObject
        val enabled = providerObj?.get("enabled")?.jsonPrimitive?.booleanOrNull
        enabled ?: when (providerId.lowercase()) {
            "castle_tv", "hdhub", "netmirror" -> false
            else -> true
        }
    }

    /**
     * Records an active playback session in Supabase with source locking.
     */
    suspend fun logPlaybackSession(session: RemotePlaybackSessionEntry): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val body = json.encodeToString(session)
            for (endpoint in listOf("playback_sessions?on_conflict=id", "playback_diagnostics")) {
                val req = buildRequest(endpoint)
                    .header("Prefer", "resolution=merge-duplicates")
                    .post(body.toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return@withContext Result.success(Unit)
                }
            }
            Result.failure(Exception("All playback session endpoints unavailable"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Logs unrecoverable source failures to assist with backend dead-link detection.
     */
    suspend fun recordSourceFailure(
        providerId: String,
        mediaId: String,
        errorCode: String?,
        errorMessage: String?,
        sourceId: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val entry = RemoteSourceFailureEntry(
                sourceId = sourceId,
                providerId = providerId,
                mediaId = mediaId,
                errorCode = errorCode,
                errorMessage = errorMessage
            )
            val body = json.encodeToString(entry)
            for (endpoint in listOf("source_failures", "source_checks")) {
                val req = buildRequest(endpoint)
                    .post(body.toRequestBody(jsonMediaType))
                    .build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) return@withContext Result.success(Unit)
                }
            }
            Result.failure(Exception("All source failure endpoints unavailable"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Reports operational analytics (discovery timings, ABR shifts, audio language match rates).
     */
    suspend fun recordOperationalAnalytics(
        eventType: String,
        providerId: String?,
        durationMs: Long?,
        success: Boolean,
        networkType: String? = null,
        bandwidthKbps: Long? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val analytics = RemoteSourceAnalyticsEntry(
                eventType = eventType,
                providerId = providerId,
                durationMs = durationMs,
                success = success,
                networkType = networkType,
                effectiveBandwidthKbps = bandwidthKbps
            )
            val body = json.encodeToString(analytics)
            val req = buildRequest("source_analytics")
                .post(body.toRequestBody(jsonMediaType))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) Result.success(Unit) else Result.failure(Exception("HTTP ${resp.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
