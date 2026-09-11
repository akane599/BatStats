package app.batstats

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.ShellRunner
import androidx.test.espresso.Espresso.closeSoftKeyboard
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppNavigationTest {
    @get:Rule(order = 0) val monitoringIsolation = TestMonitoring.withoutBootAutoStart()
    @get:Rule(order = 1) val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
    @get:Rule(order = 2) val compose = createAndroidComposeRule<BatteryMainActivity>()

    @Before fun startWithMonitoringPaused() {
        TestMonitoring.pauseWhenForegroundReady()
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        TestScreenshots.save(name, compose.onRoot().captureToImage().asAndroidBitmap())
    }

    @After fun cleanup() {
        TestMonitoring.pauseWhenForegroundReady()
    }

    @Test fun mainDestinationsSearchAndDataActionsRemainReachable() {
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("battery_level").fetchSemanticsNodes().isNotEmpty() }
        screenshot("dashboard")
        compose.onNodeWithTag("nav_apps").performClick()
        compose.onNodeWithTag("app_search").performTextInput("no.such.package")
        compose.onNodeWithTag("app_search").assertTextContains("no.such.package")
        closeSoftKeyboard()
        screenshot("apps")
        compose.onNodeWithTag("nav_drain").performClick()
        compose.onNodeWithText(text(R.string.drain_statistics)).assertIsDisplayed()
        screenshot("drain")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("screen_off_breakdown"))
        compose.onNodeWithTag("screen_off_breakdown").assertIsDisplayed()
        val inScreenOffBreakdown = hasAnyAncestor(hasTestTag("screen_off_breakdown"))
        compose.onNode(hasText(text(R.string.screen_off_sleep_breakdown)) and inScreenOffBreakdown)
            .assertIsDisplayed()
        compose.onAllNodes(hasText(text(R.string.active)) and inScreenOffBreakdown).assertCountEquals(0)
        compose.onAllNodes(hasText(text(R.string.idle)) and inScreenOffBreakdown).assertCountEquals(0)
        screenshot("screen-off-breakdown")
        compose.onNodeWithTag("nav_stats").performClick()
        compose.waitForIdle()
        screenshot("stats")
        compose.onNodeWithTag("nav_settings").performClick()
        compose.onNodeWithTag("settings_list").assertIsDisplayed()
        screenshot("settings")
        compose.onNodeWithContentDescription(text(R.string.settings_more)).performClick()
        compose.onNodeWithText(text(R.string.data_export_import)).performClick()
        compose.onNodeWithText(text(R.string.export_json)).performScrollTo().assertIsEnabled()
        compose.onNodeWithText(text(R.string.samples)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.sessions)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.export_json)).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.import_csv)).performScrollTo().assertIsEnabled()
        screenshot("data")
    }

    @Test fun monitoringCanStartAndStopAndDrainIntentOpensCorrectTab() {
        compose.onNodeWithTag("monitor_toggle").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.pause_monitoring)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("monitor_toggle").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text(R.string.start_monitoring)).fetchSemanticsNodes().isNotEmpty() }
        compose.activityRule.scenario.onActivity {
            // Preserve the scenario's launcher action/category so its lifecycle observer
            // can match onNewIntent and close the activity after this assertion.
            it.startActivity(Intent(it.intent)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_drain_stats", true))
        }
        compose.onNodeWithText(text(R.string.drain_statistics)).assertIsDisplayed()
    }

    @Test fun zzSystemStatisticsLoadWithAdbGrants() {
        // This runs last: grants belong to this disposable emulator installation and
        // leave the preceding tests able to exercise the ordinary no-access experience.
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val pkg = compose.activity.packageName
        listOf("DUMP", "BATTERY_STATS", "PACKAGE_USAGE_STATS", "INTERACT_ACROSS_USERS").forEach { permission ->
            ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("pm grant $pkg android.permission.$permission")
            ).bufferedReader().use { org.junit.Assert.assertTrue(it.readText().isBlank()) }
        }
        ParcelFileDescriptor.AutoCloseInputStream(
            automation.executeShellCommand("appops set $pkg GET_USAGE_STATS allow")
        ).bufferedReader().use { org.junit.Assert.assertTrue(it.readText().isBlank()) }
        compose.onNodeWithTag("nav_stats").performClick()
        val collector = GlobalContext.get().get<DetailedStatsCollector>()
        compose.waitUntil(30_000) { collector.snapshot.value != null && !collector.isRefreshing.value }
        org.junit.Assert.assertEquals(ShellRunner.Mode.ADB, collector.mode.value)
        compose.onNodeWithText("Overview").assertIsDisplayed()
        screenshot("stats-adb")
    }
}
