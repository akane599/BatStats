package app.batstats

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import app.batstats.battery.BatteryMainActivity
import app.batstats.battery.service.BatteryMonitorService
import org.junit.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AppNavigationTest {
    @get:Rule(order = 0) val permissions = GrantPermissionRule.grant(
        *if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()
    )
    @get:Rule(order = 1) val compose = createAndroidComposeRule<BatteryMainActivity>()

    private fun text(id: Int) = compose.activity.getString(id)
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(context.getExternalFilesDir(null), "screenshots/$name.png")
        output.parentFile!!.mkdirs()
        output.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
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
        compose.onNodeWithText(text(R.string.samples), useUnmergedTree = true).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.sessions), useUnmergedTree = true).performScrollTo().performClick()
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
            it.startActivity(Intent(it, BatteryMainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("open_drain_stats", true))
        }
        compose.onNodeWithText(text(R.string.drain_statistics)).assertIsDisplayed()
    }
}
