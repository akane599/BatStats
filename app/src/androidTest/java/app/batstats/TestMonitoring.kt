package app.batstats

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import androidx.test.platform.app.InstrumentationRegistry
import app.batstats.battery.BatteryGraph
import app.batstats.battery.service.BatteryMonitorService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.rules.ExternalResource

internal object TestMonitoring {
    /** This disposable test installation must be isolated before ActivityScenario launches. */
    fun withoutBootAutoStart() = object : ExternalResource() {
        override fun before() {
            runBlocking { BatteryGraph.settings.update { it.copy(autoStartOnBoot = false) } }
        }
    }

    /**
     * An install-time boot broadcast may already have queued startForegroundService.
     * Cancelling that service before startForeground fulfils its obligation crashes the
     * process several seconds later. Observe the real service state before fixture cleanup.
     */
    @Suppress("DEPRECATION") // Since Android 8 this API still reports the calling app's services.
    fun pauseWhenForegroundReady() {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Service cleanup must not block the app main thread" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(ActivityManager::class.java)
        val component = ComponentName(context, BatteryMonitorService::class.java)
        var lastService: ActivityManager.RunningServiceInfo? = null
        var stopRequested = false
        try {
            runBlocking {
                withTimeout(15_000L) {
                    while (true) {
                        lastService = manager.getRunningServices(Int.MAX_VALUE).firstOrNull { it.service == component }
                        val running = lastService
                        if (running == null && !BatteryGraph.repo.isMonitoringFlow.value) return@withTimeout
                        if (running?.foreground == true && !stopRequested) {
                            context.stopService(Intent().setComponent(component))
                            stopRequested = true
                        }
                        // Poll for foreground promotion or destruction; elapsed time alone
                        // never grants permission to cancel a pending foreground start.
                        delay(25L)
                    }
                }
            }
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Monitor did not reach a safe paused state: present=${lastService != null}, " +
                "foreground=${lastService?.foreground}, stopRequested=$stopRequested, " +
                "sampling=${BatteryGraph.repo.isMonitoringFlow.value}", timeout)
        }
    }
}
