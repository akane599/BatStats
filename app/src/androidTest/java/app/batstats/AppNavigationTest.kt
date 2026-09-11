package app.batstats

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.BatteryGraph
import app.batstats.battery.service.BatteryMonitorService
import app.batstats.battery.util.DetailedStatsCollector
import app.batstats.battery.util.ShellRunner
import kotlinx.coroutines.runBlocking
import androidx.test.espresso.Espresso.closeSoftKeyboard
import org.junit.*
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.koin.core.context.GlobalContext
import java.io.File

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppNavigationTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BatteryMainActivity>()

    @Before fun startWithMonitoringPaused() {
        // A delayed BOOT_COMPLETED can arrive just after a freshly installed test app.
        runBlocking { BatteryGraph.settings.update { it.copy(autoStartOnBoot = false) } }
        compose.activity.stopService(Intent(compose.activity, BatteryMonitorService::class.java))
        compose.waitUntil(15_000) { !BatteryGraph.repo.isMonitoringFlow.value }
        compose.waitForIdle()
    }

    private fun text(id: Int) = compose.activity.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(context.getExternalFilesDir(null), "screenshots")
        val output = File(directory, "$name.png")
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        if (Build.VERSION.SDK_INT >= 31) {
            // AGP creates its output directory as shell. Scoped storage can prevent the
            // app UID from writing there, so transfer the capture through the test API.
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            val pipes = automation.executeShellCommandRw("dd of=${output.absolutePath}")
            ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { response ->
                ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                response.readBytes()
            }
            ParcelFileDescriptor.AutoCloseInputStream(
                automation.executeShellCommand("wc -c ${output.absolutePath}")
            ).bufferedReader().use {
                check(it.readText().trim().substringBefore(' ').toLongOrNull()?.let { size -> size > 0 } == true)
            }
        } else {
            output.parentFile!!.mkdirs()
            output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }

    @After fun cleanup() {
        compose.activity.stopService(Intent(compose.activity, BatteryMonitorService::class.java))
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
