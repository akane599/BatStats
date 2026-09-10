package app.batstats.battery.util

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import androidx.core.app.NotificationCompat
import app.batstats.R
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.service.BatteryMonitorService

object Notifier {
    private const val CH_ID = "battery_monitor"

    /**
     * Where the monitoring notification goes when "Show Persistent Notification" is off.
     *
     * A foreground service must show a notification - Android will not let it be removed -
     * but a MIN-importance channel keeps it out of the status bar and folds it away in the
     * shade, which is what someone turning that switch off is actually asking for. It needs
     * a channel of its own because a channel's importance cannot be changed once created.
     */
    private const val CH_ID_QUIET = "battery_monitor_quiet"

    const val NOTIF_ID = 11

    fun ensureChannel(ctx: Context) {
        ensureChannel(ctx, visible = true)
        }

    private fun ensureChannel(ctx: Context, visible: Boolean): String {
        val id = if (visible) CH_ID else CH_ID_QUIET
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (mgr.getNotificationChannel(id) == null) {
            val ch = NotificationChannel(
                id,
                ctx.getString(
                    if (visible) R.string.channel_battery_monitor
                    else R.string.channel_battery_monitor_quiet
                ),
                if (visible) NotificationManager.IMPORTANCE_LOW
                else NotificationManager.IMPORTANCE_MIN
            ).apply {
                enableLights(false)
                enableVibration(false)
                lightColor = Color.GREEN
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        }
        return id
    }

    fun promptStartOnBoot(ctx: Context) {
        ensureChannel(ctx)
        val startIntent = Intent(ctx, BatteryMonitorService::class.java)
        // minSdk is 26, so the pre-O getService branch was unreachable.
        val pi = PendingIntent.getForegroundService(
            ctx, 1, startIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CH_ID)
            .setContentTitle(ctx.getString(R.string.monitoring_ready))
            .setContentText(ctx.getString(R.string.tap_to_start))
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setAutoCancel(true)
            .addAction(android.R.drawable.ic_media_play, ctx.getString(R.string.start_monitoring), pi)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1000, n)
    }

    fun monitoringNotification(
        ctx: Context,
        text: String,
        visible: Boolean = true,
        details: String? = null
    ): Notification {
        val channelId = ensureChannel(ctx, visible)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, BatteryMainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, channelId)
            .setContentTitle(ctx.getString(R.string.monitoring_battery))
            .setContentText(text)
            .apply {
                if (details != null) setStyle(NotificationCompat.BigTextStyle().bigText(details))
            }
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(
                if (visible) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN
            )
            .build()
    }

    fun notifyChargeLimit(ctx: Context, limit: Int) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CH_ID)
            .setContentTitle(ctx.getString(R.string.charge_limit_reached))
            .setContentText(ctx.getString(R.string.battery_at_percent, limit))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1001, n)
    }

    fun notifyTempHigh(ctx: Context, tempC: Int) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CH_ID)
            .setContentTitle(ctx.getString(R.string.high_temperature))
            .setContentText(ctx.getString(R.string.temperature_high, tempC))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOnlyAlertOnce(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1002, n)
    }

    fun notifyDischargeHigh(ctx: Context, ma: Int) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CH_ID)
            .setContentTitle(ctx.getString(R.string.high_discharge))
            .setContentText(ctx.getString(R.string.heavy_drain, ma))
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOnlyAlertOnce(true)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1003, n)
    }
}