package app.batstats.battery.drain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.batstats.battery.service.BatteryMonitorService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receiver for notification actions (reset, etc.)
 */
class DrainNotificationReceiver : BroadcastReceiver(), KoinComponent {
    companion object {
        const val ACTION_PAUSE = "app.batstats.battery.drain.ACTION_PAUSE"
        const val ACTION_RESET = "app.batstats.battery.drain.ACTION_RESET"
    }

    private val drainTracker: AdvancedDrainTracker by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_PAUSE) {
            // Explicit, immutable PendingIntent; the non-exported receiver only stops this
            // service. Pausing keeps the session available for review in the app.
            context.stopService(Intent(context, BatteryMonitorService::class.java))
            return
        }
        if (intent.action != ACTION_RESET) return

        // The process becomes killable the moment onReceive returns, so work launched into
        // a free-floating scope could be torn down before it ran - tapping Reset would
        // simply do nothing. goAsync keeps the receiver alive until the reset has happened.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                drainTracker.resetSession()
            } finally {
                pending.finish()
            }
        }
    }
}
