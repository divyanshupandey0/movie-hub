package com.euthopiar.core.backend.session

import android.util.Log
import com.euthopiar.core.backend.cache.SmartSourceCache
import com.euthopiar.core.backend.model.BackendContentDescriptor
import com.euthopiar.core.backend.model.BackendSourceItem
import com.euthopiar.core.backend.model.PlayableSourceState
import com.euthopiar.core.sync.supabase.RemotePlaybackSessionEntry
import com.euthopiar.core.sync.supabase.SupabaseSourceManagementClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Backend Playback Session Manager.
 *
 * Implements Section 8 guarantees:
 * 1. Generates unique session identifier for every playback request.
 * 2. Tracks requested content, active source, provider, season/episode, first-frame latency, errors, and failovers.
 * 3. On playback failure: records exact error, blacklists failed URL from retry, preserves playback position, and resolves next eligible candidate.
 * 4. Guards against source switching during normal buffering or seeking.
 */
class BackendPlaybackSessionManager private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeSessions = ConcurrentHashMap<String, PlaybackSessionState>()

    @Volatile
    private var currentActiveSessionId: String? = null

    companion object {
        private const val TAG = "PlaybackSessionManager"

        @Volatile
        private var instance: BackendPlaybackSessionManager? = null

        fun getInstance(): BackendPlaybackSessionManager {
            return instance ?: synchronized(this) {
                instance ?: BackendPlaybackSessionManager().also { instance = it }
            }
        }
    }

    data class PlaybackSessionState(
        val sessionId: String,
        val content: BackendContentDescriptor,
        var activeSource: BackendSourceItem?,
        val candidateSources: MutableList<BackendSourceItem>,
        val failedSourceIds: MutableSet<String> = mutableSetOf(),
        val failedUrls: MutableSet<String> = mutableSetOf(),
        var playbackPositionMs: Long = 0L,
        var durationMs: Long = 0L,
        var firstFrameRendered: Boolean = false,
        var firstFrameLatencyMs: Long? = null,
        var failoverCount: Int = 0,
        var isLocked: Boolean = true,
        var isBufferingOrSeeking: Boolean = false,
        val startedAtMs: Long = System.currentTimeMillis()
    )

    /**
     * Initializes a new playback session with a unique UUID request identifier.
     */
    fun startSession(
        content: BackendContentDescriptor,
        initialSource: BackendSourceItem?,
        availableCandidates: List<BackendSourceItem>,
        resumePositionMs: Long = 0L
    ): String {
        val sessionId = UUID.randomUUID().toString()
        val state = PlaybackSessionState(
            sessionId = sessionId,
            content = content,
            activeSource = initialSource,
            candidateSources = availableCandidates.toMutableList(),
            playbackPositionMs = resumePositionMs
        )

        activeSessions[sessionId] = state
        currentActiveSessionId = sessionId

        // Asynchronously persist playback session to Supabase
        scope.launch {
            try {
                val remoteEntry = RemotePlaybackSessionEntry(
                    id = sessionId,
                    mediaId = content.id,
                    season = content.season,
                    episode = content.episode,
                    activeSourceId = initialSource?.id,
                    activeProviderId = initialSource?.provider,
                    selectedAudioLanguage = initialSource?.language?.firstOrNull() ?: "hi",
                    selectedQuality = initialSource?.quality ?: "1080p",
                    playbackPositionMs = resumePositionMs,
                    isLocked = true
                )
                SupabaseSourceManagementClient.getInstance().logPlaybackSession(remoteEntry)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log playback session start: ${e.message}")
            }
        }

        return sessionId
    }

    /**
     * Marks first video frame successfully rendered for the active session.
     */
    fun onFirstFrameRendered(sessionId: String = currentActiveSessionId.orEmpty()) {
        val session = activeSessions[sessionId] ?: return
        if (!session.firstFrameRendered) {
            session.firstFrameRendered = true
            session.firstFrameLatencyMs = System.currentTimeMillis() - session.startedAtMs
            Log.i(TAG, "Session [$sessionId] First frame rendered in ${session.firstFrameLatencyMs}ms on ${session.activeSource?.sourceName}")
        }
    }

    /**
     * Updates playback position and duration periodically.
     */
    fun updateProgress(sessionId: String = currentActiveSessionId.orEmpty(), positionMs: Long, durationMs: Long) {
        val session = activeSessions[sessionId] ?: return
        session.playbackPositionMs = positionMs
        session.durationMs = durationMs
    }

    /**
     * Flags buffer/seeking state to prevent premature source failovers.
     */
    fun setBufferingOrSeeking(sessionId: String = currentActiveSessionId.orEmpty(), isBufferingOrSeeking: Boolean) {
        val session = activeSessions[sessionId] ?: return
        session.isBufferingOrSeeking = isBufferingOrSeeking
    }

    /**
     * Determines whether source failover is currently prohibited.
     */
    fun isSourceSwitchingForbidden(sessionId: String = currentActiveSessionId.orEmpty()): Boolean {
        val session = activeSessions[sessionId] ?: return false
        // Never switch sources during normal buffering or seeking
        return session.isBufferingOrSeeking
    }

    /**
     * Handles stream failure: records exact error, blacklists URL, and returns the next eligible candidate.
     */
    fun onPlaybackError(
        sessionId: String = currentActiveSessionId.orEmpty(),
        errorCode: String,
        errorMessage: String?
    ): BackendSourceItem? {
        val session = activeSessions[sessionId] ?: return null
        val failed = session.activeSource
        if (failed != null) {
            session.failedSourceIds.add(failed.id)
            session.failedUrls.add(failed.url)
            SmartSourceCache.getInstance().markUrlFailed(failed.url)
            Log.w(TAG, "Playback failed for source [${failed.sourceName}] (${failed.url}) in session [$sessionId]: $errorCode - $errorMessage")

            // Report failure to Supabase
            scope.launch {
                try {
                    SupabaseSourceManagementClient.getInstance().recordSourceFailure(
                        providerId = failed.provider,
                        mediaId = session.content.id,
                        errorCode = errorCode,
                        errorMessage = errorMessage,
                        sourceId = failed.id
                    )
                } catch (_: Exception) {}
            }
        }

        session.failoverCount++

        // Find next eligible candidate: must not be in failed IDs or failed URLs, and must be playable
        val nextCandidate = session.candidateSources.firstOrNull { candidate ->
            candidate.id !in session.failedSourceIds &&
                    candidate.url !in session.failedUrls &&
                    candidate.state.isPlayableEligible
        }

        if (nextCandidate != null) {
            session.activeSource = nextCandidate
            Log.i(TAG, "Failover activated for session [$sessionId]: switching to [${nextCandidate.sourceName}] (${nextCandidate.quality})")
        } else {
            Log.e(TAG, "No more eligible sources available for session [$sessionId]")
        }

        return nextCandidate
    }

    /**
     * Ends the playback session and reports final stats.
     */
    fun endSession(sessionId: String = currentActiveSessionId.orEmpty()) {
        val session = activeSessions.remove(sessionId) ?: return
        if (currentActiveSessionId == sessionId) {
            currentActiveSessionId = null
        }
        Log.i(TAG, "Session [$sessionId] ended. Failovers: ${session.failoverCount}, Final Position: ${session.playbackPositionMs}ms")
    }

    fun getActiveSession(sessionId: String = currentActiveSessionId.orEmpty()): PlaybackSessionState? {
        return activeSessions[sessionId]
    }
}
