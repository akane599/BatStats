package app.batstats.battery.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import app.batstats.battery.data.db.BatterySample

/**
 * Reads the battery without needing anything else to be running.
 *
 * ACTION_BATTERY_CHANGED is a sticky broadcast, so a null receiver returns the current
 * state immediately. That matters for the widgets: they used to render whatever the
 * monitoring service last pushed them, which meant they froze the moment it stopped.
 */
object BatteryReader {

    fun currentIntent(context: Context): Intent? =
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

    fun currentSample(context: Context): BatterySample? =
        currentIntent(context)?.let { sampleFrom(context, it) }

    /** Turns a battery-changed intent into a sample, filling in what only the manager knows. */
    fun sampleFrom(context: Context, intent: Intent): BatterySample {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)

        return BatterySample(
            timestamp = System.currentTimeMillis(),
            levelPercent = if (level >= 0 && scale > 0) (level * 100) / scale else 0,
            status = intent.getIntExtra(
                BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN
            ),
            plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0),
            currentNowUa = batteryManager?.let { currentNowUa(it) },
            chargeCounterUah = batteryManager
                ?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER),
            voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
            temperatureDeciC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0),
            health = intent.getIntExtra(
                BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN
            ),
            screenOn = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false
        )
    }

    /** Some devices leave CURRENT_NOW empty and only populate the average. */
    fun currentNowUa(batteryManager: BatteryManager): Long {
        val now = batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        return if (now == 0L || now == Long.MIN_VALUE) {
            batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_AVERAGE)
        } else {
            now
        }
    }
}
