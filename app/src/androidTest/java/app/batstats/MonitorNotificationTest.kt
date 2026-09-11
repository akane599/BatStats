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
    @get:Rule(order = 0) val monitoringIsolation = TestMonitoring.withoutBootAutoStart()
    @get:Rule(order = 1) val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
    @get:Rule(order = 2) val compose = createAndroidComposeRule<BatteryMainActivity>()

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
        TestMonitoring.pauseWhenForegroundReady()
    }

    @After fun cleanup() {
        TestScreenshots.shell("cmd statusbar collapse")
        TestMonitoring.pauseWhenForegroundReady()
        if (::originalSettings.isInitialized) runBlocking {
            BatteryGraph.settings.update { originalSettings }
        }
    }

    @Test fun switchingStylesKeepOneMonitorAndOriginalActions() {
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
                assertEquals(
                    if (drain) listOf(context.getString(R.string.reset_action)) else emptyList(),
                    current.actions.orEmpty().map { it.title.toString() }
                )
                assertFalse("Monitoring updates must not present themselves as new timed events", current.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
                if (style == 2) captureNotificationShade(if (drain) "notification-drain" else "notification-monitor")
            }
        }

        // Switching the persistent display off also replaces the same notification.
        runBlocking { BatteryGraph.settings.update { it.copy(showNotification = false) } }
        compose.waitUntil(15_000) { monitor()?.channelId == "battery_monitor_quiet" }
        assertEquals(1, notifications.activeNotifications.count {
            it.notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        })
        context.stopService(Intent(context, BatteryMonitorService::class.java))
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
        assertEquals(context.getString(R.string.monitoring_battery), content.title)
        val details = requireNotNull(content.details)
        assertTrue(details.contains(context.getString(R.string.charging_paused)))
        assertTrue("Absent sensor values must remain unknown, including Fahrenheit", details.count { it == '—' } >= 4)
        listOf("0 mA", "0 mV", "0 mW", "32.0 °F").forEach { fabricated ->
            assertFalse("Missing reading became $fabricated", details.contains(fabricated))
        }
        val standard = Notifier.monitoringNotification(context, content.text, details = details, title = content.title)
        assertFalse(standard.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(standard.actions.orEmpty().isEmpty())

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
        assertFalse(emptySession.extras.getBoolean(Notification.EXTRA_SHOW_WHEN))
        assertTrue(emptySession.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
            .contains(context.getString(R.string.charging_paused)))
        assertEquals(listOf(context.getString(R.string.reset_action)),
            emptySession.actions.orEmpty().map { it.title.toString() })
    }

    private fun monitor(): Notification? = notifications.activeNotifications
        .singleOrNull { it.id == Notifier.NOTIF_ID }?.notification

    private fun notificationMatchesStyle(notification: Notification, drain: Boolean, style: Int): Boolean {
        val expectedChannel = if (drain) DrainNotificationManager.CHANNEL_ID else "battery_monitor"
        if (notification.channelId != expectedChannel) return false
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (text.isBlank() || text == context.getString(R.string.waiting_for_battery)) return false
        // Android 8 may retain an empty compat extra when no BigTextStyle is attached.
        // Treat blank and absent as the same presentation state.
        val details = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?.toString()?.takeIf { it.isNotBlank() }
        if ((details != null) != (style == 2)) return false
        return if (style == 0) {
            if (drain) text in listOf(context.getString(R.string.screen_on), context.getString(R.string.screen_off))
            else text.startsWith(context.getString(R.string.notif_level, "").trim())
        } else if (drain) {
            text.contains(context.getString(R.string.screen_on)) && text.contains(context.getString(R.string.screen_off))
        } else text.contains(" · ")
    }

    private fun captureNotificationShade(name: String) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val originalFlags = automation.serviceInfo.flags
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val appName = context.getString(R.string.app_name)
        val expectedAction = if (name == "notification-drain") context.getString(R.string.reset_action) else null
        val detailMarker = if (name == "notification-drain") context.getString(R.string.notif_session_heading)
            else context.getString(R.string.notif_status, "").trim()
        fun descendants(root: android.view.accessibility.AccessibilityNodeInfo): List<android.view.accessibility.AccessibilityNodeInfo> {
            val result = mutableListOf<android.view.accessibility.AccessibilityNodeInfo>()
            val queue = java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                result.add(node)
                for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
            }
            return result
        }
        fun shadeRoots(): List<android.view.accessibility.AccessibilityNodeInfo> = automation.windows.mapNotNull { window ->
            val bounds = android.graphics.Rect()
            window.getBoundsInScreen(bounds)
            window.root?.takeIf {
                !bounds.isEmpty && it.packageName?.toString() == "com.android.systemui"
            }
        }
        fun inNotificationHeader(node: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            var ancestor = node
            repeat(8) {
                if (ancestor.viewIdResourceName?.substringAfterLast('/') in setOf(
                        "app_name_text", "notification_header", "notification_main_column",
                        "status_bar_latest_event_content") ||
                    ancestor.className?.toString()?.endsWith("ExpandableNotificationRow") == true) return true
                ancestor = ancestor.parent ?: return false
            }
            return false
        }
        fun isAppHeader(node: android.view.accessibility.AccessibilityNodeInfo) = node.isVisibleToUser &&
            listOf(node.text, node.contentDescription).any { it?.toString()?.contains(appName, ignoreCase = true) == true } &&
            inNotificationHeader(node)
        fun isExpectedAction(node: android.view.accessibility.AccessibilityNodeInfo) = expectedAction != null &&
            listOf(node.text, node.contentDescription).any { it?.toString()?.equals(expectedAction, ignoreCase = true) == true }
        fun actionVisible(root: android.view.accessibility.AccessibilityNodeInfo): Boolean =
            expectedAction == null || descendants(root).any { it.isVisibleToUser && isExpectedAction(it) }
        fun detailsVisible(root: android.view.accessibility.AccessibilityNodeInfo): Boolean =
            descendants(root).any { it.isVisibleToUser && it.text?.toString()?.contains(detailMarker) == true }
        fun expandedVisible(root: android.view.accessibility.AccessibilityNodeInfo) = actionVisible(root) && detailsVisible(root)
        fun ownNotificationVisible(root: android.view.accessibility.AccessibilityNodeInfo): Boolean =
            descendants(root).any { isAppHeader(it) || (it.isVisibleToUser && isExpectedAction(it)) }
        fun expandOwnNotification(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            // Start at our app header so another app's expand button cannot be selected.
            var node = descendants(root).firstOrNull(::isAppHeader) ?: return false
            repeat(7) {
                if (node.viewIdResourceName?.substringAfterLast('/') in setOf(
                        "notification_stack_scroller", "notification_panel", "notification_shade",
                        "notification_container_parent", "status_bar")) return false
                if (node.actionList.any { it.id == android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND }) {
                    return node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_EXPAND)
                }
                val button = descendants(node).firstOrNull {
                    it.isVisibleToUser && it.viewIdResourceName?.endsWith(":id/expand_button") == true &&
                        it.contentDescription?.toString()?.contains("collapse", ignoreCase = true) != true
                }
                if (button != null && button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true
                // Reaching the row without an expand control means it is already expanded;
                // never climb to the whole shade and click some other notification.
                if (node.className?.toString()?.endsWith("ExpandableNotificationRow") == true) return false
                node = node.parent ?: return false
            }
            return false
        }
        fun revealAction(root: android.view.accessibility.AccessibilityNodeInfo): Boolean {
            // Large fonts can place expanded actions below the viewport. Ask the action's
            // ancestor scroller to reveal it without tapping the action or notification.
            val action = descendants(root).firstOrNull(::isExpectedAction) ?: return false
            return !action.isVisibleToUser && action.performAction(
                android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
            )
        }
        try {
            TestScreenshots.shell("cmd statusbar expand-notifications")
            val deadline = android.os.SystemClock.uptimeMillis() + 15_000L
            var lastExpansion = 0L
            var expanded = false
            while (android.os.SystemClock.uptimeMillis() < deadline) {
                val roots = shadeRoots().filter(::ownNotificationVisible)
                if (roots.any(::expandedVisible)) {
                    // Accessibility can update before the expansion animation is drawn.
                    android.os.SystemClock.sleep(300L)
                    if (shadeRoots().any(::expandedVisible)) { expanded = true; break }
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (now - lastExpansion >= 1_000L && roots.any {
                    expandOwnNotification(it) || revealAction(it)
                }) lastExpansion = now
                android.os.SystemClock.sleep(100L)
            }
            if (!expanded) {
                automation.takeScreenshot()?.let { failed ->
                    try { TestScreenshots.save("$name-failure", failed) } finally { failed.recycle() }
                }
                val visible = automation.windows.joinToString("\n") { window ->
                    val root = window.root
                    "Window ${window.id}, ${root?.packageName}: " + root?.let(::descendants)
                        ?.filter { it.isVisibleToUser }?.joinToString(" | ") {
                            "${it.viewIdResourceName}: ${it.text} (${it.contentDescription})"
                        }
                }
                fail("Expanded BatStats notification was not visible: ${visible.take(12_000)}")
            }
            val bitmap = requireNotNull(automation.takeScreenshot())
            try { TestScreenshots.save(name, bitmap) } finally { bitmap.recycle() }
        } finally {
            try {
                TestScreenshots.shell("cmd statusbar collapse")
                val deadline = android.os.SystemClock.uptimeMillis() + 8_000L
                while (android.os.SystemClock.uptimeMillis() < deadline &&
                    shadeRoots().any(::ownNotificationVisible)) {
                    android.os.SystemClock.sleep(100L)
                }
                assertFalse("Notification shade must close before the next style switch",
                    shadeRoots().any(::ownNotificationVisible))
            } finally {
                automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
            }
        }
    }
}
