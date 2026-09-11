package app.batstats.battery.drain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import app.batstats.R
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.util.Notifier
import app.batstats.settings.NotificationStyle
import java.util.Date

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
        // Power/screen boundaries refresh the drain state immediately. A repository
        // sample may be minutes older when a long monitoring interval is selected.
        val status = context.getString(when {
            !state.hasBatteryReading -> R.string.monitoring_battery
            state.isCharging -> R.string.charging
            state.isPowered -> R.string.charging_paused
            else -> R.string.discharging
        })
        val title = if (state.hasBatteryReading && state.batteryLevel in 0..100) {
            context.getString(R.string.battery_level, state.batteryLevel, status)
        } else status
        val text = when {
            !state.hasBatteryReading -> context.getString(R.string.waiting_for_battery)
            style == NotificationStyle.MINIMAL -> if (state.isScreenOn) screenOn else screenOff
            else -> "$screenOn: ${formatDrainRatePreferPercent(state.screenOnDrainRate, state.capacityMah)} · " +
                "$screenOff: ${formatDrainRatePreferPercent(state.screenOffDrainRate, state.capacityMah)}"
        }
        val details = if (style == NotificationStyle.DETAILED && state.hasBatteryReading) buildString {
            appendLine("$screenOn: ${formatDuration(state.screenOnTimeMs)} · ${formatDrainRateWithPercent(state.screenOnDrainRate, state.capacityMah)}")
            appendLine("$screenOff: ${formatDuration(state.screenOffTimeMs)} · ${formatDrainRateWithPercent(state.screenOffDrainRate, state.capacityMah)}")
            appendLine(context.getString(R.string.notif_screen_off_times, formatDuration(state.awakeTimeMs), formatDuration(state.deepSleepTimeMs)))
            appendLine(context.getString(R.string.notif_recorded_drain, formatMahWithPercent(state.totalDrainMah, state.capacityMah), formatDuration(state.trackedTimeMs)))
            appendLine(context.getString(R.string.notif_drain_average_note))
            append(context.getString(R.string.notif_last_reading, DateFormat.getTimeFormat(context).format(Date(state.lastUpdateTime))))
        } else null

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_charging)
            .setContentTitle(title)
            .setContentText(text)
            .apply { if (details != null) setStyle(NotificationCompat.BigTextStyle().bigText(details)) }
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setWhen(state.lastUpdateTime)
            .setShowWhen(state.hasBatteryReading)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(android.R.drawable.ic_media_pause, context.getString(R.string.pause_monitoring), Notifier.pauseIntent(context))
            .build()
    }
}
