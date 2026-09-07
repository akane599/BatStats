package app.batstats.battery.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import app.batstats.battery.BatteryGraph
import app.batstats.battery.drain.AdvancedDrainTracker
import app.batstats.battery.drain.DrainNotificationManager
import android.app.Notification
import android.app.NotificationManager
import android.util.Log
import app.batstats.battery.shizuku.BstatsCollector
import app.batstats.battery.util.Notifier
import app.batstats.battery.util.ShellRunner
import app.batstats.battery.widget.WidgetUpdater
import app.batstats.insights.ForegroundDrainTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
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

    private var useAdvancedNotification = false

    /** onStartCommand can fire repeatedly (START_STICKY restarts, re-issued intents). */
    private val started = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // An existing foreground service already satisfies the startup deadline. Reposting
        // the placeholder here would replace its live notification with "Starting…".
        if (!started.compareAndSet(false, true)) return START_STICKY

        // Go foreground straight away. The system only allows ~5 s between
        // startForegroundService() and startForeground(), and probing for
        // root/Shizuku/ADB below can easily take longer than that.
        if (!goForeground(Notifier.NOTIF_ID, Notifier.monitoringNotification(this, "Starting…"))) {
            started.set(false)
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            val settings = BatteryGraph.settings.flow.first()
            useAdvancedNotification = settings.showDrainNotification

            // Start sampling
            BatteryGraph.repo.startSampling()

            val hasAdvanced = shellRunner.hasAnyPrivilegedAccess()

            // Auto-detect drain mode strategy
            if (hasAdvanced) {
                drainTracker.stop()
                advancedDrainTracker.start()

                if (useAdvancedNotification) {
                    // Swap the placeholder for the richer drain notification.
                    if (goForeground(
                            DrainNotificationManager.NOTIFICATION_ID,
                            drainNotificationManager.getNotification()
                        )
                    ) {
                        runCatching {
                            getSystemService(NotificationManager::class.java)
                                ?.cancel(Notifier.NOTIF_ID)
                        }
                    }
                    drainNotificationManager.startNotification()
                }

                if (!enhancedCollector.isRunning()) {
                    enhancedCollector.start()
                }
            } else {
                // Fall back to heuristic drain tracker
                enhancedCollector.stop()
                advancedDrainTracker.stop()
                drainNotificationManager.stopNotification()
                if (!drainTracker.isRunning()) {
                    drainTracker.start()
                }
            }

            // Update notification and widgets
            BatteryGraph.repo.realtimeFlow.collect { rt ->
                // Always update widgets
                rt.sample?.let { WidgetUpdater.push(this@BatteryMonitorService, it) }

                // Update standard notification if not using advanced
                if (!useAdvancedNotification || !hasAdvanced) {
                    val text = if (rt.sample == null)
                        "Waiting for battery data…"
                    else
                        "Level ${rt.level}% • ${rt.currentMa} mA • ${rt.voltageMv} mV"

                    val running = Notifier.monitoringNotification(this@BatteryMonitorService, text)
                    try {
                        val nm = getSystemService(android.app.NotificationManager::class.java)
                        nm.notify(Notifier.NOTIF_ID, running)
                    } catch (_: Throwable) { }
                }
            }
        }

        return START_STICKY
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
        BatteryGraph.repo.stopSampling()
        drainTracker.stop()
        advancedDrainTracker.stop()
        enhancedCollector.stop()
        drainNotificationManager.stopNotification()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
