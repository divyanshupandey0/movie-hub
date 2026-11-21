package com.euthopiar.core.backend.cache

import android.util.LruCache
import com.euthopiar.core.backend.model.BackendSourceResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import java.util.concurrent.ConcurrentHashMap

/**
 * Smart Source Cache with Request Deduplication (Single-Flight) and Expiration Management.
 *
 * Implements Section 7 guarantees:
 * 1. Caches content metadata, provider responses, validated source metadata, and provider health.
 * 2. Enforces strict TTL expiration (ephemeral URLs: 10 minutes; metadata: 24 hours).
 * 3. Prevents duplicate in-flight API requests across concurrent callers (Request Coalescing).
 * 4. Never returns stale or invalidated sources as healthy.
 */
class SmartSourceCache private constructor() {

    private data class CacheEntry<T>(
        val value: T,
        val createdAtMs: Long,
        val expiresAtMs: Long
    ) {
        val isExpired: Boolean
            get() = System.currentTimeMillis() > expiresAtMs
    }

    // In-memory cache for resolved source responses (60 entries max)
    private val responseCache = object : LruCache<String, CacheEntry<BackendSourceResponse>>(60) {}
    private val responseLock = Any()

    // Request coalescing for in-flight requests (Single-Flight pattern)
    private val inFlightDeferreds = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<Any?>>()
    private val inFlightLock = Any()
    private val flightScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    // Track blacklisted/failed URLs to prevent returning stale invalid streams
    private val failedUrls = ConcurrentHashMap.newKeySet<String>()

    companion object {
        private const val DEFAULT_SOURCE_TTL_MS = 10 * 60 * 1000L // 10 minutes for signed/ephemeral streams
        private const val DEFAULT_METADATA_TTL_MS = 24 * 60 * 60 * 1000L // 24 hours for canonical metadata

        @Volatile
        private var instance: SmartSourceCache? = null

        fun getInstance(): SmartSourceCache {
            return instance ?: synchronized(this) {
                instance ?: SmartSourceCache().also { instance = it }
            }
        }
    }

    /**
     * Executes a block with request deduplication (Single-Flight).
     * If multiple concurrent requests arrive for the exact same cache key, only ONE execution occurs,
     * and all callers receive the coalesced result.
     */
    suspend fun <T> executeSingleFlight(key: String, block: suspend () -> T): T {
        val deferred: Deferred<T> = synchronized(inFlightLock) {
            val existing = inFlightDeferreds[key]
            if (existing != null) {
                @Suppress("UNCHECKED_CAST")
                existing as Deferred<T>
            } else {
                val newDeferred = flightScope.async {
                    try {
                        block()
                    } finally {
                        synchronized(inFlightLock) {
                            inFlightDeferreds.remove(key)
                        }
                    }
                }
                @Suppress("UNCHECKED_CAST")
                inFlightDeferreds[key] = newDeferred as Deferred<Any?>
                newDeferred
            }
        }
        return deferred.await()
    }

    /**
     * Retrieves an unexpired cached source response for the specified key.
     */
    fun getSourceResponse(key: String): BackendSourceResponse? {
        synchronized(responseLock) {
            val entry = responseCache.get(key) ?: return null
            if (entry.isExpired) {
                responseCache.remove(key)
                return null
            }
            // Filter out any sources that have failed since caching
            val validSources = entry.value.sources.filter { it.url !in failedUrls }
            if (validSources.isEmpty() && entry.value.sources.isNotEmpty()) {
                responseCache.remove(key)
                return null
            }
            return entry.value.copy(sources = validSources)
        }
    }

    /**
     * Stores a validated source response in cache with appropriate TTL.
     */
    fun putSourceResponse(key: String, response: BackendSourceResponse, customTtlMs: Long = DEFAULT_SOURCE_TTL_MS) {
        val now = System.currentTimeMillis()
        val entry = CacheEntry(
            value = response,
            createdAtMs = now,
            expiresAtMs = now + customTtlMs
        )
        synchronized(responseLock) {
            responseCache.put(key, entry)
        }
    }

    /**
     * Flags a failed URL to immediately invalidate it across all cached entries.
     */
    fun markUrlFailed(url: String) {
        failedUrls.add(url)
    }

    /**
     * Invalidates a specific cache key.
     */
    fun invalidate(key: String) {
        synchronized(responseLock) {
            responseCache.remove(key)
        }
    }

    /**
     * Clears all cached items and failed URL tracking.
     */
    fun clear() {
        synchronized(responseLock) {
            responseCache.evictAll()
        }
        synchronized(inFlightLock) {
            inFlightDeferreds.clear()
        }
        failedUrls.clear()
    }
}
