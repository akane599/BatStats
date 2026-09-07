package app.batstats.battery.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.batstats.battery.BatteryGraph
import app.batstats.battery.util.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pending = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            var autoStartEnabled = false
            try {
                // Keep the receiver alive while DataStore loads, within the broadcast budget.
                val config = withTimeoutOrNull(5_000L) { BatteryGraph.settings.flow.first() }
                    ?: return@launch
                if (!config.autoStartOnBoot) return@launch
                autoStartEnabled = true
                appContext.startForegroundService(Intent(appContext, BatteryMonitorService::class.java))
            } catch (e: Exception) {
                Log.w("BootReceiver", "Could not start battery monitoring after boot", e)
                // OEM background-start restrictions may still require a user tap.
                if (autoStartEnabled) runCatching { Notifier.promptStartOnBoot(appContext) }
            } finally {
                pending.finish()
            }
        }
    }
}
