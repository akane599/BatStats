package app.batstats.battery.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import app.batstats.battery.BatteryGraph
import app.batstats.settings.useFahrenheit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Shared behaviour for the three widgets.
 *
 * Whatever wakes them - the system's periodic update, a manual refresh, being placed on the
 * home screen - they render the battery's own current state. They used to read the
 * monitoring service's in-memory snapshot instead, which is empty whenever that service is
 * not running, so a widget would sit on a stale number until monitoring was started again.
 */
abstract class BatteryWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        refresh(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetUpdater.ACTION_REFRESH) refresh(context)
    }

    private fun refresh(context: Context) {
        // Reading the temperature unit is asynchronous, and the process is killable as soon
        // as onReceive returns.
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val fahrenheit = runCatching {
                    BatteryGraph.settings.flow.first().useFahrenheit
                }.getOrDefault(false)
                WidgetUpdater.refreshFromSystem(appContext, fahrenheit)
            } finally {
                pending.finish()
            }
        }
    }
}
