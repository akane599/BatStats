package app.batstats.battery.drain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.util.BatteryCapacity
import app.batstats.battery.util.BatteryReader
import app.batstats.battery.util.BatteryStatsParser
import app.batstats.battery.util.CheckinSource
import app.batstats.battery.util.ShellRunner
import app.batstats.settings.AppSettings
import app.batstats.settings.detailedStatsIntervalMs
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records screen/power boundaries and charge-counter deltas. Screen-on use is one state;
 * current magnitude cannot establish whether a person is active or the phone is idle.
 * CPU suspend time is elapsedRealtime minus uptime, measured within screen-off segments.
 */
class AdvancedDrainTracker(
    private val context: Context,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val checkinSource: CheckinSource,
    private val batteryRepository: BatteryRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "AdvancedDrainTracker"
    }

    private val running = AtomicBoolean(false)
    private var trackingJob: Job? = null
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val _drainState = MutableStateFlow(DrainState())
    val drainState: StateFlow<DrainState> = _drainState.asStateFlow()
    private val _snapshots = MutableStateFlow<List<DrainSnapshot>>(emptyList())
    val snapshots: StateFlow<List<DrainSnapshot>> = _snapshots.asStateFlow()
    private val snapshotBuffer = ArrayDeque<DrainSnapshot>()
    private var lastSnapshotElapsed: Long? = null
    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()
    private var receiverRegistered = false

    private val ledgerLock = Any()
    private val ledger = DrainLedger()
    private var sessionStartTime = System.currentTimeMillis()
    private var sessionGeneration = 0L
    private var knownCapacityMah = 0.0
    private var lastCapacityReadElapsed: Long? = null
    private var pendingPowerState: Boolean? = null
    @Volatile private var privilegedAccessAvailable = false

    private val stateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Only local reads under the lock; no shell/process work on this boundary.
            synchronized(ledgerLock) {
                if (!running.get()) return
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> pendingPowerState = true
                    Intent.ACTION_POWER_DISCONNECTED -> pendingPowerState = false
                }
                recordReading(
                    screenOverride = when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> true
                        Intent.ACTION_SCREEN_OFF -> false
                        else -> null
                    }
                )
            }
        }
    }

    fun isRunning(): Boolean = running.get()

    /**
     * A basic drain session needs no special access. A checkin dump used only as a capacity
     * fallback does, so never probe shell backends every few minutes on an ordinary device.
     */
    fun setPrivilegedAccessAvailable(available: Boolean) {
        val becameAvailable = available && !privilegedAccessAvailable
        privilegedAccessAvailable = available
        if (becameAvailable) synchronized(ledgerLock) {
            lastCapacityReadElapsed = null
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        _isTracking.value = true
        synchronized(ledgerLock) { pendingPowerState = null }
        resetSession()
        registerReceivers()
        // The repository already owns the user-selected sampling timer. Reuse those
        // readings rather than waking independently every minute and reading the same
        // battery properties twice.
        trackingJob = scope.launch {
            batteryRepository.realtimeFlow
                .map { it.sample }
                .filterNotNull()
                .distinctUntilChanged()
                .collect sampleLoop@ { sample ->
                try {
                    val generation = synchronized(ledgerLock) {
                        if (!running.get()) null else {
                            recordReading(snapshot = true, sampleOverride = sample)
                            sessionGeneration
                        }
                    } ?: return@sampleLoop
                    refreshCapacity(generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tracking loop", e)
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        trackingJob?.cancel()
        trackingJob = null
        unregisterReceivers()
        synchronized(ledgerLock) {
            val reading = readBattery()
            ledger.stop(reading.ledgerReading)
            publish(reading)
        }
        _isTracking.value = false
    }

    fun resetSession() {
        synchronized(ledgerLock) {
            sessionGeneration++
            sessionStartTime = System.currentTimeMillis()
            val reading = readBattery()
            ledger.reset(reading.ledgerReading, running.get())
            snapshotBuffer.clear()
            lastSnapshotElapsed = null
            _snapshots.value = emptyList()
            publish(reading)
        }
    }

    private fun registerReceivers() {
        if (receiverRegistered) return
        registerDrainSystemReceiver(context, stateChangeReceiver)
        receiverRegistered = true
    }

    private fun unregisterReceivers() {
        if (!receiverRegistered) return
        receiverRegistered = false
        context.unregisterReceiver(stateChangeReceiver)
    }

    private data class Reading(
        val sample: BatterySample?,
        val ledgerReading: DrainReading,
        val uptimeMs: Long,
        val wallTimeMs: Long,
        val dozing: Boolean
    )

    private fun readBattery(
        screenOverride: Boolean? = null,
        sampleOverride: BatterySample? = null
    ): Reading {
        val sample = sampleOverride ?: BatteryReader.currentSample(context)
        val reportedPower = sample?.let { it.plugged != 0 }
        // BatteryService may deliver the power event before updating its sticky battery
        // intent. Keep the event's state until a matching battery reading arrives.
        if (reportedPower == pendingPowerState) pendingPowerState = null
        val powered = pendingPowerState ?: reportedPower
        val elapsed = SystemClock.elapsedRealtime()
        val uptime = SystemClock.uptimeMillis()
        return Reading(
            sample = sample,
            ledgerReading = DrainReading(
                elapsedMs = elapsed,
                sleptMs = (elapsed - uptime).coerceAtLeast(0L),
                screenOn = screenOverride ?: powerManager.isInteractive,
                powered = powered,
                chargeMah = chargeCounterMah(sample?.chargeCounterUah, sample?.levelPercent ?: -1)
            ),
            uptimeMs = uptime,
            wallTimeMs = sample?.timestamp ?: System.currentTimeMillis(),
            dozing = powerManager.isDeviceIdleMode
        )
    }

    /** Caller holds ledgerLock so a reset/stop cannot be overwritten by a stale update. */
    private fun recordReading(
        snapshot: Boolean = false,
        screenOverride: Boolean? = null,
        sampleOverride: BatterySample? = null
    ) {
        val reading = readBattery(screenOverride, sampleOverride)
        ledger.advance(reading.ledgerReading)
        publish(reading)
        if (snapshot) {
            val state = _drainState.value
            val candidate = DrainSnapshot(
                timestamp = reading.wallTimeMs,
                batteryLevel = state.batteryLevel,
                batteryMah = state.batteryLevelMah,
                currentMa = reading.sample?.currentNowUa?.div(1000)?.toInt(),
                isScreenOn = state.isScreenOn,
                isCharging = state.isCharging,
                isDozing = state.isDozing,
                // Both counters have the same since-boot basis, regardless of access.
                cpuAwakeTimeMs = reading.uptimeMs,
                deepSleepTimeMs = reading.ledgerReading.sleptMs
            )
            if (shouldRecordDrainSnapshot(
                    snapshotBuffer.lastOrNull(), lastSnapshotElapsed,
                    candidate, reading.ledgerReading.elapsedMs
                )) {
                snapshotBuffer.addLast(candidate)
                while (snapshotBuffer.size > MAX_DRAIN_SNAPSHOTS) snapshotBuffer.removeFirst()
                lastSnapshotElapsed = reading.ledgerReading.elapsedMs
                // The graph only needs a minute-scale battery-level history. Publishing a
                // copied 1,000-element list on every 5-second current tick was pure churn.
                _snapshots.value = snapshotBuffer.toList()
            }
        }
    }

    private fun publish(reading: Reading) {
        // Reuse capacity established by another collector; otherwise derive it from
        // this same gauge/level pair, avoiding a second inconsistent sensor read.
        val capacity = BatteryCapacity.rememberedMah ?: reading.sample?.let {
            BatteryCapacity.fromChargeCounter(it.chargeCounterUah, it.levelPercent)
        }
        capacity?.let { knownCapacityMah = it; BatteryCapacity.remember(it) }
        val t = ledger.totals
        val sample = reading.sample
        _drainState.value = DrainState(
            timestamp = reading.wallTimeMs,
            batteryLevel = sample?.levelPercent ?: -1,
            batteryLevelMah = reading.ledgerReading.chargeMah,
            capacityMah = knownCapacityMah,
            hasBatteryReading = sample != null,
            isScreenOn = reading.ledgerReading.screenOn,
            isCharging = reading.ledgerReading.powered == true && sample?.plugged != 0 &&
                sample?.status == BatteryManager.BATTERY_STATUS_CHARGING,
            isPowered = reading.ledgerReading.powered == true,
            isDozing = reading.dozing,
            screenOnDrainMah = t.screenOnDrainMah,
            screenOffDrainMah = t.screenOffDrainMah,
            screenOnTimeMs = t.screenOnTimeMs,
            screenOffTimeMs = t.screenOffTimeMs,
            deepSleepTimeMs = t.deepSleepTimeMs,
            awakeTimeMs = t.awakeTimeMs,
            screenOnDrainRate = drainRateOver(t.screenOnDrainMah, t.screenOnTimeMs),
            screenOffDrainRate = drainRateOver(t.screenOffDrainMah, t.screenOffTimeMs),
            sessionElapsedMs = ledger.sessionElapsedMs,
            sessionStartTime = sessionStartTime,
            lastUpdateTime = reading.wallTimeMs
        )
    }

    private suspend fun refreshCapacity(generation: Long) {
        // Most devices expose enough gauge data to derive capacity. In that common case,
        // avoid even collecting the settings flow on every sample.
        synchronized(ledgerLock) {
            if (!running.get() || generation != sessionGeneration || knownCapacityMah > 0.0) return
        }
        if (!privilegedAccessAvailable) return
        val intervalMs = settingsRepository.flow.first().detailedStatsIntervalMs
        val now = SystemClock.elapsedRealtime()
        synchronized(ledgerLock) {
            if (!running.get() || generation != sessionGeneration) return
            // Capacity changes slowly. Do not keep running a full battery dump merely
            // to rediscover it, particularly when per-app collection is switched off.
            if (knownCapacityMah > 0.0) return
            if (lastCapacityReadElapsed?.let { now - it < intervalMs } == true) return
            lastCapacityReadElapsed = now
        }
        val result = checkinSource.get(maxAgeMs = intervalMs / 2)
        currentCoroutineContext().ensureActive()
        if (result is ShellRunner.Outcome.Success) {
            val reported = BatteryStatsParser.parseCheckin(result.output).estimatedCapacityMah.toDouble()
            if (BatteryCapacity.isPlausible(reported)) synchronized(ledgerLock) {
                if (running.get() && generation == sessionGeneration) {
                    knownCapacityMah = reported
                    BatteryCapacity.remember(reported)
                    _drainState.update { it.copy(capacityMah = reported) }
                }
            }
        }
    }
}

internal const val DRAIN_SNAPSHOT_INTERVAL_MS = 60_000L
internal const val MAX_DRAIN_SNAPSHOTS = 1_000

/** Keep state boundaries and level changes, but coalesce identical high-rate current polls. */
internal fun shouldRecordDrainSnapshot(
    previous: DrainSnapshot?,
    previousElapsedMs: Long?,
    current: DrainSnapshot,
    currentElapsedMs: Long
): Boolean {
    if (previous == null || previousElapsedMs == null || currentElapsedMs < previousElapsedMs) return true
    return currentElapsedMs - previousElapsedMs >= DRAIN_SNAPSHOT_INTERVAL_MS ||
        current.batteryLevel != previous.batteryLevel ||
        current.isScreenOn != previous.isScreenOn ||
        current.isCharging != previous.isCharging ||
        current.isDozing != previous.isDozing
}

/**
 * These five actions are protected broadcasts: Android rejects sends by ordinary apps.
 * Keep app-defined actions in separate, non-exported receivers. Battery changes are
 * consumed once by BatteryRepository instead of being parsed again in this tracker.
 */
internal fun registerDrainSystemReceiver(context: Context, receiver: BroadcastReceiver): Intent? {
    val filter = IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
    }
    return ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
}
