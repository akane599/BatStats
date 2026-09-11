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
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.util.BatteryCapacity
import app.batstats.battery.util.batteryLevel
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
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "AdvancedDrainTracker"
        private const val POLL_INTERVAL_MS = 60_000L
    }

    private val running = AtomicBoolean(false)
    private var trackingJob: Job? = null
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val _drainState = MutableStateFlow(DrainState())
    val drainState: StateFlow<DrainState> = _drainState.asStateFlow()
    private val _snapshots = MutableStateFlow<List<DrainSnapshot>>(emptyList())
    val snapshots: StateFlow<List<DrainSnapshot>> = _snapshots.asStateFlow()
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

    private val stateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Only local reads under the lock; no shell/process work on this boundary.
            synchronized(ledgerLock) {
                if (!running.get()) return
                when (intent.action) {
                    Intent.ACTION_POWER_CONNECTED -> pendingPowerState = true
                    Intent.ACTION_POWER_DISCONNECTED -> pendingPowerState = false
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val state = _drainState.value
                        val level = batteryLevel(intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                            intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)) ?: -1
                        val powered = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                        val charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, 1) == BatteryManager.BATTERY_STATUS_CHARGING
                        // Temperature-only broadcasts need not poll the gauge or redraw
                        // drain history. Power/level changes update the title promptly.
                        if (state.hasBatteryReading && pendingPowerState == null &&
                            state.batteryLevel == level && state.isPowered == powered && state.isCharging == charging) return
                    }
                }
                recordReading(
                    screenOverride = when (intent.action) {
                        Intent.ACTION_SCREEN_ON -> true
                        Intent.ACTION_SCREEN_OFF -> false
                        else -> null
                    },
                    batteryIntent = intent.takeIf { it.action == Intent.ACTION_BATTERY_CHANGED }
                )
            }
        }
    }

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        _isTracking.value = true
        synchronized(ledgerLock) { pendingPowerState = null }
        resetSession()
        registerReceivers()
        trackingJob = scope.launch {
            while (isActive && running.get()) {
                try {
                    val generation = synchronized(ledgerLock) {
                        if (!running.get()) return@launch
                        recordReading(snapshot = true)
                        sessionGeneration
                    }
                    // Settings are refreshed independently of the capacity-query cadence.
                    val interval = settingsRepository.flow.first().detailedStatsIntervalMs
                    refreshCapacity(generation, interval)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tracking loop", e)
                }
                delay(POLL_INTERVAL_MS)
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
            _snapshots.value = emptyList()
            publish(reading)
        }
    }

    private fun registerReceivers() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
        }
        ContextCompat.registerReceiver(context, stateChangeReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
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

    private fun readBattery(screenOverride: Boolean? = null, batteryIntent: Intent? = null): Reading {
        val sample = batteryIntent?.let { BatteryReader.sampleFrom(context, it) } ?: BatteryReader.currentSample(context)
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
    private fun recordReading(snapshot: Boolean = false, screenOverride: Boolean? = null, batteryIntent: Intent? = null) {
        val reading = readBattery(screenOverride, batteryIntent)
        ledger.advance(reading.ledgerReading)
        publish(reading)
        if (snapshot) {
            val state = _drainState.value
            _snapshots.update { previous ->
                (previous + DrainSnapshot(
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
                )).takeLast(1000)
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

    private suspend fun refreshCapacity(generation: Long, intervalMs: Long) {
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
