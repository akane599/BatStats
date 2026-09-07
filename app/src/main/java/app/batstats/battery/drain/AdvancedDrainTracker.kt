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
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.BatteryCapacity
import app.batstats.battery.util.BatteryStatsParser
import app.batstats.battery.util.ShellRunner
import app.batstats.settings.AppSettings
import app.batstats.settings.detailedStatsIntervalMs
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * Tracks battery drain across device states.
 *
 * Time and charge are accounted for in *segments*. A segment runs from one state change to
 * the next - the screen turning on or off, the charger going in or out - and is also closed
 * on every poll so its charge delta gets measured. When a segment closes, its whole elapsed
 * time and its whole charge delta are credited to the buckets that were actually in effect
 * for it.
 *
 * The earlier version sampled instead: every poll credited the entire interval to whichever
 * state the closing sample happened to observe. A minute in which the screen was off for two
 * seconds was booked as a minute of screen-off, which is how the UI came to report more deep
 * sleep than screen-off time. Segments make the buckets add up by construction:
 * screen-on + screen-off is the tracked time, active + idle is the screen-on time, and
 * deep sleep + awake is the screen-off time.
 */
class AdvancedDrainTracker(
    private val context: Context,
    private val shizukuBridge: ShizukuBridge,
    private val shellRunner: ShellRunner,
    private val settingsRepository: SettingsRepository<AppSettings>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    companion object {
        private const val TAG = "AdvancedDrainTracker"
        private const val POLL_INTERVAL_MS = 60_000L
        private const val SETTINGS_REFRESH_INTERVAL_MS = 60_000L

        /** Screen-on draw above this counts as active use rather than idling. */
        private const val ACTIVE_CURRENT_MA = 200

        /** A screen-off segment asleep for more than this fraction counts as deep sleep. */
        private const val DEEP_SLEEP_RATIO = 0.5
    }

    private val running = AtomicBoolean(false)
    private var trackingJob: Job? = null

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager

    private val _drainState = MutableStateFlow(DrainState())
    val drainState: StateFlow<DrainState> = _drainState.asStateFlow()

    private val _snapshots = MutableStateFlow<List<DrainSnapshot>>(emptyList())
    val snapshots: StateFlow<List<DrainSnapshot>> = _snapshots.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private var receiverRegistered = false

    /** Carries a placeholder so the mAh fallback has something to work with. */
    private var estimatedCapacityMah: Double = 4000.0

    /**
     * Only ever set from a real source (batterystats, or the charge counter). Unlike
     * [estimatedCapacityMah], 0 here means "unknown" and percentages are left off.
     */
    private var knownCapacityMah: Double = 0.0

    private var detailedStatsIntervalMs: Long = 300_000L
    private var lastDumpsysTime: Long = 0L
    private var cachedAwakeTime: Long = 0L
    private var cachedDeepSleepTime: Long = 0L

    private val ledgerLock = Any()
    private var totals = DrainTotals()
    private var openSegment: Segment? = null

    /** Share of the last closed screen-off segment the CPU spent suspended. */
    @Volatile
    private var lastSleepRatio: Double = 0.0

    private var sessionStartTime: Long = System.currentTimeMillis()

    private val stateChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON,
                Intent.ACTION_SCREEN_OFF,
                Intent.ACTION_POWER_CONNECTED,
                Intent.ACTION_POWER_DISCONNECTED -> {
                    // Timestamp the boundary here so it is exact, but take the readings off
                    // the main thread - this fires on every screen unlock.
                    val at = SystemClock.elapsedRealtime()
                    scope.launch {
                        advanceLedger(at)
                        updateDrainState()
                    }
                }
            }
        }
    }

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return

        Log.i(TAG, "Starting advanced drain tracking")
        _isTracking.value = true
        resetSession()
        registerReceivers()

        trackingJob = scope.launch {
            while (isActive && running.get()) {
                try {
                    if (System.currentTimeMillis() - lastDumpsysTime >= SETTINGS_REFRESH_INTERVAL_MS) {
                        val settings = settingsRepository.flow.first()
                        detailedStatsIntervalMs = settings.detailedStatsIntervalMs
                    }

                    // Close the running segment so its charge delta is measured and booked,
                    // then start the next one from here.
                    advanceLedger()
                    takeSnapshot()?.let { snapshot ->
                        _snapshots.update { (it + snapshot).takeLast(1000) }
                    }
                    updateDrainState()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in tracking loop", e)
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return

        Log.i(TAG, "Stopping advanced drain tracking")
        _isTracking.value = false
        trackingJob?.cancel()
        trackingJob = null
        unregisterReceivers()

        // Book whatever the final segment earned, then stop accruing.
        synchronized(ledgerLock) {
            closeSegment(SystemClock.elapsedRealtime())
            openSegment = null
        }
        updateDrainState()
    }

    fun resetSession() {
        synchronized(ledgerLock) {
            totals = DrainTotals()
            openSegment = null
            sessionStartTime = System.currentTimeMillis()
        }
        lastSleepRatio = 0.0
        _snapshots.value = emptyList()
        _drainState.value = DrainState(sessionStartTime = sessionStartTime)
        advanceLedger()
        Log.i(TAG, "Session reset")
    }

    private fun registerReceivers() {
        if (receiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                // Charging refills the battery, so a segment must not straddle a plug event.
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            }
            // Explicit export flag: required from API 34 for anything but protected broadcasts.
            ContextCompat.registerReceiver(
                context,
                stateChangeReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register receivers", e)
        }
    }

    private fun unregisterReceivers() {
        if (!receiverRegistered) return
        receiverRegistered = false
        try {
            context.unregisterReceiver(stateChangeReceiver)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister receivers", e)
        }
    }

    // ------------------------------------------------------------------ interval ledger

    /** One stretch of time during which the device stayed in a single state. */
    private class Segment(
        val startedElapsedMs: Long,
        val startChargeMah: Double,
        /** Cumulative time the CPU had been suspended when this segment opened. */
        val startSleptMs: Long,
        val screenOn: Boolean,
        val charging: Boolean,
        val active: Boolean
    )

    /** Total time the CPU has been suspended since boot. */
    private fun sleptSinceBootMs(): Long =
        SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    /**
     * Closes the running segment and opens a fresh one from this instant. Called on every
     * state change and on every poll, so no segment ever spans a change of state.
     */
    private fun advanceLedger(now: Long = SystemClock.elapsedRealtime()) {
        synchronized(ledgerLock) {
            closeSegment(now)
            openSegment = Segment(
                startedElapsedMs = now,
                startChargeMah = getCurrentBatteryMah(),
                startSleptMs = sleptSinceBootMs(),
                screenOn = powerManager.isInteractive,
                charging = isCharging(),
                active = powerManager.isInteractive && abs(getCurrentNowMa()) > ACTIVE_CURRENT_MA
            )
        }
    }

    /** Must hold [ledgerLock]. */
    private fun closeSegment(now: Long) {
        val segment = openSegment ?: return
        val elapsed = now - segment.startedElapsedMs
        if (elapsed <= 0L) return

        // A segment that touched the charger tells us nothing about drain: the battery was
        // being refilled, and crediting that would poison every rate derived from it.
        if (segment.charging || isCharging()) return

        val slept = (sleptSinceBootMs() - segment.startSleptMs).coerceIn(0L, elapsed)
        if (!segment.screenOn) {
            lastSleepRatio = slept.toDouble() / elapsed
        }

        val drain = max(0.0, segment.startChargeMah - getCurrentBatteryMah())
        totals.credit(segment.screenOn, segment.active, elapsed, slept, drain)
    }

    /** The committed totals plus whatever the still-open segment has earned so far. */
    private fun totalsIncludingOpenSegment(now: Long): DrainTotals = synchronized(ledgerLock) {
        val snapshot = totals.copy()
        val segment = openSegment ?: return snapshot
        val elapsed = now - segment.startedElapsedMs
        if (elapsed <= 0L || segment.charging || isCharging()) return snapshot

        val slept = (sleptSinceBootMs() - segment.startSleptMs).coerceIn(0L, elapsed)
        val drain = max(0.0, segment.startChargeMah - getCurrentBatteryMah())
        snapshot.credit(segment.screenOn, segment.active, elapsed, slept, drain)
        snapshot
    }

    // ---------------------------------------------------------------------- state output

    private suspend fun takeSnapshot(): DrainSnapshot? {
        return try {
            val isScreenOn = powerManager.isInteractive
            val (cpuAwakeTime, deepSleepTime) = getDeepSleepInfo()

            DrainSnapshot(
                timestamp = System.currentTimeMillis(),
                batteryLevel = getBatteryLevel(),
                batteryMah = getCurrentBatteryMah(),
                currentMa = getCurrentNowMa(),
                isScreenOn = isScreenOn,
                isCharging = isCharging(),
                isDeepSleep = isInDeepSleep(),
                isDozing = powerManager.isDeviceIdleMode,
                cpuAwakeTimeMs = cpuAwakeTime,
                deepSleepTimeMs = deepSleepTime
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to take snapshot", e)
            null
        }
    }

    private fun updateDrainState() {
        val now = System.currentTimeMillis()

        // batterystats is the preferred capacity source, but it needs a privileged dump;
        // fall back to measuring so percentages still work without one.
        if (knownCapacityMah <= 0.0) {
            BatteryCapacity.measuredMah(context)?.let { knownCapacityMah = it }
        }

        // Duration accounting must not jump when the wall clock is corrected.
        val t = totalsIncludingOpenSegment(SystemClock.elapsedRealtime())

        _drainState.value = DrainState(
            timestamp = now,
            batteryLevel = getBatteryLevel(),
            batteryLevelMah = getCurrentBatteryMah(),
            capacityMah = knownCapacityMah,
            isScreenOn = powerManager.isInteractive,
            isCharging = isCharging(),
            isDeepSleep = isInDeepSleep(),
            isDozing = powerManager.isDeviceIdleMode,

            screenOnDrainMah = t.screenOnDrainMah,
            screenOffDrainMah = t.screenOffDrainMah,
            activeDrainMah = t.activeDrainMah,
            idleDrainMah = t.idleDrainMah,
            deepSleepDrainMah = t.deepSleepDrainMah,
            awakeDrainMah = t.awakeDrainMah,

            screenOnTimeMs = t.screenOnTimeMs,
            screenOffTimeMs = t.screenOffTimeMs,
            activeTimeMs = t.activeTimeMs,
            idleTimeMs = t.idleTimeMs,
            deepSleepTimeMs = t.deepSleepTimeMs,
            awakeTimeMs = t.awakeTimeMs,

            screenOnDrainRate = drainRateOver(t.screenOnDrainMah, t.screenOnTimeMs),
            screenOffDrainRate = drainRateOver(t.screenOffDrainMah, t.screenOffTimeMs),
            activeDrainRate = drainRateOver(t.activeDrainMah, t.activeTimeMs),
            idleDrainRate = drainRateOver(t.idleDrainMah, t.idleTimeMs),
            deepSleepDrainRate = drainRateOver(t.deepSleepDrainMah, t.deepSleepTimeMs),
            awakeDrainRate = drainRateOver(t.awakeDrainMah, t.awakeTimeMs),

            sessionStartTime = sessionStartTime,
            lastUpdateTime = now
        )
    }

    // -------------------------------------------------------------------- device readings

    private fun getBatteryLevel(): Int {
        // Devices that do not implement the property return Integer.MIN_VALUE.
        val level = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (level in 0..100) return level

        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val raw = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (raw >= 0 && scale > 0) (raw * 100 / scale) else 0
    }

    private fun getCurrentBatteryMah(): Double {
        val chargeCounter = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        return if (chargeCounter > 0) {
            chargeCounter / 1000.0
        } else {
            (getBatteryLevel() / 100.0) * estimatedCapacityMah
        }
    }

    private fun getCurrentNowMa(): Int {
        var current = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        if (current == 0L || current == Long.MIN_VALUE) {
            current = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        }
        return (current / 1000).toInt()
    }

    private fun isCharging(): Boolean {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    /**
     * Whether the device is currently suspending rather than merely screen-off.
     *
     * This used to compare (elapsedRealtime - uptime) against a threshold, but that
     * difference is the *cumulative* sleep since boot: once a device had ever slept 30
     * seconds it read as being in deep sleep for every screen-off moment thereafter. The
     * answer comes from how much of the last screen-off segment was actually suspended.
     */
    private fun isInDeepSleep(): Boolean =
        !powerManager.isInteractive && lastSleepRatio > DEEP_SLEEP_RATIO

    private suspend fun getDeepSleepInfo(): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        if (now - lastDumpsysTime < detailedStatsIntervalMs) {
            return Pair(cachedAwakeTime, cachedDeepSleepTime)
        }

        try {
            val result = shellRunner.run("dumpsys batterystats --checkin")
            if (result != null) {
                val snapshot = BatteryStatsParser.parseCheckin(result.output)
                val awakeTime = snapshot.batteryRealtimeMs - (snapshot.doze?.deepIdleTimeMs ?: 0L)
                val sleepTime = snapshot.doze?.deepIdleTimeMs ?: 0L
                cachedAwakeTime = awakeTime
                cachedDeepSleepTime = sleepTime
                val reported = snapshot.estimatedCapacityMah.toDouble()
                if (BatteryCapacity.isPlausible(reported)) {
                    estimatedCapacityMah = reported
                    knownCapacityMah = reported
                }
                lastDumpsysTime = System.currentTimeMillis()
                return Pair(awakeTime, sleepTime)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting deep sleep info", e)
        }

        val uptime = SystemClock.uptimeMillis()
        val elapsedRealtime = SystemClock.elapsedRealtime()
        cachedAwakeTime = uptime
        cachedDeepSleepTime = elapsedRealtime - uptime
        lastDumpsysTime = System.currentTimeMillis()
        return Pair(uptime, elapsedRealtime - uptime)
    }
}
