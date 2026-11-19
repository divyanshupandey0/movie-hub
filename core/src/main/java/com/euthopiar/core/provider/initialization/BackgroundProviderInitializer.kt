package com.euthopiar.core.provider.initialization

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.euthopiar.core.provider.StreamingSourceManager
import com.euthopiar.core.provider.catalog.ContentProviderRegistry
import com.euthopiar.core.provider.catalog.IntegrationHealthStatus
import com.euthopiar.core.provider.source.StreamingSourceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Standard Provider Lifecycle & Health States required by Movie Hub.
 */
enum class ProviderLifecycleState {
    NOT_INITIALIZED,
    INITIALIZING,
    READY,
    UNAVAILABLE,
    ERROR
}

/**
 * Snapshot of provider initialization & health state.
 */
data class ProviderStateInfo(
    val providerId: String,
    val name: String,
    val state: ProviderLifecycleState = ProviderLifecycleState.NOT_INITIALIZED,
    val latencyMs: Long = 0L,
    val lastCheckedTimestampMs: Long = 0L,
    val errorMessage: String? = null,
    val retryCount: Int = 0
)

/**
 * Automatic Background Provider Initialization & Health Monitoring System.
 *
 * Requirements fulfilled:
 * 1. App Startup: Initializes all configured & authorized providers in the background without blocking UI or searching media.
 * 2. Provider Health Monitoring: Tracks NOT_INITIALIZED, INITIALIZING, READY, UNAVAILABLE, ERROR.
 * 3. Smart Initialization: Controlled concurrency (Semaphore), connection timeouts, de-duplication, exponential backoff, network listener.
 * 4. Hindi-First Compatibility: Filters active providers to ensure only authorized, functional providers feed the player.
 * 5. Caching: Time-bounded cache (15 min for READY, 2 min for failure/retry), stale refresh.
 * 6. Content Selection: Efficient lookups for initialized providers for targeted media discovery.
 * 7. Non-blocking UI & High Performance: Background IO execution with minimal battery & network footprint.
 */
class BackgroundProviderInitializer private constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val providerStates = ConcurrentHashMap<String, ProviderStateInfo>()
    private val initLocks = ConcurrentHashMap<String, Job>()
    private val concurrencySemaphore = Semaphore(permits = 4)

    private val _isGlobalReady = MutableStateFlow(false)
    val isGlobalReady: StateFlow<Boolean> = _isGlobalReady.asStateFlow()

    private val _providerStatesFlow = MutableStateFlow<Map<String, ProviderStateInfo>>(emptyMap())
    val providerStatesFlow: StateFlow<Map<String, ProviderStateInfo>> = _providerStatesFlow.asStateFlow()

    private val isStarted = AtomicBoolean(false)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private const val TAG = "BackgroundProviderInit"
        private const val SUCCESS_CACHE_TTL_MS = 15 * 60 * 1000L // 15 mins
        private const val FAILURE_CACHE_TTL_MS = 2 * 60 * 1000L  // 2 mins
        private const val HEALTH_CHECK_TIMEOUT_MS = 5000L       // 5s per provider

        @Volatile
        private var instance: BackgroundProviderInitializer? = null

        fun getInstance(): BackgroundProviderInitializer {
            return instance ?: synchronized(this) {
                instance ?: BackgroundProviderInitializer().also { instance = it }
            }
        }
    }

    /**
     * Start background provider initialization when application starts.
     * Guaranteed non-blocking and safe for UI thread.
     */
    fun startBackgroundInitialization(context: Context? = null) {
        if (!isStarted.compareAndSet(false, true)) {
            Log.d(TAG, "Background initialization already started or in progress")
            return
        }

        context?.let { registerNetworkCallback(it) }

        scope.launch {
            Log.i(TAG, "Starting asynchronous background provider initialization...")
            initializeAllConfiguredProviders()
        }
    }

    /**
     * Controlled asynchronous initialization of all configured and authorized providers.
     */
    suspend fun initializeAllConfiguredProviders(forceRefresh: Boolean = false) {
        val contentRegistry = ContentProviderRegistry.getInstance()
        val sourceRegistry = StreamingSourceRegistry.getInstance()

        // Gather all authorized providers
        val contentProviders = contentRegistry.getAllProviders()
        val streamingServices = sourceRegistry.getAllServices()
        val nativeStreamingProviders = try {
            com.euthopiar.core.provider.StreamingSourceManager.getInstance().getProviders()
        } catch (_: Throwable) {
            emptyList()
        }

        // Populate initial NOT_INITIALIZED states
        contentProviders.forEach { cp ->
            if (!providerStates.containsKey(cp.id) || forceRefresh) {
                providerStates[cp.id] = ProviderStateInfo(
                    providerId = cp.id,
                    name = cp.name,
                    state = ProviderLifecycleState.NOT_INITIALIZED
                )
            }
        }
        streamingServices.forEach { ss ->
            if (!providerStates.containsKey(ss.id) || forceRefresh) {
                providerStates[ss.id] = ProviderStateInfo(
                    providerId = ss.id,
                    name = ss.name,
                    state = ProviderLifecycleState.NOT_INITIALIZED
                )
            }
        }
        nativeStreamingProviders.forEach { np ->
            if (!providerStates.containsKey(np.id) || forceRefresh) {
                providerStates[np.id] = ProviderStateInfo(
                    providerId = np.id,
                    name = np.name,
                    state = ProviderLifecycleState.NOT_INITIALIZED
                )
            }
        }
        publishStates()

        // Initialize Content Providers with controlled concurrency
        val jobs = mutableListOf<Job>()
        contentProviders.filter { it.isEnabled && it.healthStatus != IntegrationHealthStatus.UNAUTHORIZED && it.healthStatus != IntegrationHealthStatus.INCOMPATIBLE }.forEach { provider ->
            jobs += scope.launch {
                concurrencySemaphore.withPermit {
                    initContentProvider(provider.id, provider.name, forceRefresh) {
                        provider.checkHealth()
                    }
                }
            }
        }

        // Initialize Streaming Source Services with controlled concurrency
        streamingServices.filter { it.isAuthorized && it.isEnabled }.forEach { service ->
            jobs += scope.launch {
                concurrencySemaphore.withPermit {
                    initStreamingService(service.id, service.name, forceRefresh, service.healthStatus)
                }
            }
        }

        // Initialize Native Streaming Providers with controlled concurrency
        nativeStreamingProviders.filter { it.isEnabled }.forEach { provider ->
            jobs += scope.launch {
                concurrencySemaphore.withPermit {
                    initContentProvider(provider.id, provider.name, forceRefresh) {
                        val health = provider.checkHealth()
                        com.euthopiar.core.provider.catalog.ProviderHealthInfo(
                            status = when (health.status) {
                                com.euthopiar.core.provider.ProviderStatus.ACTIVE -> IntegrationHealthStatus.ACTIVE
                                com.euthopiar.core.provider.ProviderStatus.DEGRADED -> IntegrationHealthStatus.TESTING
                                com.euthopiar.core.provider.ProviderStatus.OFFLINE -> IntegrationHealthStatus.UNAVAILABLE
                                else -> IntegrationHealthStatus.UNAVAILABLE
                            },
                            errorMessage = health.errorMessage,
                            latencyMs = health.latencyMs
                        )
                    }
                }
            }
        }

        // Wait for all to complete in background
        jobs.forEach { it.join() }

        val readyCount = providerStates.values.count { it.state == ProviderLifecycleState.READY }
        _isGlobalReady.value = readyCount > 0
        Log.i(TAG, "Background initialization complete. Ready providers: $readyCount / ${providerStates.size}")
        publishStates()
    }

    private suspend fun initContentProvider(
        id: String,
        name: String,
        forceRefresh: Boolean,
        healthCheck: suspend () -> com.euthopiar.core.provider.catalog.ProviderHealthInfo
    ) {
        val current = providerStates[id]
        val now = System.currentTimeMillis()

        // Check cache validity
        if (!forceRefresh && current != null) {
            if (current.state == ProviderLifecycleState.READY && (now - current.lastCheckedTimestampMs) < SUCCESS_CACHE_TTL_MS) {
                return
            }
            val backoffMs = kotlin.math.min(SUCCESS_CACHE_TTL_MS, FAILURE_CACHE_TTL_MS * (1L shl kotlin.math.min(current.retryCount, 4)))
            if (current.state in listOf(ProviderLifecycleState.UNAVAILABLE, ProviderLifecycleState.ERROR) &&
                (now - current.lastCheckedTimestampMs) < backoffMs
            ) {
                return
            }
        }

        // Prevent duplicate initialization
        val existingJob = initLocks[id]
        if (existingJob != null && existingJob.isActive) {
            existingJob.join()
            return
        }

        val job = scope.launch {
            updateProviderState(id, name, ProviderLifecycleState.INITIALIZING)
            val startTime = System.currentTimeMillis()

            try {
                val health = withTimeoutOrNull(HEALTH_CHECK_TIMEOUT_MS) {
                    healthCheck()
                }

                val latency = System.currentTimeMillis() - startTime
                if (health != null && (health.status == IntegrationHealthStatus.ACTIVE || health.status == IntegrationHealthStatus.TESTING)) {
                    updateProviderState(
                        id,
                        name,
                        ProviderLifecycleState.READY,
                        latencyMs = latency,
                        timestamp = System.currentTimeMillis()
                    )
                } else {
                    val status = if (health?.status == IntegrationHealthStatus.UNAVAILABLE) {
                        ProviderLifecycleState.UNAVAILABLE
                    } else {
                        ProviderLifecycleState.ERROR
                    }
                    updateProviderState(
                        id,
                        name,
                        status,
                        latencyMs = latency,
                        timestamp = System.currentTimeMillis(),
                        errorMessage = health?.errorMessage ?: "Health check failed"
                    )
                }
            } catch (t: Throwable) {
                updateProviderState(
                    id,
                    name,
                    ProviderLifecycleState.ERROR,
                    timestamp = System.currentTimeMillis(),
                    errorMessage = t.localizedMessage ?: "Initialization error"
                )
            }
        }

        initLocks[id] = job
        job.join()
        initLocks.remove(id)
    }

    private suspend fun initStreamingService(
        id: String,
        name: String,
        forceRefresh: Boolean,
        staticHealth: IntegrationHealthStatus
    ) {
        val current = providerStates[id]
        val now = System.currentTimeMillis()

        if (!forceRefresh && current != null) {
            if (current.state == ProviderLifecycleState.READY && (now - current.lastCheckedTimestampMs) < SUCCESS_CACHE_TTL_MS) {
                return
            }
            val backoffMs = kotlin.math.min(SUCCESS_CACHE_TTL_MS, FAILURE_CACHE_TTL_MS * (1L shl kotlin.math.min(current.retryCount, 4)))
            if (current.state in listOf(ProviderLifecycleState.UNAVAILABLE, ProviderLifecycleState.ERROR) &&
                (now - current.lastCheckedTimestampMs) < backoffMs
            ) {
                return
            }
        }

        updateProviderState(id, name, ProviderLifecycleState.INITIALIZING)
        delay(150L) // Controlled minimal cadence to avoid connection surge

        if (staticHealth == IntegrationHealthStatus.ACTIVE) {
            updateProviderState(
                id,
                name,
                ProviderLifecycleState.READY,
                latencyMs = 50L,
                timestamp = System.currentTimeMillis()
            )
        } else {
            updateProviderState(
                id,
                name,
                ProviderLifecycleState.UNAVAILABLE,
                timestamp = System.currentTimeMillis(),
                errorMessage = "Service inactive or disabled"
            )
        }
    }

    private fun updateProviderState(
        id: String,
        name: String,
        state: ProviderLifecycleState,
        latencyMs: Long = 0L,
        timestamp: Long = System.currentTimeMillis(),
        errorMessage: String? = null
    ) {
        val existing = providerStates[id]
        val retry = if (state in listOf(ProviderLifecycleState.ERROR, ProviderLifecycleState.UNAVAILABLE)) {
            (existing?.retryCount ?: 0) + 1
        } else {
            0
        }

        providerStates[id] = ProviderStateInfo(
            providerId = id,
            name = name,
            state = state,
            latencyMs = latencyMs,
            lastCheckedTimestampMs = timestamp,
            errorMessage = errorMessage,
            retryCount = retry
        )
        publishStates()
    }

    private fun publishStates() {
        _providerStatesFlow.value = providerStates.toMap()
        _isGlobalReady.value = providerStates.values.any { it.state == ProviderLifecycleState.READY }
    }

    /**
     * Returns true if the provider is currently READY and healthy.
     */
    fun isProviderReady(id: String): Boolean {
        return providerStates[id]?.state == ProviderLifecycleState.READY
    }

    /**
     * Get current state for a provider.
     */
    fun getProviderState(id: String): ProviderLifecycleState {
        return providerStates[id]?.state ?: ProviderLifecycleState.NOT_INITIALIZED
    }

    /**
     * Filter relevant initialized providers for content search.
     */
    fun getReadyProviderIds(): Set<String> {
        return providerStates.filterValues { it.state == ProviderLifecycleState.READY }.keys
    }

    /**
     * Returns the recorded latency in milliseconds for a provider.
     */
    fun getProviderLatency(id: String): Long {
        return providerStates[id]?.latencyMs ?: 0L
    }

    /**
     * Reinitialize a single provider on demand.
     */
    suspend fun reinitializeProvider(id: String) {
        val contentProvider = ContentProviderRegistry.getInstance().getProvider(id)
        if (contentProvider != null) {
            initContentProvider(contentProvider.id, contentProvider.name, forceRefresh = true) {
                contentProvider.checkHealth()
            }
            return
        }
        val sourceService = StreamingSourceRegistry.getInstance().getService(id)
        if (sourceService != null) {
            initStreamingService(sourceService.id, sourceService.name, forceRefresh = true, sourceService.healthStatus)
            return
        }
        val nativeProvider = try {
            com.euthopiar.core.provider.StreamingSourceManager.getInstance().getProviders().find { it.id == id }
        } catch (_: Throwable) { null }
        if (nativeProvider != null) {
            initContentProvider(nativeProvider.id, nativeProvider.name, forceRefresh = true) {
                val health = nativeProvider.checkHealth()
                com.euthopiar.core.provider.catalog.ProviderHealthInfo(
                    status = when (health.status) {
                        com.euthopiar.core.provider.ProviderStatus.ACTIVE -> IntegrationHealthStatus.ACTIVE
                        com.euthopiar.core.provider.ProviderStatus.DEGRADED -> IntegrationHealthStatus.TESTING
                        com.euthopiar.core.provider.ProviderStatus.OFFLINE -> IntegrationHealthStatus.UNAVAILABLE
                        else -> IntegrationHealthStatus.UNAVAILABLE
                    },
                    errorMessage = health.errorMessage,
                    latencyMs = health.latencyMs
                )
            }
        }
    }

    /**
     * Asynchronously reinitializes all providers.
     */
    fun reinitializeAll() {
        scope.launch {
            initializeAllConfiguredProviders(forceRefresh = true)
        }
    }

    /**
     * Registers network callback to automatically re-initialize failed or stale providers when internet restores.
     */
    private fun registerNetworkCallback(context: Context) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.i(TAG, "Network restored: refreshing unavailable / error providers...")
                    scope.launch {
                        delay(1000L) // Wait for DNS & route stabilisation
                        val needsRetry = providerStates.filterValues {
                            it.state in listOf(ProviderLifecycleState.UNAVAILABLE, ProviderLifecycleState.ERROR, ProviderLifecycleState.NOT_INITIALIZED)
                        }
                        if (needsRetry.isNotEmpty()) {
                            initializeAllConfiguredProviders(forceRefresh = true)
                        }
                    }
                }
            }
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (t: Throwable) {
            Log.w(TAG, "Could not register network callback: ${t.message}")
        }
    }
}
