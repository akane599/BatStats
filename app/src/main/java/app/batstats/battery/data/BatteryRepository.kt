package app.batstats.battery.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import app.batstats.battery.data.db.BatteryCurrentPoint
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.data.db.ChargeSession
import app.batstats.battery.data.db.SessionType
import app.batstats.battery.util.BatteryCapacity
import app.batstats.battery.util.BatteryReader
import app.batstats.settings.AppSettings
import app.batstats.settings.monitoringIntervalMs
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import androidx.room.withTransaction
import java.util.concurrent.atomic.AtomicLong
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
    private val generation = AtomicLong()

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

    @OptIn(ExperimentalCoroutinesApi::class)
    fun currentChartFlow(durationMs: Long): Flow<List<BatteryCurrentPoint>> =
        windowStarts(durationMs).flatMapLatest { since ->
            batteryDao.currentChart(since, Long.MAX_VALUE, (durationMs / 300).coerceAtLeast(1_000L))
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

    suspend fun clearHistory() = sessionMutex.withLock {
        withContext(Dispatchers.IO) { db.clearAllTables() }
        pendingSampleCount = 0L
        lastPlugged = null
    }

    suspend fun getSettings(): AppSettings = settingsRepository.flow.first()

    fun startSampling() {
        if (_isMonitoring.value) return
        generation.incrementAndGet()
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
        val stoppedGeneration = generation.incrementAndGet()

        samplingJob?.cancel()
        samplingJob = null

        scope.launch {
            sessionMutex.withLock {
                if (generation.get() != stoppedGeneration) return@withLock
                lastPlugged = null
                sessionDao.active()?.takeIf { it.autoStarted }?.let { completeSession(it) }
                flushSampleCount()
            }
        }

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {
            // Ignore if already unregistered
        }
    }

    suspend fun startSession(type: SessionType) = sessionMutex.withLock {
        if (!_isMonitoring.value || _realtime.value.sample == null) return@withLock
        db.withTransaction {
            val active = sessionDao.active()
            // Repeated taps must not create another open manual session.
            if (active != null && !active.autoStarted) return@withTransaction
            active?.let { completeSession(it) }
            startSession(type, autoStarted = false)
        }
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
        val avgCurrent = batteryDao.averageCurrent(session.startTime, end)?.toLong()

        // The charge counter moves monotonically within a session, so its endpoints give
        // the charge that actually shifted - steadier than integrating a noisy current.
        val first = batteryDao.firstCounter(session.startTime, end)
        val last = batteryDao.lastCounter(session.startTime, end)
        val deltaUah = if (first != null && last != null && first.timestamp < last.timestamp) {
            last.chargeCounterUah!! - first.chargeCounterUah!!
        } else null

        val estCapacity = BatteryCapacity.fromChargeDelta(deltaUah, endLevel - session.startLevel)
        BatteryCapacity.remember(estCapacity?.toDouble())

        sessionDao.complete(session.sessionId, end, endLevel, deltaUah, avgCurrent, estCapacity)
    }

    /**
     * Opens and closes sessions as the charger comes and goes, so History fills itself in.
     * Sessions were manual-only before this: unless you remembered to press the button at
     * both ends of every charge, the screen stayed empty.
     */
    private suspend fun onPluggedChanged(charging: Boolean) = db.withTransaction {
        val wanted = if (charging) SessionType.CHARGE else SessionType.DISCHARGE
        val active = sessionDao.active()
        if (active != null) {
            // A session someone started by hand is theirs to end.
            if (!active.autoStarted) return@withTransaction
            // The system broadcast and the polling loop can both report the same plug
            // change; without this the second one would close the session the first just
            // opened and leave an empty one behind.
            if (active.type == wanted) return@withTransaction
            completeSession(active)
        }
        startSession(wanted, autoStarted = true)
    }

    /** Refresh the dashboard without starting a service or writing history. */
    fun refreshBatteryReading() {
        val expected = generation.get()
        scope.launch {
            sessionMutex.withLock {
                if (expected != generation.get()) return@withLock
                BatteryReader.currentSample(context)?.let { updateRealtime(it) }
            }
        }
    }

    private fun updateRealtime(sample: BatterySample) {
        if (sample.levelPercent !in 0..100) return
        val currentNow = sample.currentNowUa ?: 0L
        val voltage = sample.voltageMv ?: 0
        _realtime.value = Realtime(
            level = sample.levelPercent,
            plugged = sample.plugged,
            currentMa = (currentNow / 1000).toInt(),
            voltageMv = voltage,
            powerMw = (abs(currentNow.toDouble()) * voltage / 1_000_000).toFloat(),
            temperatureC = (sample.temperatureDeciC ?: 0) / 10f,
            sample = sample
        )
    }

    private fun processBatteryState(intent: Intent, persist: Boolean = false) {
        val expected = generation.get()
        scope.launch {
            // Serialize the reading, session boundary, insert and sample counter together.
            // A queued reading from a previous monitoring run must not reopen a session.
            sessionMutex.withLock {
                if (!_isMonitoring.value || expected != generation.get()) return@withLock
                val sample = BatteryReader.sampleFrom(context, intent)
                if (sample.levelPercent !in 0..100) return@withLock
                updateRealtime(sample)
                val wasPlugged = lastPlugged
                lastPlugged = sample.plugged
                if (wasPlugged == null || (wasPlugged == 0) != (sample.plugged == 0)) {
                    onPluggedChanged(charging = sample.plugged != 0)
                }
                if (persist) {
                    batteryDao.insertSample(sample)
                    pendingSampleCount++
                    if (pendingSampleCount >= 10) flushSampleCount()
                }
            }
        }
    }

    private suspend fun flushSampleCount() {
        if (pendingSampleCount == 0L) return
        val count = pendingSampleCount
        settingsRepository.update { it.copy(totalSamplesCollected = it.totalSamplesCollected + count) }
        pendingSampleCount = 0
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
