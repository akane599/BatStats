package app.batstats.battery.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.batstats.battery.BatteryGraph
import app.batstats.battery.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Notifier.ensureChannel(context)
        val repository = BatteryGraph.settings

        // Reading the setting is asynchronous, and the process is killable as soon as
        // onReceive returns - so auto-start could lose the race against its own teardown.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val config = repository.flow.first()
                if (!config.autoStartOnBoot) return@launch

                if (Build.VERSION.SDK_INT >= 35) {
                    Notifier.promptStartOnBoot(context)
                } else {
                    context.startForegroundService(Intent(context, BatteryMonitorService::class.java))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
