package app.batstats.battery.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.batstats.R
import app.batstats.battery.BatteryMainActivity

/**
 * Posts the battery alerts.
 *
 * From API 26 a notification's sound and vibration come from its channel, and a channel's
 * settings cannot be changed once it exists - recreating it under the same id restores
 * whatever the user last chose. So the "Alert Sound" and "Alert Vibration" switches are
 * honoured by posting to one of four channels, created only when a combination is first
 * used. The user can still override any of them in the system settings, which is where
 * Android wants that choice to live.
 */
class AlertNotifier(private val context: Context) {

    companion object {
        private const val CHANNEL_PREFIX = "battery_alerts"

        /** Stable per alert, so a repeat replaces the old one instead of stacking. */
        private fun notificationId(alert: BatteryAlert) = 2100 + alert.ordinal
    }

    private val manager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun notify(alert: BatteryAlert, reading: AlertReading, sound: Boolean, vibrate: Boolean) {
        val channelId = ensureChannel(sound, vibrate)

        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, BatteryMainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(alert.icon())
            .setContentTitle(context.getString(alert.titleRes()))
            .setContentText(context.messageFor(alert, reading))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        runCatching { manager.notify(notificationId(alert), notification) }
    }

    private fun ensureChannel(sound: Boolean, vibrate: Boolean): String {
        val id = "$CHANNEL_PREFIX${if (sound) "_sound" else ""}${if (vibrate) "_vibrate" else ""}"
        if (manager.getNotificationChannel(id) != null) return id

        val importance =
            if (sound || vibrate) NotificationManager.IMPORTANCE_DEFAULT
            else NotificationManager.IMPORTANCE_LOW

        val channel = NotificationChannel(
            id,
            context.getString(channelNameRes(sound, vibrate)),
            importance
        ).apply {
            description = context.getString(R.string.alert_channel_description)
            enableVibration(vibrate)
            if (!sound) setSound(null, null)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
        return id
    }

    private fun channelNameRes(sound: Boolean, vibrate: Boolean): Int = when {
        sound && vibrate -> R.string.alert_channel_sound_vibrate
        sound -> R.string.alert_channel_sound
        vibrate -> R.string.alert_channel_vibrate
        else -> R.string.alert_channel_silent
    }
}

private fun BatteryAlert.titleRes(): Int = when (this) {
    BatteryAlert.LOW_BATTERY -> R.string.alert_low_battery
    BatteryAlert.HIGH_BATTERY -> R.string.charge_limit_reached
    BatteryAlert.TEMPERATURE -> R.string.high_temperature
    BatteryAlert.HIGH_DISCHARGE -> R.string.high_discharge
    BatteryAlert.CHARGING_COMPLETE -> R.string.alert_charging_complete
}

private fun BatteryAlert.icon(): Int = when (this) {
    BatteryAlert.LOW_BATTERY -> android.R.drawable.ic_lock_idle_low_battery
    BatteryAlert.CHARGING_COMPLETE, BatteryAlert.HIGH_BATTERY ->
        android.R.drawable.ic_lock_idle_charging
    else -> android.R.drawable.stat_sys_warning
}

private fun Context.messageFor(alert: BatteryAlert, reading: AlertReading): String = when (alert) {
    BatteryAlert.LOW_BATTERY ->
        getString(R.string.alert_low_battery_body, reading.levelPercent)
    BatteryAlert.HIGH_BATTERY ->
        getString(R.string.battery_at_percent, reading.levelPercent)
    BatteryAlert.TEMPERATURE ->
        getString(R.string.temperature_high, reading.temperatureC.toInt())
    BatteryAlert.HIGH_DISCHARGE ->
        getString(R.string.heavy_drain, kotlin.math.abs(reading.currentMa))
    BatteryAlert.CHARGING_COMPLETE ->
        getString(R.string.alert_charging_complete_body, reading.levelPercent)
}
