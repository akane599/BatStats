package app.batstats.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Analytics
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryFull
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import app.batstats.R
import app.batstats.ui.NavGraph
import app.batstats.ui.Screen

/**
 * The app's top-level destinations.
 *
 * These used to be six unlabelled icon buttons crowded into the dashboard's app bar, which
 * left almost no room for the title on a narrow phone and gave no clue what any of them did.
 * A navigation bar names them and keeps them reachable from anywhere.
 */
private enum class TopLevel(val screen: Screen, val icon: ImageVector, val labelRes: Int) {
    DASHBOARD(Screen.Dashboard, Icons.Outlined.BatteryFull, R.string.nav_battery),
    APPS(Screen.AppDrain, Icons.Outlined.Apps, R.string.nav_apps),
    DRAIN(Screen.DrainStats, Icons.AutoMirrored.Outlined.ShowChart, R.string.nav_drain),
    STATS(Screen.DetailedStats, Icons.Outlined.Analytics, R.string.nav_stats),
    SETTINGS(Screen.Settings(), Icons.Outlined.Settings, R.string.nav_settings)
}

@Composable
fun MainScreen(requestedScreen: Screen? = null, onNavigationHandled: () -> Unit = {}) {
    val backStack = rememberNavBackStack(Screen.Dashboard)
    LaunchedEffect(requestedScreen) {
        requestedScreen?.let { backStack.switchTo(it); onNavigationHandled() }
    }

    BackHandler(enabled = backStack.size > 1) {
        backStack.removeAt(backStack.lastIndex)
    }

    val current = backStack.lastOrNull()
    val selected = TopLevel.entries.firstOrNull { it.matches(current) }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        BoxWithConstraints {
            val useRail = maxWidth >= 600.dp
            Row(Modifier.fillMaxSize()) {
                if (useRail && selected != null) {
                    NavigationRail(Modifier.fillMaxHeight()) {
                        TopLevel.entries.forEach { destination ->
                            NavigationRailItem(
                                modifier = Modifier.testTag("nav_${destination.name.lowercase()}"),
                                selected = destination == selected,
                                onClick = { backStack.switchTo(destination.screen) },
                                icon = { Icon(destination.icon, null) },
                                label = { Text(stringResource(destination.labelRes)) }
                            )
                        }
                    }
                }
        Scaffold(
            modifier = Modifier.weight(1f),
            // The inner screens each run their own Scaffold and handle their own insets;
            // this one exists only to hold the bar.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // Detail screens - a session, the export flow - are pushed on top of a
                // top-level destination and get the full height to themselves.
                if (selected != null && !useRail) {
                    NavigationBar {
                        TopLevel.entries.forEach { destination ->
                            NavigationBarItem(
                                modifier = Modifier.testTag("nav_${destination.name.lowercase()}"),
                                selected = destination == selected,
                                onClick = { backStack.switchTo(destination.screen) },
                                icon = {
                                    Icon(
                                        destination.icon,
                                        contentDescription = null
                                    )
                                },
                                label = { Text(stringResource(destination.labelRes)) }
                            )
                        }
                    }
                }
            }
        ) { padding ->
            NavGraph(
                backStack = backStack,
                decorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator()
                ),
                modifier = Modifier.padding(bottom = padding.calculateBottomPadding())
            )
        }
            }
        }
    }
}

/** Settings carries an optional category, so identity is by type rather than by value. */
private fun TopLevel.matches(key: NavKey?): Boolean = when (screen) {
    is Screen.Settings -> key is Screen.Settings
    else -> key == screen
}

/**
 * Moves to a top-level destination rather than stacking onto the current one, so the bar
 * never builds up a pile of screens. Back from any of them returns to the dashboard, and
 * back from the dashboard leaves the app.
 */
private fun NavBackStack<NavKey>.switchTo(screen: Screen) {
    if (lastOrNull() == screen) return
    clear()
    if (screen != Screen.Dashboard) add(Screen.Dashboard)
    add(screen)
}
