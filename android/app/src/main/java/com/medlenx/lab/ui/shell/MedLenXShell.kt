package com.medlenx.lab.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Divider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.medlenx.lab.ui.navigation.Destination
import com.medlenx.lab.ui.screens.PendingScreen
import com.medlenx.lab.ui.screens.analytics.AnalyticsScreen
import com.medlenx.lab.ui.screens.scan.ScanScreen
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/**
 * The app shell: translucent top bar, scrolling content area, bottom navigation.
 *
 * Content carries 24dp of bottom clearance so nothing is ever hidden behind the
 * bottom bar (web equivalent: `pb-24`).
 */
@Composable
fun MedLenXShell(
    topBarState: TopBarState,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    var query by remember { mutableStateOf("") }
    // The scrolling content below is the haze source; the app bar is the haze child.
    val hazeState = rememberHazeState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = Destination.fromRoute(backStackEntry?.destination?.route)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Mlx.Screen,
        topBar = {
            Column {
                MlxTopBar(
                    query = query,
                    onQueryChange = { query = it },
                    onSearchClick = { /* global search overlay - Step 9 */ },
                    state = topBarState,
                    hazeState = hazeState,
                )
                Divider(color = Mlx.Brand200, thickness = 1.dp)
            }
        },
        bottomBar = {
            Column {
                Divider(color = Mlx.Brand200, thickness = 1.dp)
                MlxBottomNav(
                    selected = selected,
                    onSelect = { dest ->
                        navController.navigate(dest.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                )
            }
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Mlx.Screen)
                // Deliberately NOT .padding(inner): content has to reach y=0 and pass
                // *behind* the app bar, or the backdrop blur has nothing to sample.
                // Only the bottom-bar inset is applied, so nothing hides under the nav.
                .hazeSource(state = hazeState)
                .padding(bottom = inner.calculateBottomPadding()),
        ) {
            // NOTE: no verticalScroll() here. Wrapping the NavHost in a scrollable
            // gives children unbounded height, which crashes any LazyColumn added in
            // Steps 5-8. Each screen owns its own scrolling.
            NavHost(
                navController = navController,
                startDestination = Destination.Scan.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                Destination.bottomBar.forEach { dest ->
                    composable(dest.route) {
                        // Scan scrolls under the app bar (its own scroll container
                        // carries the top offset, so content disappears behind the
                        // blurred bar). Every other screen is simply offset below it
                        // until Steps 5-9 give them real scrolling content.
                        val scrollsUnderBar = dest == Destination.Scan || dest == Destination.Analytics
                        Column(
                            Modifier.padding(
                                PaddingValues(
                                    start = MlxD.ScreenMargin,
                                    end = MlxD.ScreenMargin,
                                    top = if (scrollsUnderBar) 0.dp else MlxD.AppBarHeight + MlxD.SectionGap,
                                    bottom = MlxD.ContentBottomClearance,
                                ),
                            ),
                        ) {
                            when {
                                scrollsUnderBar -> ScanScreen()
                                dest == Destination.Analytics -> AnalyticsScreen()
                                else -> PendingScreen(destination = dest)
                            }
                        }
                    }
                }
                composable(Destination.Help.route) {
                    Column(
                        Modifier.padding(
                            PaddingValues(
                                start = MlxD.ScreenMargin,
                                end = MlxD.ScreenMargin,
                                top = MlxD.AppBarHeight + MlxD.SectionGap,
                                bottom = MlxD.ContentBottomClearance,
                            ),
                        ),
                    ) {
                        PendingScreen(destination = Destination.Help)
                    }
                }
            }
        }
    }
}
