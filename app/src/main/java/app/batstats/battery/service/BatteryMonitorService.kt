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
import app.batstats.R
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

        // Monitoring is exactly when the database grows, so it is also when it gets pruned.
        serviceScope.launch {
            while (isActive) {
                dataRetentionManager.cleanupIfDue()
                delay(DataRetentionManager.CLEANUP_INTERVAL_MS)
