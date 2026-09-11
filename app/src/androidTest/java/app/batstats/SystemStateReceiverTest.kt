package app.batstats

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.batstats.battery.data.registerBatteryUpdates
import app.batstats.battery.drain.registerDrainSystemReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SystemStateReceiverTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun trackerReceiverReceivesInitialSystemBatteryBroadcast() {
        val delivered = CountDownLatch(1)
        val battery = AtomicReference<Intent?>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED && isInitialStickyBroadcast) {
                    battery.set(intent)
                    delivered.countDown()
                }
            }
        }
        val sticky = registerBatteryUpdates(context, receiver)
        try {
            assertNotNull("The platform must expose its current battery state", sticky)
            // A synchronous sticky lookup succeeds even with the old registration. The
            // asynchronous receiver callback was denied on Android 8 by AndroidX's
            // generated signature permission, so assert delivery through the real filter.
            assertTrue("Initial system battery callback was blocked", delivered.await(5, TimeUnit.SECONDS))
            assertTrue(battery.get()!!.hasExtra(BatteryManager.EXTRA_LEVEL))
        } finally {
            context.unregisterReceiver(receiver)
        }
    }

    @Test
    fun ordinaryAppCannotForgeAnyTrackerSystemAction() {
        listOf(
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_SCREEN_ON,
            Intent.ACTION_SCREEN_OFF,
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_POWER_DISCONNECTED,
            PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED
        ).forEach { action ->
            try {
                context.sendBroadcast(Intent(action).setPackage(context.packageName))
                fail("Platform accepted an app-forged protected broadcast: $action")
            } catch (_: SecurityException) {
                // Exporting this system-only filter must not allow an ordinary app to
                // inject battery, screen, power or Doze state into the drain ledger.
            }
        }
    }
}
