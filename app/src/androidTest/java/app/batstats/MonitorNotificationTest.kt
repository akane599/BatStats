package app.batstats

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.batstats.battery.BatteryGraph
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.data.db.BatterySample
import app.batstats.battery.drain.DrainNotificationManager
import app.batstats.battery.drain.DrainState
import app.batstats.battery.service.BatteryMonitorService
import app.batstats.battery.util.MonitorNotificationText
import app.batstats.battery.util.Notifier
import app.batstats.settings.AppSettings
import app.batstats.settings.NotificationStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
class MonitorNotificationTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BatteryMainActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notifications get() = context.getSystemService(NotificationManager::class.java)
    private lateinit var originalSettings: AppSettings

    @Before fun prepare() {
        originalSettings = runBlocking { BatteryGraph.settings.flow.first() }
        runBlocking {
            BatteryGraph.settings.update {
                it.copy(autoStartOnBoot = false, showNotification = true, showDrainNotification = false,
                    notificationStyleIndex = 0, monitoringIntervalIndex = 0, trackAppDrain = false,
                    lowBatteryAlertEnabled = false, highBatteryAlertEnabled = false,
                    temperatureWarningEnabled = false, dischargeAlertEnabled = false)
            }
        }
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        compose.waitUntil(15_000) { !BatteryGraph.repo.isMonitoringFlow.value }
    }

    @After fun cleanup() {
        TestScreenshots.shell("cmd statusbar collapse")
        context.stopService(Intent(context, BatteryMonitorService::class.java))
        compose.waitUntil(15_000) { !BatteryGraph.repo.isMonitoringFlow.value }
        if (::originalSettings.isInitialized) runBlocking {
            BatteryGraph.settings.update { originalSettings }
        }
    }

    @Test fun switchingStylesKeepsOneMonitorAndPauseStopsService() {
        ContextCompat.startForegroundService(context, Intent(context, BatteryMonitorService::class.java))
        compose.waitUntil(20_000) {
            BatteryGraph.repo.isMonitoringFlow.value && BatteryGraph.repo.realtimeFlow.value.sample != null
        }
        for (drain in listOf(false, true)) {
            for (style in 0..2) {
                runBlocking {
                    BatteryGraph.settings.update {
                        it.copy(showDrainNotification = drain, notificationStyleIndex = style)
                    }
                }
                compose.waitUntil(20_000) {
                    monitor()?.let { notificationMatchesStyle(it, drain, style) } == true
                }
                val active = notifications.activeNotifications
                assertEquals("Style switches must replace the foreground notification", 1,
                    active.count { it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0 })
                val current = active.single { it.id == Notifier.NOTIF_ID }.notification
                assertTrue(current.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                assertEquals(listOf(context.getString(R.string.pause_monitoring)),
                    current.actions.orEmpty().map { it.title.toString() })
                assertTrue("Notification time must refer to a battery reading", current.`when` > 0)
                if (style == 2) captureNotificationShade(if (drain) "notification-drain" else "notification-monitor")
            }
        }

        // Switching the persistent display off also replaces the same notification.
        runBlocking { BatteryGraph.settings.update { it.copy(showNotification = false) } }
        compose.waitUntil(15_000) { monitor()?.channelId == "battery_monitor_quiet" }
        assertEquals(1, notifications.activeNotifications.count {
            it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        })
        monitor()!!.actions.single().actionIntent.send()
        compose.waitUntil(15_000) {
            !BatteryGraph.repo.isMonitoringFlow.value && monitor() == null
        }
    }

    @Test fun missingSensorValuesAndPausedChargingRemainHonest() {
        val sample = BatterySample(
            timestamp = 1_700_000_000_000L, levelPercent = 50,
            status = BatteryManager.BATTERY_STATUS_NOT_CHARGING,
            plugged = BatteryManager.BATTERY_PLUGGED_USB,
            currentNowUa = null, chargeCounterUah = null, voltageMv = null,
            temperatureDeciC = null, health = null, screenOn = true
        )
        val content = MonitorNotificationText.from(context, sample, NotificationStyle.DETAILED, useFahrenheit = true)
        assertTrue(content.title.contains(context.getString(R.string.charging_paused)))
        val details = requireNotNull(content.details)
        assertTrue("Absent sensor values must remain unknown, including Fahrenheit", details.count { it == '—' } >= 4)
        listOf("0 mA", "0 mV", "0 mW", "32.0 °F").forEach { fabricated ->
            assertFalse("Missing reading became $fabricated", details.contains(fabricated))
        }
        val standard = Notifier.monitoringNotification(context, content.text, details = details,
            title = content.title, readingTime = sample.timestamp)
        assertEquals(sample.timestamp, standard.`when`)
        assertEquals(listOf(context.getString(R.string.pause_monitoring)), standard.actions.map { it.title.toString() })

        val drain = GlobalContext.get().get<DrainNotificationManager>()
        val waiting = drain.getNotification(state = DrainState(), style = NotificationStyle.DETAILED)
        assertEquals(context.getString(R.string.waiting_for_battery),
            waiting.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertNull(waiting.extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
        val emptySession = drain.getNotification(
            state = DrainState(hasBatteryReading = true, batteryLevel = 50, isScreenOn = true, isPowered = true,
                lastUpdateTime = sample.timestamp),
            style = NotificationStyle.COMPACT
        )
        val drainText = emptySession.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertEquals("An unobserved drain rate must not be shown as zero", 2, drainText.count { it == '—' })
        assertEquals(sample.timestamp, emptySession.`when`)
        assertTrue(emptySession.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            .contains(context.getString(R.string.charging_paused)))
    }

    private fun monitor(): Notification? = notifications.activeNotifications
        .singleOrNull { it.id == Notifier.NOTIF_ID }?.notification

    private fun notificationMatchesStyle(notification: Notification, drain: Boolean, style: Int): Boolean {
        val expectedChannel = if (drain) DrainNotificationManager.CHANNEL_ID else "battery_monitor"
        if (notification.channelId != expectedChannel) return false
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank() || text == context.getString(R.string.waiting_for_battery)) return false
        val details = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        if ((details != null) != (style == 2)) return false
        return if (style == 0) {
            if (drain) text in listOf(context.getString(R.string.screen_on), context.getString(R.string.screen_off))
            else text == context.getString(R.string.monitoring_battery)
        } else if (drain) {
            text.contains(context.getString(R.string.screen_on)) && text.contains(context.getString(R.string.screen_off))
        } else text.contains(" · ")
    }

    private fun captureNotificationShade(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        TestScreenshots.shell("cmd statusbar expand-notifications")
        try {
            automation.waitForIdle(500, 5_000)
            val bitmap = requireNotNull(automation.takeScreenshot())
            try { TestScreenshots.save(name, bitmap) } finally { bitmap.recycle() }
        } finally {
            TestScreenshots.shell("cmd statusbar collapse")
            automation.waitForIdle(500, 5_000)
        }
    }
}
