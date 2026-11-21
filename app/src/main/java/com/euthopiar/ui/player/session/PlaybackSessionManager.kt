package com.euthopiar.ui.player.session

import android.util.Log
import com.euthopiar.core.sync.supabase.RemotePlaybackSessionEntry
import com.euthopiar.core.sync.supabase.SupabaseSourceManagementClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Playback Session Manager for Movie Hub.
 *
 * Guarantees that:
 * 1. The active streaming source is strictly locked when playback begins.
 * 2. Background source discovery NEVER overwrites or interrupts the active playback URL.
 * 3. Normal buffering and network fluctuations never trigger source switching.
 * 4. Seeking (forward/backward: 2m, 5m, 15m) never triggers source switching.
 * 5. Pausing, resuming, minimizing, screen-lock, and reopening preserve the active source and position.
 * 6. Never silently switches to another source without user confirmation.
 * 7. When manually switching sources, playback position is preserved.
 */
class PlaybackSessionManager private constructor() {

    @Volatile
    private var currentSessionId: Long = 0L

    @Volatile
    private var mediaId: String? = null

    @Volatile
    private var season: Int? = null

    @Volatile
    private var episode: Int? = null

    @Volatile
    private var lockedServerId: String? = null

    @Volatile
    private var lockedStreamUrl: String? = null

    @Volatile
    private var isSourceLocked: Boolean = false

    @Volatile
    private var isUserSeeking: Boolean = false

    @Volatile
    private var lastSeekTimestampMs: Long = 0L

    @Volatile
    private var lastPlaybackPositionMs: Long = 0L

    @Volatile
    private var isPlaybackStarted: Boolean = false

    companion object {
        private const val TAG = "PlaybackSessionManager"
        private const val SEEK_GRACE_WINDOW_MS = 45_000L // 45 seconds immunity after seek

        @Volatile
        private var instance: PlaybackSessionManager? = null

        fun getInstance(): PlaybackSessionManager {
            return instance ?: synchronized(this) {
                instance ?: PlaybackSessionManager().also { instance = it }
            }
        }
    }

    /**
     * Initializes a new playback session for a movie or TV episode.
     */
    fun startSession(mediaId: String, season: Int? = null, episode: Int? = null): Long {
        synchronized(this) {
            currentSessionId = System.currentTimeMillis()
            this.mediaId = mediaId
            this.season = season
            this.episode = episode
            this.lockedServerId = null
            this.lockedStreamUrl = null
            this.isSourceLocked = false
            this.isUserSeeking = false
            this.lastSeekTimestampMs = 0L
            this.lastPlaybackPositionMs = 0L
            this.isPlaybackStarted = false
            Log.d(TAG, "Started new playback session $currentSessionId for media=$mediaId, s=$season, e=$episode")
            return currentSessionId
        }
    }

    /**
     * Locks the active source when playback starts.
     * Once locked, background discovery or temporary buffering cannot replace this source.
     */
    fun lockActiveSource(serverId: String, streamUrl: String) {
        synchronized(this) {
            this.lockedServerId = serverId
            this.lockedStreamUrl = streamUrl
            this.isSourceLocked = true
            this.isPlaybackStarted = true
            Log.i(TAG, "Source LOCKED for session $currentSessionId: serverId=$serverId")

            // Log session lock to Supabase backend asynchronously
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    SupabaseSourceManagementClient.getInstance().logPlaybackSession(
                        RemotePlaybackSessionEntry(
                            id = "sess_$currentSessionId",
                            mediaId = mediaId.orEmpty(),
                            season = season,
                            episode = episode,
                            activeSourceId = serverId,
                            playbackPositionMs = lastPlaybackPositionMs,
                            isLocked = true
                        )
                    )
                } catch (_: Exception) {}
            }
        }
    }

    fun unlockForFailover() {
        synchronized(this) {
            isSourceLocked = false
            Log.d(TAG, "Source UNLOCKED for failover in session $currentSessionId")
        }
    }

    /**
     * Called when the user initiates a seek (scrub, forward/backward jump).
     */
    fun onSeekStarted(targetPositionMs: Long) {
        isUserSeeking = true
        lastSeekTimestampMs = System.currentTimeMillis()
        lastPlaybackPositionMs = targetPositionMs
        Log.d(TAG, "Seek initiated to ${targetPositionMs}ms. Automatic source switching disabled.")
    }

    /**
     * Called when player recovers from seek (reaches STATE_READY or playing).
     */
    fun onSeekCompleted(currentPositionMs: Long) {
        isUserSeeking = false
        lastPlaybackPositionMs = currentPositionMs
        lastSeekTimestampMs = System.currentTimeMillis()
        Log.d(TAG, "Seek completed at ${currentPositionMs}ms.")
    }

    /**
     * Updates tracked playback position.
     */
    fun updatePosition(positionMs: Long) {
        if (positionMs > 0L) {
            lastPlaybackPositionMs = positionMs
        }
    }

    fun getLastPlaybackPosition(): Long = lastPlaybackPositionMs

    val isSeeking: Boolean get() = isUserSeeking
    val isPlaybackActive: Boolean get() = isPlaybackStarted || isSourceLocked

    /**
     * Returns true if source is locked and must NOT be automatically switched.
     * Prevents unexpected source switching during seeking or temporary buffering.
     */
    fun isSourceSwitchingForbidden(): Boolean {
        if (isUserSeeking) return true
        val timeSinceSeek = System.currentTimeMillis() - lastSeekTimestampMs
        if (timeSinceSeek in 0L..SEEK_GRACE_WINDOW_MS) {
            return true
        }
        return isSourceLocked || isPlaybackStarted
    }

    /**
     * Checks if a background discovery emission should be rejected from overwriting playback.
     */
    fun shouldBlockBackgroundStreamOverwrite(): Boolean {
        return isPlaybackStarted || isSourceLocked
    }

    /**
     * Explicit user action to switch sources.
     * Preserves playback position and re-locks to the new source.
     */
    fun manualSourceSwitch(newServerId: String, newStreamUrl: String): Long {
        synchronized(this) {
            val resumePos = lastPlaybackPositionMs
            this.lockedServerId = newServerId
            this.lockedStreamUrl = newStreamUrl
            this.isSourceLocked = true
            this.isUserSeeking = false
            Log.i(TAG, "Manual source switch to $newServerId, preserving position at ${resumePos}ms")
            return resumePos
        }
    }

    /**
     * Resets the session on player exit.
     */
    fun endSession() {
        synchronized(this) {
            isSourceLocked = false
            isPlaybackStarted = false
            isUserSeeking = false
            lockedServerId = null
            lockedStreamUrl = null
            Log.d(TAG, "Ended playback session $currentSessionId")
        }
    }
}
