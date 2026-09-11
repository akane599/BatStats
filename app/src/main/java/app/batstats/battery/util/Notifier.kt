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

    /** A quiet channel reduces prominence; Android still exposes the foreground service. */
    private const val CH_ID_QUIET = "battery_monitor_quiet"

    const val NOTIF_ID = 11

    fun ensureChannel(ctx: Context) {
        ensureChannel(ctx, visible = true)
    }

    private fun ensureChannel(ctx: Context, visible: Boolean): String {
        val id = if (visible) CH_ID else CH_ID_QUIET
        val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val name = ctx.getString(
            if (visible) R.string.channel_battery_monitor else R.string.channel_battery_monitor_quiet
        )
        val existing = mgr.getNotificationChannel(id)
        if (existing == null) {
            val ch = NotificationChannel(
                id,
                name,
                if (visible) NotificationManager.IMPORTANCE_LOW else NotificationManager.IMPORTANCE_MIN
            ).apply {
                enableLights(false)
                enableVibration(false)
                lightColor = Color.GREEN
                setShowBadge(false)
            }
            mgr.createNotificationChannel(ch)
        } else if (existing.name.toString() != name) {
            // Update corrected/localized wording without replacing the user's channel settings.
            existing.name = name
            mgr.createNotificationChannel(existing)
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
            .setContentIntent(pi)
            .addAction(android.R.drawable.ic_media_play, ctx.getString(R.string.start_monitoring), pi)
            .build()
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(1000, n)
    }

    fun monitoringNotification(
        ctx: Context,
        text: String,
        visible: Boolean = true,
        details: String? = null,
        title: String? = null
    ): Notification {
        val channelId = ensureChannel(ctx, visible)
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, BatteryMainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, channelId)
            .setContentTitle(title ?: ctx.getString(R.string.monitoring_battery))
            .setContentText(text)
            .apply {
                if (details != null) setStyle(NotificationCompat.BigTextStyle().bigText(details))
            }
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentIntent(pi)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setShowWhen(false)
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
