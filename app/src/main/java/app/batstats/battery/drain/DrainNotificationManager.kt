package app.batstats.battery.drain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.batstats.R
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.util.Notifier
import app.batstats.settings.NotificationStyle

/** Notification factory. The foreground service owns its one notification and update flow. */
class DrainNotificationManager(
    private val context: Context,
    private val drainTracker: AdvancedDrainTracker
) {
    companion object {
        const val CHANNEL_ID = "drain_stats_channel"
        const val NOTIFICATION_ID = Notifier.NOTIF_ID
    }

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.drain_statistics),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.notif_drain_channel_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun getNotification(
        state: DrainState = drainTracker.drainState.value,
        style: NotificationStyle = NotificationStyle.COMPACT
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            2001,
            Intent(context, BatteryMainActivity::class.java).apply {
                putExtra("open_drain_stats", true)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val screenOn = context.getString(R.string.screen_on)
        val screenOff = context.getString(R.string.screen_off)
        val currentState = when {
            !state.hasBatteryReading -> context.getString(R.string.monitoring_battery)
            state.isCharging -> "⚡ ${context.getString(R.string.charging)}"
            state.isPowered -> "🔌 ${context.getString(R.string.charging_paused)}"
            state.isScreenOn -> "📱 $screenOn"
            state.isDozing -> "💤 ${context.getString(R.string.dozing)}"
            else -> "🌙 $screenOff"
        }
        val title = if (state.hasBatteryReading && state.batteryLevel in 0..100) {
            "${state.batteryLevel}% • $currentState"
        } else currentState
        val text = when {
            !state.hasBatteryReading -> context.getString(R.string.waiting_for_battery)
            style == NotificationStyle.MINIMAL -> if (state.isScreenOn) screenOn else screenOff
            else -> context.getString(R.string.notif_drain_compact,
                formatDrainRatePreferPercent(state.screenOnDrainRate, state.capacityMah),
                formatDrainRatePreferPercent(state.screenOffDrainRate, state.capacityMah))
        }
        val details = if (style == NotificationStyle.DETAILED && state.hasBatteryReading) buildString {
            appendLine(context.getString(R.string.notif_drain_rates_heading))
            appendLine("📱 " + context.getString(R.string.notif_rate_and_duration, screenOn,
                formatDrainRateWithPercent(state.screenOnDrainRate, state.capacityMah), formatDuration(state.screenOnTimeMs)))
            appendLine("🌙 " + context.getString(R.string.notif_rate_and_duration, screenOff,
                formatDrainRateWithPercent(state.screenOffDrainRate, state.capacityMah), formatDuration(state.screenOffTimeMs)))
            appendLine()
            appendLine(context.getString(R.string.notif_screen_off_time_heading))
            val awakeShare = if (state.screenOffTimeMs > 0) state.awakeTimeMs * 100.0 / state.screenOffTimeMs else 0.0
            appendLine("😴 " + context.getString(R.string.notif_time_and_share, context.getString(R.string.deep_sleep),
                formatDuration(state.deepSleepTimeMs), state.deepSleepPercentage.toDouble()))
            appendLine("⚡ " + context.getString(R.string.notif_time_and_share, context.getString(R.string.awake),
                formatDuration(state.awakeTimeMs), awakeShare))
            appendLine()
            appendLine(context.getString(R.string.notif_session_heading))
            appendLine(context.getString(R.string.notif_session_total,
                formatMahWithPercent(state.totalDrainMah, state.capacityMah), formatDuration(state.trackedTimeMs)))
            append(context.getString(R.string.notif_session_average,
                formatDrainRateWithPercent(state.averageDrainRate, state.capacityMah)))
        } else null

        val resetIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, DrainNotificationReceiver::class.java)
                .setAction(DrainNotificationReceiver.ACTION_RESET)
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(text)
            .apply { if (details != null) setStyle(NotificationCompat.BigTextStyle().bigText(details)) }
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_menu_rotate, context.getString(R.string.reset_action), resetIntent)
            .build()
    }
}
