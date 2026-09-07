package app.batstats.battery.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.util.BatteryCapacity
import app.batstats.battery.util.BatteryReader
import app.batstats.settings.AppSettings
import app.batstats.settings.monitoringIntervalMs
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.abs

class BatteryRepository(
    private val context: Context,
    private val db: BatteryDatabase,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val scope: CoroutineScope
) {
    private val batteryDao = db.batteryDao()
    val sessionDao = db.sessionDao()

    // Settings flows
    val monitoringInterval: Flow<Long> = settingsRepository.flow.map { it.monitoringIntervalMs }
    val showNotification: Flow<Boolean> = settingsRepository.flow.map { it.showNotification }
    val lowBatteryThreshold: Flow<Int> = settingsRepository.flow.map { it.lowBatteryThreshold }
    val highBatteryThreshold: Flow<Int> = settingsRepository.flow.map { it.highBatteryThreshold }
    val temperatureThreshold: Flow<Float> = settingsRepository.flow.map { it.temperatureThreshold }

    // Realtime state
    private val _realtime = MutableStateFlow(Realtime())
    val realtimeFlow: StateFlow<Realtime> = _realtime.asStateFlow()

    // Monitoring state
    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoringFlow: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private var samplingJob: Job? = null
    private var pendingSampleCount: Long = 0L

    /**
     * Battery state arrives from two places at once - the system broadcast on the main
     * thread and the polling loop - so session bookkeeping has to be serialised or a single
     * plug-in can open two sessions.
     */
    private val sessionMutex = Mutex()

    @Volatile private var lastPlugged: Int? = null

    // Broadcast receiver for immediate system updates
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            processBatteryState(intent)
        }
    }

    // Active session
    val activeSessionFlow: Flow<ChargeSession?> = sessionDao.activeFlow()

    // Recent samples based on settings
    @OptIn(ExperimentalCoroutinesApi::class)
    fun recentSamplesFlow(durationMs: Long): Flow<List<BatterySample>> =
        windowStarts(durationMs).flatMapLatest { since ->
            batteryDao.samplesBetween(since, Long.MAX_VALUE)
        }

    /**
     * The start of a window that keeps moving. This used to be computed once, when the flow
     * was created, which pinned the chart to whatever moment the screen was opened: leave
     * the dashboard up for an hour and "last 15 minutes" was really "the hour since you got
     * here".
     */
    private fun windowStarts(durationMs: Long): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis() - durationMs)
            delay(windowSlideIntervalMs(durationMs))
        }
    }

    fun samplesBetween(start: Long, end: Long): Flow<List<BatterySample>> {
        return batteryDao.samplesBetween(start, end)
    }

    suspend fun getSettings(): AppSettings = settingsRepository.flow.first()

    fun startSampling() {
        if (_isMonitoring.value) return
        _isMonitoring.value = true

        // Register Receiver for system broadcasts (plug/unplug, % change)
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)

        // Start Polling Coroutine for current/voltage fluctuations
        // Android's ACTION_BATTERY_CHANGED is "sticky" but doesn't fire often enough
        // to show live current changes. We poll BatteryManager properties.
        samplingJob = scope.launch {
            settingsRepository.flow
                .map { it.monitoringIntervalMs }
                .distinctUntilChanged()
                .collectLatest { intervalMs ->
                    while (isActive) {
                        val intent = context.registerReceiver(null, filter)
                        if (intent != null) {
                            processBatteryState(intent, persist = true)
                        }
                        delay(intervalMs)
                    }
                }
        }
    }

    fun stopSampling() {
        if (!_isMonitoring.value) return
        _isMonitoring.value = false

        samplingJob?.cancel()
        samplingJob = null

        // Drop the plug baseline: the next reading should re-seed it rather than compare
        // against however the world looked before monitoring was switched off.
        lastPlugged = null

        if (pendingSampleCount > 0) {
            scope.launch {
                settingsRepository.update {
                    it.copy(totalSamplesCollected = it.totalSamplesCollected + pendingSampleCount)
                }
                pendingSampleCount = 0
            }
        }

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
            // Ignore if already unregistered
        }
    }

    suspend fun startSession(type: SessionType) = sessionMutex.withLock {
        startSession(type, autoStarted = false)
    }

    suspend fun endCurrentSession() = sessionMutex.withLock {
        sessionDao.active()?.let { completeSession(it) }
    }

    private suspend fun startSession(type: SessionType, autoStarted: Boolean) {
        val session = ChargeSession(
            sessionId = java.util.UUID.randomUUID().toString(),
            type = type,
            startTime = System.currentTimeMillis(),
            startLevel = _realtime.value.level,
            endTime = null, endLevel = null, deltaUah = null, avgCurrentUa = null,
            estCapacityMah = null,
            autoStarted = autoStarted
        )
        sessionDao.upsert(session)
    }

    /**
     * Closes [session] out, filling in the figures History shows. `complete` used to be
     * handed nulls for the charge delta and the capacity, so those columns were never once
     * populated.
     */
    private suspend fun completeSession(session: ChargeSession) {
        val end = System.currentTimeMillis()
        val endLevel = _realtime.value.level
        val samples = batteryDao.samplesBetween(session.startTime, end).first()

        val avgCurrent = samples.mapNotNull { it.currentNowUa }
            .takeIf { it.isNotEmpty() }
            ?.average()
            ?.toLong()

        // The charge counter moves monotonically within a session, so its endpoints give
        // the charge that actually shifted - steadier than integrating a noisy current.
        val counters = samples.mapNotNull { it.chargeCounterUah }
        val deltaUah = if (counters.size >= 2) counters.last() - counters.first() else null

        val estCapacity = BatteryCapacity.fromChargeDelta(deltaUah, endLevel - session.startLevel)
        BatteryCapacity.remember(estCapacity?.toDouble())

        sessionDao.complete(session.sessionId, end, endLevel, deltaUah, avgCurrent, estCapacity)
    }

    /**
     * Opens and closes sessions as the charger comes and goes, so History fills itself in.
     * Sessions were manual-only before this: unless you remembered to press the button at
     * both ends of every charge, the screen stayed empty.
     */
    private suspend fun onPluggedChanged(charging: Boolean) = sessionMutex.withLock {
        val wanted = if (charging) SessionType.CHARGE else SessionType.DISCHARGE
        val active = sessionDao.active()
        if (active != null) {
            // A session someone started by hand is theirs to end.
            if (!active.autoStarted) return@withLock
            // The system broadcast and the polling loop can both report the same plug
            // change; without this the second one would close the session the first just
            // opened and leave an empty one behind.
            if (active.type == wanted) return@withLock
            completeSession(active)
        }
        startSession(wanted, autoStarted = true)
    }

    private fun processBatteryState(intent: Intent, persist: Boolean = false) {
        // Shared with the widgets, which read the same sticky broadcast without a service.
        val sample = BatteryReader.sampleFrom(context, intent)
        val pluggedState = sample.plugged
        val currentNow = sample.currentNowUa ?: 0L
        val voltage = sample.voltageMv ?: 0

        // Calculate Power (mW) = (uA * mV) / 1,000,000
        val powerMw = (abs(currentNow) * voltage) / 1_000_000f

        // Update StateFlow for UI
        _realtime.value = Realtime(
            level = sample.levelPercent,
            plugged = pluggedState,
            currentMa = (currentNow / 1000).toInt(),
            voltageMv = voltage,
            powerMw = powerMw,
            temperatureC = (sample.temperatureDeciC ?: 0) / 10f,
            sample = sample
        )

        // Charger came or went. Checked on every update, not just persisted ones: the
        // system broadcast is what reports a plug change promptly.
        val wasPlugged = lastPlugged
        lastPlugged = pluggedState
        if (wasPlugged != null && (wasPlugged == 0) != (pluggedState == 0)) {
            scope.launch { onPluggedChanged(charging = pluggedState != 0) }
        }

        // Persist to DB
        if (persist) {
            scope.launch {
                batteryDao.insertSample(sample)
                pendingSampleCount++
                if (pendingSampleCount >= 10) {
                    settingsRepository.update {
                        it.copy(totalSamplesCollected = it.totalSamplesCollected + pendingSampleCount)
                    }
                    pendingSampleCount = 0
                }
            }
        }
    }

    data class Realtime(
        val level: Int = 0,
        val plugged: Int = 0,
        val currentMa: Int = 0,
        val voltageMv: Int = 0,
        val powerMw: Float = 0f,
        val temperatureC: Float = 0f,
        val sample: BatterySample? = null
    )
}

/**
 * How often a chart window advances. A sixtieth of the window is a step too small to notice
 * on screen, bounded so a 15-minute view does not re-query every few seconds and a week-long
 * one still moves while you watch it.
 */
internal fun windowSlideIntervalMs(durationMs: Long): Long =
    (durationMs / 60).coerceIn(30_000L, 15 * 60_000L)
