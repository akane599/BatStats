package app.batstats.battery

import android.Manifest
import android.content.Intent
import androidx.activity.enableEdgeToEdge
import app.batstats.ui.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.batstats.settings.AppSettings
import app.batstats.ui.screens.MainScreen
import app.batstats.ui.theme.MainTheme
import io.github.mlmgames.settings.core.SettingsRepository
import org.koin.compose.koinInject

class BatteryMainActivity : ComponentActivity() {
    private val requestedScreen = MutableStateFlow<Screen?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDestination(intent)
    }

    private fun handleDestination(intent: Intent) {
        if (intent.getBooleanExtra("open_drain_stats", false)) {
            requestedScreen.value = Screen.DrainStats
            intent.removeExtra("open_drain_stats")
        }
    }

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDestination(intent)

        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val settingsRepository: SettingsRepository<AppSettings> = koinInject()
            val settings by settingsRepository.flow.collectAsStateWithLifecycle(initialValue = AppSettings())
            val isSystemDark = isSystemInDarkTheme()
            val darkTheme = when (settings.themeIndex) {
                1 -> false
                2 -> true
                else -> isSystemDark
            }
            MainTheme(
                darkTheme = darkTheme,
                dynamicColor = settings.dynamicColors,
                useAuroraTheme = !settings.dynamicColors,
                oledBlack = settings.oledBlack
            ) {
                val destination by requestedScreen.collectAsStateWithLifecycle()
                MainScreen(destination) { requestedScreen.value = null }
            }
        }
    }
}