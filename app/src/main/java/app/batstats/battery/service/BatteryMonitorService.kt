package app.batstats.battery.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import app.batstats.R
import app.batstats.battery.BatteryGraph
import app.batstats.battery.alerts.BatteryAlertMonitor
import app.batstats.battery.data.DataRetentionManager
import app.batstats.battery.drain.AdvancedDrainTracker
import app.batstats.battery.drain.DrainNotificationManager
import app.batstats.battery.shizuku.BstatsCollector
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.battery.util.MonitorNotificationText
import app.batstats.battery.util.Notifier
import app.batstats.battery.util.ShellRunner
import app.batstats.battery.widget.WidgetUpdater
import app.batstats.insights.ForegroundDrainTracker
import app.batstats.settings.notificationStyle
import app.batstats.settings.useFahrenheit
import app.batstats.settings.NotificationStyle
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

    /** onStartCommand can fire repeatedly (START_STICKY restarts, re-issued intents). */
    private val started = AtomicBoolean(false)

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started.compareAndSet(false, true)) return START_STICKY
        // Enter foreground before any potentially slow shell or storage work.
        if (!goForeground(Notifier.monitoringNotification(this, getString(R.string.waiting_for_battery)))) {
            started.set(false)
            stopSelf()
            return START_NOT_STICKY
        }
        getSystemService(NotificationManager::class.java)?.cancel(1000) // Boot start prompt.

        serviceScope.launch {
            while (isActive) {
                // Application startup already performs the due-now cleanup. Waiting here
                // avoids racing a duplicate settings read and database purge at service start.
                delay(DataRetentionManager.CLEANUP_INTERVAL_MS)
                dataRetentionManager.cleanupIfDue()
            }
        }

        BatteryGraph.repo.startSampling()
        // Screen-state time and fuel-gauge accounting work without privileged access.
        advancedDrainTracker.start()
        alertMonitor.start()

        // Access changes choose the per-app data source. They must not choose which
        // notification is visible: basic drain tracking works without root/Shizuku/ADB.
        serviceScope.launch {
            combine(
                shizukuBridge.granted,
                shizukuBridge.running,
                BatteryGraph.settings.flow.map { it.trackAppDrain }.distinctUntilChanged()
            ) { _, _, trackAppDrain -> trackAppDrain }.collect { trackAppDrain ->
                shellRunner.invalidateMode()
                applyStrategy(trackAppDrain, shellRunner.hasAnyPrivilegedAccess())
            }
        }

        serviceScope.launch {
            combine(
                BatteryGraph.repo.realtimeFlow.map { it.sample }.distinctUntilChanged(),
                BatteryGraph.settings.flow.map { it.useFahrenheit }.distinctUntilChanged()
            ) { sample, fahrenheit -> sample to fahrenheit }.collect { (sample, fahrenheit) ->
                sample?.let { WidgetUpdater.push(this@BatteryMonitorService, it, fahrenheit) }
            }
        }

        // A single owner and ID prevents old plain/drain notifications reappearing after
        // a setting changes. Serializing on Main also orders updates before onDestroy.
        serviceScope.launch(Dispatchers.Main.immediate) {
            val notificationSettings = BatteryGraph.settings.flow.map {
                NotificationPreferences(
                    showDrain = it.showDrainNotification,
                    visible = it.showNotification,
                    style = it.notificationStyle,
                    useFahrenheit = it.useFahrenheit
                )
            }.distinctUntilChanged()
            combine(
                BatteryGraph.repo.realtimeFlow.map { it.sample }.distinctUntilChanged(),
                advancedDrainTracker.drainState,
                notificationSettings
            ) { sample, state, settings ->
                NotificationFrame(
                    sample = sample,
                    drainState = state.takeIf { settings.showDrain && settings.visible },
                    settings = settings
                )
            }.distinctUntilChanged().collect { frame ->
                val notification = if (frame.drainState != null) {
                    drainNotificationManager.getNotification(frame.drainState, frame.settings.style)
                } else {
                    val content = MonitorNotificationText.from(
                        this@BatteryMonitorService, frame.sample, frame.settings.style, frame.settings.useFahrenheit
                    )
                    Notifier.monitoringNotification(
                        ctx = this@BatteryMonitorService,
                        text = content.text,
                        visible = frame.settings.visible,
                        details = content.details,
                        title = content.title
                    )
                }
                val fingerprint = NotificationFingerprint.from(notification)
                if (fingerprint == lastNotificationFingerprint) return@collect
                try {
                    getSystemService(NotificationManager::class.java)?.notify(Notifier.NOTIF_ID, notification)
                } catch (e: SecurityException) {
                    Log.w(TAG, "Monitoring notification permission is unavailable", e)
                } finally {
                    // Avoid repeating an identical binder update and error on every current
                    // sample. A content, style, state or channel change creates a new key.
                    lastNotificationFingerprint = fingerprint
                }
            }
        }
        return START_STICKY
    }

    private fun applyStrategy(trackAppDrain: Boolean, hasAdvanced: Boolean) {
        advancedDrainTracker.setPrivilegedAccessAvailable(hasAdvanced)
        if (hasAdvanced) {
            drainTracker.stop()
        } else {
            if (trackAppDrain && !drainTracker.isRunning()) drainTracker.start()
            if (!trackAppDrain) drainTracker.stop()
        }
        if (trackAppDrain && hasAdvanced) {
            if (!enhancedCollector.isRunning()) enhancedCollector.start()
        } else {
            enhancedCollector.stop()
        }
    }

    private fun goForeground(notification: Notification): Boolean = try {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(Notifier.NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(Notifier.NOTIF_ID, notification)
        }
        true
    } catch (t: Throwable) {
        Log.e(TAG, "startForeground failed", t)
        false
    }

    override fun onDestroy() {
        started.set(false)
        // Cancel rendering before trackers emit their final states so a stopped service
        // cannot post a new ongoing notification from a late coroutine.
        serviceScope.cancel()
        alertMonitor.stop()
        BatteryGraph.repo.stopSampling()
        drainTracker.stop()
        advancedDrainTracker.stop()
        enhancedCollector.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        getSystemService(NotificationManager::class.java)?.cancel(Notifier.NOTIF_ID)
        WidgetUpdater.requestRefresh(this)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class NotificationPreferences(
        val showDrain: Boolean,
        val visible: Boolean,
        val style: NotificationStyle,
        val useFahrenheit: Boolean
    )

    private data class NotificationFrame(
        val sample: app.batstats.battery.data.db.BatterySample?,
        val drainState: app.batstats.battery.drain.DrainState?,
        val settings: NotificationPreferences
    )

    private var lastNotificationFingerprint: NotificationFingerprint? = null

    private data class NotificationFingerprint(
        val channelId: String?,
        val title: String,
        val text: String,
        val details: String?,
        val actions: List<String>
    ) {
        companion object {
            fun from(notification: Notification) = NotificationFingerprint(
                channelId = notification.channelId,
                title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
                text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty(),
                details = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
                actions = notification.actions.orEmpty().map { it.title.toString() }
            )
        }
    }
}
