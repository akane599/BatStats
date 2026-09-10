package app.batstats.battery

import android.app.Application
import app.batstats.battery.data.BatteryRepository
import app.batstats.battery.data.DataRetentionManager
import app.batstats.battery.data.db.BatteryDatabase
import app.batstats.battery.shizuku.ShizukuBridge
import app.batstats.di.appModule
import app.batstats.insights.ForegroundDrainTracker
import app.batstats.settings.AppSettings
import io.github.mlmgames.settings.core.SettingsRepository
import io.github.mlmgames.settings.core.managers.MigrationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.android.ext.koin.androidContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.context.startKoin

class BatteryApp : Application() {

    private val appScope: CoroutineScope by inject()
    private val migrationManager: MigrationManager by inject()
    private val shizukuBridge: ShizukuBridge by inject()
    private val dataRetentionManager: DataRetentionManager by inject()
    private val settings: SettingsRepository<AppSettings> by inject()

    override fun onCreate() {
        super.onCreate()

        startKoin {
            androidContext(this@BatteryApp)
            modules(appModule)
        }

        // Subscribe to Shizuku's binder callbacks before anything asks whether it is
        // available - the binder is delivered asynchronously shortly after process start,
        // and a listener registered later would miss it.
        shizukuBridge.warmUp()

        // Run migrations
        appScope.launch {
            migrationManager.migrate()
        }

        // Catches the case where monitoring is never switched on: the sample table would
        // otherwise keep whatever it accumulated forever.
        appScope.launch {
            dataRetentionManager.cleanupIfDue()
        }

        // Stamped once, so the Data screen can say how long it has been collecting.
        appScope.launch {
            settings.update { if (it.firstLaunchTime == 0L) it.copy(firstLaunchTime = System.currentTimeMillis()) else it }
        }
    }
}

/**
 * Legacy accessor for components. Prefer using Koin injection directly.
 */
object BatteryGraph : KoinComponent {
    val db: BatteryDatabase by inject()
    val repo: BatteryRepository by inject()
    val settings: SettingsRepository<AppSettings> by inject()
    val drainTracker: ForegroundDrainTracker by inject()
}