package app.batstats.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import app.batstats.ui.screens.AppDrainScreen
import app.batstats.ui.screens.BatterySettingsScreen
import app.batstats.ui.screens.DashboardScreen
import app.batstats.ui.screens.DataScreen
import app.batstats.ui.screens.DetailedStatsScreen
import app.batstats.ui.screens.DrainStatsScreen
import app.batstats.ui.screens.HistoryScreen
import app.batstats.ui.screens.SessionDetailsScreen
import kotlinx.serialization.Serializable
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun NavGraph(
    backStack: NavBackStack<NavKey>,
    decorators: List<NavEntryDecorator<Any>>,
    modifier: Modifier = Modifier
) {
    NavDisplay(
        modifier = modifier,
        backStack = backStack,
        onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
        entryDecorators = decorators,
        entryProvider = entryProvider {

            // Dashboard
            entry<Screen.Dashboard> {
                DashboardScreen(
                    onOpenHistory = { backStack.add(Screen.History) },
                    onOpenAlarms = { backStack.add(Screen.Settings(initialCategory = "Notifications")) }
                )
            }

            entry<Screen.History> {
                HistoryScreen(
                    onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                    onOpenSession = { id -> backStack.add(Screen.SessionDetails(id)) }
                )
            }

            entry<Screen.SessionDetails> { args ->
                SessionDetailsScreen(
                    sessionId = args.sessionId,
                    onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                    vm = koinViewModel(parameters = { parametersOf(args.sessionId) })
                )
            }

            entry<Screen.Data> {
                DataScreen(onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) })
            }

            entry<Screen.Settings> { args ->
                BatterySettingsScreen(
                    onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) },
                    onExportData = { backStack.add(Screen.Data) },
                    onOpenAccess = { backStack.add(Screen.DetailedStats) },
                    initialCategory = args.initialCategory
                )
            }

            entry<Screen.DetailedStats> {
                DetailedStatsScreen(onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) })
            }

            entry<Screen.DrainStats> {
                DrainStatsScreen(onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) })
            }

            entry<Screen.AppDrain> {
                AppDrainScreen(onBack = { if (backStack.size > 1) backStack.removeAt(backStack.lastIndex) }, onOpenAccess = { backStack.add(Screen.DetailedStats) })
            }
        }
    )
}

/**
 * Navigation 3 Keys.
 */
@Serializable
sealed interface Screen: NavKey {
    @Serializable
    data object Dashboard : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data class SessionDetails(val sessionId: String) : Screen

    @Serializable
    data object Data : Screen

    @Serializable
    data class Settings(val initialCategory: String? = null) : Screen

    @Serializable
    data object DetailedStats : Screen

    @Serializable
    data object DrainStats : Screen

    @Serializable
    data object AppDrain : Screen
}