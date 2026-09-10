package app.batstats.battery.alerts

import android.os.BatteryManager
import app.batstats.battery.data.BatteryRepository
import app.batstats.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the live battery reading and fires the alerts configured in Settings.
 *
 * Every one of those settings - Low Battery Alert, High Battery Alert, Temperature Warning,
 * High Discharge Alert, Charging Complete, and the sound and vibration switches - was shown
 * in Settings and read by nothing at all. The notification builders they were meant to drive
 * existed too, with no callers. This connects the two.
 */
class BatteryAlertMonitor(
    private val repository: BatteryRepository,
    private val settings: SettingsRepository<AppSettings>,
    private val notifier: AlertNotifier,
    private val scope: CoroutineScope
) {
    private val running = AtomicBoolean(false)
    private var job: Job? = null

    /** Which conditions currently hold, so only newly held ones notify. */
    private var active: Set<BatteryAlert> = emptySet()

    fun isRunning(): Boolean = running.get()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            combine(
                repository.realtimeFlow,
                settings.flow.map { it.toThresholds() }.distinctUntilChanged(),
                settings.flow.map { it.alertSoundEnabled to it.alertVibrationEnabled }
                    .distinctUntilChanged()
            ) { realtime, thresholds, (sound, vibrate) ->
                Triple(realtime, thresholds, sound to vibrate)
            }.collect { (realtime, thresholds, alertStyle) ->
                val sample = realtime.sample ?: return@collect
                val reading = AlertReading(
                    levelPercent = realtime.level,
                    temperatureC = realtime.temperatureC,
                    currentMa = realtime.currentMa,
                    plugged = realtime.plugged != 0,
                    statusFull = sample.status == BatteryManager.BATTERY_STATUS_FULL
                )

                val current = AlertEvaluator.evaluate(reading, thresholds, active)
                val (sound, vibrate) = alertStyle
                AlertEvaluator.rising(active, current).forEach { alert ->
                    notifier.notify(alert, reading, sound, vibrate)
                }
                active = current
            }
        }
    }

    fun stop() {
        running.set(false)
        job?.cancel()
        job = null
        // Nothing holds while we are not watching, so whatever is true when monitoring
        // resumes counts as new and notifies once.
        active = emptySet()
    }
}

private fun AppSettings.toThresholds() = AlertThresholds(
    lowEnabled = lowBatteryAlertEnabled,
    lowThreshold = lowBatteryThreshold,
    highEnabled = highBatteryAlertEnabled,
    highThreshold = highBatteryThreshold,
    temperatureEnabled = temperatureWarningEnabled,
    temperatureThresholdC = temperatureThreshold,
    dischargeEnabled = dischargeAlertEnabled,
    dischargeThresholdMa = dischargeCurrentThreshold,
    chargingCompleteEnabled = chargingCompleteAlert
)
