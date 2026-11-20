package com.euthopiar.core.cache

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache categories with customized TTL policies.
 */
enum class CacheCategory(val defaultTtlMs: Long) {
    METADATA(24 * 60 * 60 * 1000L),            // 24 hours
    PROVIDER_REGISTRY(60 * 60 * 1000L),         // 1 hour
    PROVIDER_HEALTH(15 * 60 * 1000L),           // 15 minutes
    SOURCE_DISCOVERY(10 * 60 * 1000L),          // 10 minutes (prevents stale/expired links)
    VERIFIED_SOURCES(10 * 60 * 1000L),          // 10 minutes
    AUDIO_TRACK_INFO(24 * 60 * 60 * 1000L),     // 24 hours
    EPISODE_MAPPINGS(24 * 60 * 60 * 1000L)      // 24 hours
}

data class CacheEntry<T>(
    val value: T,
    val timestampMs: Long,
    val ttlMs: Long
) {
    fun isExpired(): Boolean = (System.currentTimeMillis() - timestampMs) > ttlMs
}

/**
 * Persistent, intelligent multi-tier cache manager for Movie Hub.
 *
 * Implements:
 * 1. Categorized in-memory caching with fast-path O(1) lookups.
 * 2. Persistent disk storage across app restarts for durable categories (metadata, episode mappings).
 * 3. Configurable TTL and eviction for volatile categories (sources, provider health).
 * 4. Automatic storage quota clamping (max 25MB disk cache).
 * 5. Dead/expired source invalidation.
 */
class SmartCacheManager private constructor() {

    private val memoryCache = ConcurrentHashMap<String, CacheEntry<String>>()
    private val mutex = Mutex()
    private var cacheDir: File? = null

    companion object {
        private const val TAG = "SmartCacheManager"
        private const val MAX_DISK_CACHE_BYTES = 25 * 1024 * 1024L // 25 MB max

        @Volatile
        private var instance: SmartCacheManager? = null

        fun getInstance(): SmartCacheManager {
            return instance ?: synchronized(this) {
                instance ?: SmartCacheManager().also { instance = it }
            }
        }
    }

    fun initialize(context: Context) {
        val dir = File(context.applicationContext.cacheDir, "smart_cache")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        cacheDir = dir
        cleanExpiredEntries()
    }

    private fun buildKey(category: CacheCategory, key: String): String {
        return "${category.name}::$key"
    }

    /**
     * Put a string value into cache with the category's TTL.
     */
    fun put(category: CacheCategory, key: String, value: String, customTtlMs: Long? = null) {
        val fullKey = buildKey(category, key)
        val ttl = customTtlMs ?: category.defaultTtlMs
        val entry = CacheEntry(
            value = value,
            timestampMs = System.currentTimeMillis(),
            ttlMs = ttl
        )
        memoryCache[fullKey] = entry

        // Persist durable categories to disk
        if (category in listOf(CacheCategory.METADATA, CacheCategory.EPISODE_MAPPINGS, CacheCategory.AUDIO_TRACK_INFO)) {
            persistToDiskAsync(fullKey, entry)
        }
    }

    /**
     * Retrieve a cached value if present and unexpired.
     */
    fun get(category: CacheCategory, key: String): String? {
        val fullKey = buildKey(category, key)
        val entry = memoryCache[fullKey]
        if (entry != null) {
            if (!entry.isExpired()) {
                return entry.value
            } else {
                memoryCache.remove(fullKey)
                deleteFromDiskAsync(fullKey)
                return null
            }
        }

        // Check persistent disk cache
        return readFromDisk(fullKey)
    }

    /**
     * Invalidate specific cached key.
     */
    fun invalidate(category: CacheCategory, key: String) {
        val fullKey = buildKey(category, key)
        memoryCache.remove(fullKey)
        deleteFromDiskAsync(fullKey)
    }

    /**
     * Invalidate all entries for a specific category.
     */
    fun invalidateCategory(category: CacheCategory) {
        val prefix = "${category.name}::"
        val keysToRemove = memoryCache.keys.filter { it.startsWith(prefix) }
        keysToRemove.forEach { memoryCache.remove(it) }
        cacheDir?.listFiles()?.forEach { file ->
            if (file.name.startsWith(category.name)) {
                file.delete()
            }
        }
    }

    /**
     * Clean all expired entries and enforce storage limits.
     */
    fun cleanExpiredEntries() {
        val now = System.currentTimeMillis()
        val expired = memoryCache.filterValues { it.isExpired() }.keys
        expired.forEach { memoryCache.remove(it) }

        val dir = cacheDir ?: return
        try {
            val files = dir.listFiles() ?: return
            var totalBytes = 0L
            val fileList = files.toList().sortedBy { it.lastModified() }
            for (file in fileList) {
                totalBytes += file.length()
                if (totalBytes > MAX_DISK_CACHE_BYTES) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error cleaning cache directory: ${e.message}")
        }
    }

    private fun persistToDiskAsync(fullKey: String, entry: CacheEntry<String>) {
        val dir = cacheDir ?: return
        try {
            val safeFilename = fullKey.replace(Regex("""[^a-zA-Z0-9._-]"""), "_") + ".json"
            val file = File(dir, safeFilename)
            val json = JSONObject().apply {
                put("value", entry.value)
                put("timestampMs", entry.timestampMs)
                put("ttlMs", entry.ttlMs)
            }
            file.writeText(json.toString())
        } catch (_: Exception) {}
    }

    private fun readFromDisk(fullKey: String): String? {
        val dir = cacheDir ?: return null
        try {
            val safeFilename = fullKey.replace(Regex("""[^a-zA-Z0-9._-]"""), "_") + ".json"
            val file = File(dir, safeFilename)
            if (!file.exists()) return null

            val json = JSONObject(file.readText())
            val ts = json.optLong("timestampMs", 0L)
            val ttl = json.optLong("ttlMs", 0L)
            if (System.currentTimeMillis() - ts > ttl) {
                file.delete()
                return null
            }
            val v = json.optString("value")
            if (v.isNotBlank()) {
                memoryCache[fullKey] = CacheEntry(v, ts, ttl)
                return v
            }
        } catch (_: Exception) {}
        return null
    }

    private fun deleteFromDiskAsync(fullKey: String) {
        val dir = cacheDir ?: return
        try {
            val safeFilename = fullKey.replace(Regex("""[^a-zA-Z0-9._-]"""), "_") + ".json"
            File(dir, safeFilename).delete()
        } catch (_: Exception) {}
    }
}
