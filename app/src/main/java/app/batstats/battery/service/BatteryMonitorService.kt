package app.batstats.battery.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.batstats.battery.BatteryGraph
import app.batstats.battery.alerts.BatteryAlertMonitor
import app.batstats.battery.drain.AdvancedDrainTracker
import app.batstats.battery.drain.DrainNotificationManager
import android.app.Notification
import android.app.NotificationManager
import android.util.Log
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.DataRetentionManager
import app.batstats.battery.drain.formatLevelRatePerHour
import app.batstats.battery.shizuku.BstatsCollector
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.Notifier
import app.batstats.battery.util.ShellRunner
import app.batstats.battery.util.TimeEstimator
import app.batstats.battery.widget.WidgetUpdater
import app.batstats.settings.NotificationStyle
import app.batstats.settings.notificationStyle
import app.batstats.settings.useFahrenheit
import app.batstats.insights.ForegroundDrainTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class BatteryMonitorService : Service() {
    companion object {
        private const val TAG = "BatteryMonitorService"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val drainTracker: ForegroundDrainTracker by inject()
    private val advancedDrainTracker: AdvancedDrainTracker by inject()
    private val drainNotificationManager: DrainNotificationManager by inject()
    private val shellRunner: ShellRunner by inject()
    private val enhancedCollector: BstatsCollector by inject()
    private val dataRetentionManager: DataRetentionManager by inject()
    private val shizukuBridge: ShizukuBridge by inject()
    private val alertMonitor: BatteryAlertMonitor by inject()

    @Volatile private var useAdvancedNotification = false

    /** Re-evaluated whenever the Shizuku binder or the settings change, not just at start. */
    @Volatile private var hasAdvanced = false

    /** Tracked live so the widgets follow a unit change without waiting for a restart. */
    @Volatile private var useFahrenheit = false

    /** Both were shown in Settings and read by nothing; now they shape the notification. */
    @Volatile private var notificationVisible = true
    @Volatile private var notificationStyle = NotificationStyle.COMPACT

    /** onStartCommand can fire repeatedly (START_STICKY restarts, re-issued intents). */
    private val started = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started.compareAndSet(false, true)) return START_STICKY
        // Go foreground straight away. The system only allows ~5 s between
        // startForegroundService() and startForeground(), and probing for
        // root/Shizuku/ADB below can easily take longer than that.
        if (!goForeground(Notifier.NOTIF_ID, Notifier.monitoringNotification(this, "Starting…"))) {
            started.set(false)
            stopSelf()
            return START_NOT_STICKY
        }

        // Monitoring is exactly when the database grows, so it is also when it gets pruned.
        serviceScope.launch {
            while (isActive) {
                dataRetentionManager.cleanupIfDue()
                delay(DataRetentionManager.CLEANUP_INTERVAL_MS)
            }
        }

        serviceScope.launch {
            BatteryGraph.settings.flow.collect {
                useFahrenheit = it.useFahrenheit
                notificationVisible = it.showNotification
                notificationStyle = it.notificationStyle
            }
        }

        BatteryGraph.repo.startSampling()
        // Screen-state accounting uses the fuel gauge on every device. Privileged access
        // adds system sleep/energy data, but is not required to keep this ledger running.
        advancedDrainTracker.start()
        alertMonitor.start()

        // Which backend is available can change *after* we start. At boot the service and
        // Shizuku race each other, and this used to be decided once and never revisited, so
        // losing that race left the app in heuristic mode until monitoring was restarted by
        // hand. The bridge's state flows emit their current value on subscribe, which also
        // gives us the initial decision.
        serviceScope.launch {
            combine(
                shizukuBridge.granted,
                shizukuBridge.running,
                BatteryGraph.settings.flow.map { it.trackAppDrain }.distinctUntilChanged(),
                BatteryGraph.settings.flow.map { it.showDrainNotification && it.showNotification }.distinctUntilChanged()
            ) { _, _, trackAppDrain, showDrainNotification ->
                trackAppDrain to showDrainNotification
            }.collect { (trackAppDrain, showDrainNotification) ->
                useAdvancedNotification = showDrainNotification
                // A grant that just landed will not be reflected in the cached backend.
                shellRunner.invalidateMode()
                hasAdvanced = shellRunner.hasAnyPrivilegedAccess()
                applyStrategy(trackAppDrain)
            }
        }

        serviceScope.launch {
            // Update notification and widgets
            combine(BatteryGraph.repo.realtimeFlow, BatteryGraph.settings.flow) { rt, settings ->
                useFahrenheit = settings.useFahrenheit
                notificationVisible = settings.showNotification
                notificationStyle = settings.notificationStyle
                rt
            }.collect { rt ->
                // Always update widgets
                rt.sample?.let {
                    WidgetUpdater.push(this@BatteryMonitorService, it, useFahrenheit)
                }

                // Update standard notification if not using advanced
                if (!useAdvancedNotification || !hasAdvanced) {
                    val running = Notifier.monitoringNotification(
                        ctx = this@BatteryMonitorService,
                        text = monitoringText(rt),
                        visible = notificationVisible,
                        details = detailedText(rt)
                    )
                    try {
                        val nm = getSystemService(android.app.NotificationManager::class.java)
                        nm.notify(Notifier.NOTIF_ID, running)
                    } catch (_: Throwable) { }
                }
            }
        }

        return START_STICKY
    }

    /**
     * Picks the drain tracker that matches the access we actually have, and starts or stops
     * per-app collection to match the setting.
     *
     * Safe to call repeatedly: each collector is guarded on its own running state, so a
     * re-evaluation that changes nothing does nothing.
     */
    private fun applyStrategy(trackAppDrain: Boolean) {
        if (hasAdvanced) {
            drainTracker.stop()
        } else {
            // The heuristic tracker only earns its keep when it is the only per-app source.
            if (trackAppDrain && !drainTracker.isRunning()) drainTracker.start()
            if (!trackAppDrain) drainTracker.stop()
        }

        if (hasAdvanced && useAdvancedNotification) {
            // Swap the placeholder for the richer drain notification.
            if (goForeground(
                    DrainNotificationManager.NOTIFICATION_ID,
                    drainNotificationManager.getNotification()
                )
            ) {
                runCatching {
                    getSystemService(NotificationManager::class.java)?.cancel(Notifier.NOTIF_ID)
                }
            }
            drainNotificationManager.startNotification()
        } else {
            // Turning the drain notification off mid-run has to hand the foreground back to
            // the plain one first: the notification a service is in the foreground with
            // cannot simply be cancelled.
            val realtime = BatteryGraph.repo.realtimeFlow.value
            goForeground(
                Notifier.NOTIF_ID,
                Notifier.monitoringNotification(
                    ctx = this,
                    text = monitoringText(realtime),
                    visible = notificationVisible,
                    details = detailedText(realtime)
                )
            )
            drainNotificationManager.stopNotification()
        }

        // Per-app collection has its own switch, so monitoring can run without the
        // five-minute batterystats dumps.
        val wantCollector = trackAppDrain && hasAdvanced
        if (wantCollector && !enhancedCollector.isRunning()) {
            enhancedCollector.start()
        } else if (!wantCollector) {
            enhancedCollector.stop()
        }
        if (!hasAdvanced) enhancedCollector.stop()
    }

    /**
     * "Level 47% • -780 mA · -15.6%/h • 3812 mV".
     *
     * Both readings earn their place: the mA is what the hardware actually reports and is
     * the number to compare against another device or another app, while the share per hour
     * is what says how long the battery has left. Where the capacity could not be
     * established the share is dropped rather than computed against a guess.
     */
    private fun monitoringText(rt: BatteryRepository.Realtime): String {
        val sample = rt.sample ?: return "Waiting for battery data…"
        if (notificationStyle == NotificationStyle.MINIMAL) return "Level ${rt.level}%"

        val capacity = TimeEstimator.capacityMahFor(sample)
        val milliAmps = if (sample.currentNowUa == null) "—" else "${rt.currentMa} mA"
        val rate = formatLevelRatePerHour(rt.currentMa, capacity)
        val draw = if (rate == null) milliAmps else "$milliAmps · $rate"
        return "Level ${rt.level}% • $draw • ${rt.voltageMv} mV"
    }

    /** The expanded body, only under the Detailed style. */
    private fun detailedText(rt: BatteryRepository.Realtime): String? {
        if (notificationStyle != NotificationStyle.DETAILED) return null
        val sample = rt.sample ?: return null
        val eta = TimeEstimator.etaString(sample)
        return buildString {
            appendLine(monitoringText(rt))
            appendLine(
                String.format(
                    Locale.getDefault(),
                    if (useFahrenheit) "Temperature %.1f °F • Power %.0f mW" else "Temperature %.1f °C • Power %.0f mW",
                    if (useFahrenheit) rt.temperatureC * 9 / 5 + 32 else rt.temperatureC,
                    rt.powerMw
                )
            )
            append(eta ?: "")
        }.trim()
    }

    private fun goForeground(id: Int, notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground failed", t)
        false
    }

    override fun onDestroy() {
        started.set(false)
        alertMonitor.stop()
        BatteryGraph.repo.stopSampling()
        drainTracker.stop()
        advancedDrainTracker.stop()
        enhancedCollector.stop()
        drainNotificationManager.stopNotification()
        serviceScope.cancel()
        // Stopping monitoring is exactly when widgets start going stale, so hand them one
        // last refresh from the system rather than leaving them on our final live push.
        WidgetUpdater.requestRefresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
