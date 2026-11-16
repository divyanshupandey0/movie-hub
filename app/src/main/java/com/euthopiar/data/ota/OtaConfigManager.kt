package com.euthopiar.data.ota

import android.content.Context
import androidx.compose.runtime.Immutable
import com.euthopiar.core.network.DohDns
import com.euthopiar.data.preferences.PreferencesRepository
import com.euthopiar.data.update.AppUpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

@Immutable
sealed interface OtaSyncState {
    data object Idle : OtaSyncState
    data object Syncing : OtaSyncState
    data class Success(val lastSyncTimestamp: Long, val configVersion: Int) : OtaSyncState
    data class Error(val message: String, val isUsingCache: Boolean = true) : OtaSyncState
}

class OtaConfigManager private constructor(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fetchMutex = Mutex()
    private val preferencesRepository = PreferencesRepository.getInstance(context)

    private val _config = MutableStateFlow(OtaRemoteConfig.DEFAULT)
    val config: StateFlow<OtaRemoteConfig> = _config.asStateFlow()

    private val _syncState = MutableStateFlow<OtaSyncState>(OtaSyncState.Idle)
    val syncState: StateFlow<OtaSyncState> = _syncState.asStateFlow()

    private val _dismissedAnnouncementIds = MutableStateFlow<Set<String>>(emptySet())
    val dismissedAnnouncementIds: StateFlow<Set<String>> = _dismissedAnnouncementIds.asStateFlow()

    private val cacheFile by lazy { File(context.filesDir, "ota_remote_config.json") }
    private val lastGoodFile by lazy { File(context.filesDir, "ota_last_good_config.json") }

    init {
        // Fast, non-blocking initialization from local disk cache
        loadCachedConfiguration()
    }

    private fun loadCachedConfiguration() {
        try {
            val target = when {
                cacheFile.exists() && cacheFile.length() > 0 -> cacheFile
                lastGoodFile.exists() && lastGoodFile.length() > 0 -> lastGoodFile
                else -> null
            }

            if (target != null) {
                val cachedJson = target.readText()
                val currentAppVersion = AppUpdateManager.getInstance(context).getCurrentVersionName()
                val result = OtaConfigParser.parseAndValidate(cachedJson, currentAppVersion)
                if (result is OtaValidationResult.Success) {
                    _config.value = result.config
                    _syncState.value = OtaSyncState.Success(target.lastModified(), result.config.configVersion)
                }
            }
        } catch (_: Exception) {
            _config.value = OtaRemoteConfig.DEFAULT
        }
    }

    suspend fun fetchRemoteConfig(forceManual: Boolean = false) {
        withContext(Dispatchers.IO) {
            if (!fetchMutex.tryLock()) {
                // Prevent duplicate concurrent requests
                return@withContext
            }

            try {
                _syncState.value = OtaSyncState.Syncing

                val currentAppVersion = AppUpdateManager.getInstance(context).getCurrentVersionName()
                val client = DohDns.sharedBaseClient

                val request = Request.Builder()
                    .url(OTA_CONFIG_URL)
                    .header("Accept", "application/json")
                    .header("User-Agent", "MOVIE-HUB-Android/$currentAppVersion")
                    .header("Cache-Control", "no-cache")
                    .build()

                var responseBody: String? = null
                var httpCode = 0

                try {
                    client.newCall(request).execute().use { response ->
                        httpCode = response.code
                        if (response.isSuccessful) {
                            responseBody = response.body?.string()
                        }
                    }
                } catch (netEx: Exception) {
                    handleSyncFailure("Network connection failed: ${netEx.message ?: "Offline"}")
                    return@withContext
                }

                if (responseBody.isNullOrBlank()) {
                    handleSyncFailure("Remote server returned HTTP $httpCode or empty payload")
                    return@withContext
                }

                when (val result = OtaConfigParser.parseAndValidate(responseBody!!, currentAppVersion)) {
                    is OtaValidationResult.Success -> {
                        val validConfig = result.config

                        // Safely persist cache and last-good fallback
                        try {
                            cacheFile.writeText(responseBody!!)
                            lastGoodFile.writeText(responseBody!!)
                        } catch (_: Exception) {}

                        _config.value = validConfig
                        _syncState.value = OtaSyncState.Success(
                            lastSyncTimestamp = System.currentTimeMillis(),
                            configVersion = validConfig.configVersion
                        )
                    }
                    is OtaValidationResult.Error -> {
                        // Rollback to last-known-good configuration on validation failure
                        rollbackToLastGood(result.reason)
                    }
                }
            } catch (e: Exception) {
                handleSyncFailure(e.message ?: "Unexpected sync failure")
            } finally {
                fetchMutex.unlock()
            }
        }
    }

    private fun handleSyncFailure(errorMessage: String) {
        val hasCache = cacheFile.exists() && cacheFile.length() > 0
        _syncState.value = OtaSyncState.Error(errorMessage, isUsingCache = hasCache)
    }

    private fun rollbackToLastGood(reason: String) {
        try {
            if (lastGoodFile.exists() && lastGoodFile.length() > 0) {
                val currentAppVersion = AppUpdateManager.getInstance(context).getCurrentVersionName()
                val goodResult = OtaConfigParser.parseAndValidate(lastGoodFile.readText(), currentAppVersion)
                if (goodResult is OtaValidationResult.Success) {
                    _config.value = goodResult.config
                    _syncState.value = OtaSyncState.Error("Invalid new config ($reason). Rolled back to Config v${goodResult.config.configVersion}.", isUsingCache = true)
                    return
                }
            }
        } catch (_: Exception) {}

        _syncState.value = OtaSyncState.Error("Validation rejected ($reason). Using defaults.", isUsingCache = true)
    }

    fun dismissAnnouncement(announcementId: String) {
        if (announcementId.isNotBlank()) {
            _dismissedAnnouncementIds.value = _dismissedAnnouncementIds.value + announcementId
        }
    }

    fun isAnnouncementDismissed(announcementId: String): Boolean {
        return announcementId.isBlank() || _dismissedAnnouncementIds.value.contains(announcementId)
    }

    companion object {
        const val OTA_CONFIG_URL = "https://raw.githubusercontent.com/divyanshupandey0/movie-hub/main/remote-config.json"

        @Volatile
        private var instance: OtaConfigManager? = null

        fun getInstance(context: Context): OtaConfigManager {
            return instance ?: synchronized(this) {
                instance ?: OtaConfigManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
