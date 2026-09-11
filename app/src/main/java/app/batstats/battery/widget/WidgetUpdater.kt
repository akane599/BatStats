package app.batstats.battery.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import app.batstats.R
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.util.BatteryReader
import app.batstats.battery.util.TimeEstimator
import java.util.Locale

object WidgetUpdater {
    const val ACTION_REFRESH = "app.batstats.battery.widget.ACTION_REFRESH"

    private const val EM_DASH = "—"
    private data class RenderedWidget(val ids: List<Int>, val title: String, val value: String)
    private val rendered = mutableMapOf<Class<*>, RenderedWidget>()

    fun push(ctx: Context, s: BatterySample, useFahrenheit: Boolean = false, force: Boolean = false) {
        updateLevel(ctx, s, force)
        updateTemp(ctx, s, useFahrenheit, force)
        updateTime(ctx, s, force)
    }

    /**
     * Renders from the battery's own current state rather than from whatever the monitoring
     * service last handed over. Widgets used to be pushed only from that service, so with it
     * stopped they showed a frozen reading indefinitely.
     */
    fun refreshFromSystem(ctx: Context, useFahrenheit: Boolean = false) {
        val sample = BatteryReader.currentSample(ctx)
        if (sample == null) showPlaceholder(ctx) else push(ctx, sample, useFahrenheit, force = true)
    }

    fun showPlaceholder(ctx: Context) {
        setAll(ctx, BatteryLevelWidget::class.java, ctx.getString(R.string.widget_battery), EM_DASH, force = true)
        setAll(ctx, BatteryTempWidget::class.java, ctx.getString(R.string.widget_temperature), EM_DASH, force = true)
        setAll(ctx, BatteryTimeWidget::class.java, ctx.getString(R.string.widget_eta), EM_DASH, force = true)
    }

    private fun createRemoteViews(ctx: Context): RemoteViews {
        // Since we're using a common layout, create base RemoteViews
        val rv = RemoteViews(ctx.packageName, R.layout.widget_common)

        // Add click intent to open app
        val intent = Intent(ctx, BatteryMainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            ctx, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        rv.setOnClickPendingIntent(R.id.root, pendingIntent)

        return rv
    }

    private fun setAll(ctx: Context, provider: Class<*>, title: String, value: String, force: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val ids = mgr.getAppWidgetIds(ComponentName(ctx, provider))
        val key = RenderedWidget(ids.sorted(), title, value)
        synchronized(rendered) {
            if (ids.isEmpty()) {
                rendered.remove(provider)
                return
            }
            if (!force && rendered[provider] == key) return
        }
        val rv = createRemoteViews(ctx).apply {
            setTextViewText(R.id.title, title)
            setTextViewText(R.id.value, value)
        }
        ids.forEach { mgr.updateAppWidget(it, rv) }
        // Cache only a successful binder update so a transient host failure is retried.
        synchronized(rendered) { rendered[provider] = key }
    }

    private fun updateLevel(ctx: Context, s: BatterySample, force: Boolean) {
        setAll(
            ctx,
            BatteryLevelWidget::class.java,
            ctx.getString(R.string.widget_battery),
            if (s.levelPercent in 0..100) "${s.levelPercent}%" else EM_DASH,
            force
        )
    }

    private fun updateTemp(ctx: Context, s: BatterySample, useFahrenheit: Boolean, force: Boolean) {
        if (s.temperatureDeciC == null) {
            setAll(ctx, BatteryTempWidget::class.java, ctx.getString(R.string.widget_temperature), EM_DASH, force)
            return
        }
        val tempC = s.temperatureDeciC / 10.0
        val value = if (useFahrenheit) {
            String.format(Locale.getDefault(), "%.1f °F", tempC * 9 / 5 + 32)
        } else {
            String.format(Locale.getDefault(), "%.1f °C", tempC)
        }
        setAll(ctx, BatteryTempWidget::class.java, ctx.getString(R.string.widget_temperature), value, force)
    }

    private fun updateTime(ctx: Context, s: BatterySample, force: Boolean) {
        setAll(
            ctx,
            BatteryTimeWidget::class.java,
            ctx.getString(R.string.widget_eta),
            TimeEstimator.etaString(s) ?: EM_DASH,
            force
        )
    }

    fun requestRefresh(ctx: Context) {
        // Fix: use actual package name
        ctx.sendBroadcast(Intent(ACTION_REFRESH).setPackage(ctx.packageName))
    }
}
