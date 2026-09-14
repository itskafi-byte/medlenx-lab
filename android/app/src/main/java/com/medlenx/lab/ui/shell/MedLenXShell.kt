package com.medlenx.lab.ui.shell

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.medlenx.lab.data.config.AppGraph
import com.medlenx.lab.ui.navigation.Destination
import com.medlenx.lab.ui.screens.PendingScreen
import com.medlenx.lab.ui.screens.analytics.AnalyticsScreen
import com.medlenx.lab.ui.screens.analytics.AnalyticsViewModel
import com.medlenx.lab.ui.screens.analytics.AnalyticsViewModelFactory
import com.medlenx.lab.ui.screens.hub.HubScreen
import com.medlenx.lab.ui.screens.hub.HubViewModel
import com.medlenx.lab.ui.screens.hub.HubViewModelFactory
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.ui.screens.rx.DoctorPitchCard
import com.medlenx.lab.ui.screens.rx.RxAuditScreen
import com.medlenx.lab.ui.screens.scan.ScanScreen
import com.medlenx.lab.ui.screens.scan.ScanViewModel
import com.medlenx.lab.ui.screens.scan.ScanViewModelFactory
import com.medlenx.lab.ui.screens.team.TeamScreen
import com.medlenx.lab.ui.screens.team.TeamViewModel
import com.medlenx.lab.ui.screens.team.TeamViewModelFactory
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
    appGraph: AppGraph,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    var query by remember { mutableStateOf("") }

    /**
     * The scan store lives here, not inside ScanScreen. Navigation recreates the
     * ScanScreen composable on every entry, so creating the ViewModel there threw
     * away the captured read, the verification form and the officer profile each
     * time the user walked to the Hub and back.
     */
    val context = LocalContext.current
    /** Medicine whose Doctor Pitch sheet is open; null keeps it closed. */
    var pitchTarget by remember { mutableStateOf<EnrichedMedicine?>(null) }
    val scanVm: ScanViewModel = viewModel(
        factory = ScanViewModelFactory(context.applicationContext as Application),
    )

    /** Same reasoning as [scanVm]: the Hub's filters and month must survive navigation. */
    val hubVm: HubViewModel = viewModel(
        factory = HubViewModelFactory(context.applicationContext as Application),
    )

    /** Same reasoning as [scanVm] and [hubVm]: the tier filter must survive navigation. */
    val teamVm: TeamViewModel = viewModel(
        factory = TeamViewModelFactory(context.applicationContext as Application),
    )

    /** Same reasoning as the others: the leaderboard page and filters must survive navigation. */
    val analyticsVm: AnalyticsViewModel = viewModel(
        factory = AnalyticsViewModelFactory(context.applicationContext as Application),
    )
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
                                scrollsUnderBar -> ScanScreen(
                                    vm = scanVm,
                                    onOpenAudit = {
                                        navController.navigate(Destination.RxAudit.route)
                                    },
                                )
                                dest == Destination.Analytics -> AnalyticsScreen(
                                    vm = analyticsVm,
                                    onOpenFilters = {
                                        // The FilterSheet is Step 9; until it exists,
                                        // say so rather than accepting the tap silently.
                                        android.widget.Toast.makeText(
                                            context,
                                            "Global filters are not wired up yet.",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    },
                                    onExport = {
                                        android.widget.Toast.makeText(
                                            context,
                                            "CSV export is unavailable in the offline build.",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    },
                                )
                                dest == Destination.Hub -> HubScreen(
                                    vm = hubVm,
                                    onOpenJob = { url -> openUrl(context, url) },
                                )
                                dest == Destination.Team -> TeamScreen(
                                    vm = teamVm,
                                    onExportPdf = {
                                        android.widget.Toast.makeText(
                                            context,
                                            "PDF export is unavailable in the offline build.",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    },
                                )
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

                composable(Destination.RxAudit.route) {
                    val s = scanVm.state
                    val profile = scanVm.officerProfile
                    RxAuditScreen(
                        rxId = s.receipt?.rxNumber ?: "Unsaved read",
                        doctorName = s.doctor.name,
                        doctorSpecialty = s.doctor.specialty,
                        repId = profile?.employeeId ?: s.receipt?.repCode.orEmpty(),
                        district = s.doctor.district,
                        medicines = s.enriched,
                        ownCompany = profile?.company.orEmpty(),
                        offTerritory = s.geo.offTerritory,
                        duplicateOfRxIds = emptyList(),
                        onBack = { navController.popBackStack() },
                        onPitchCard = { med ->
                            val sub = med.substitution
                            if (sub != null) {
                                pitchTarget = med
                            } else {
                                // No silent no-op: say why there is nothing to pitch.
                                Toast.makeText(
                                    context,
                                    "No own-portfolio equivalent for ${med.brandName} - " +
                                        "either this is already our brand, or the " +
                                        "catalogue holds no matching molecule.",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        onVerifyAgainstMedex = {
                            // The 25K+ Drug Index tab of the Pharma Intelligence Hub,
                            // not the scan workspace - re-scanning the same photo
                            // would just reproduce the value already on screen.
                            navController.navigate(Destination.Hub.route)
                        },
                        onExportCsv = { csv -> copyToClipboard(context, csv, "Market share CSV") },
                        onCopyClipboard = { text ->
                            copyToClipboard(context, text, "Market share")
                        },
                        modifier = Modifier.fillMaxSize(),
                        // The screen owns its verticalScroll, so the top offset is
                        // carried by its own padding and content passes under the bar.
                        contentPadding = PaddingValues(
                            start = MlxD.ScreenMargin,
                            end = MlxD.ScreenMargin,
                            top = MlxD.AppBarHeight + MlxD.SectionGap,
                            bottom = MlxD.ContentBottomClearance,
                        ),
                    )
                }
            }

            pitchTarget?.let { med ->
                med.substitution?.let { sub ->
                    DoctorPitchCard(
                        rxId = scanVm.state.receipt?.rxNumber ?: "Unsaved read",
                        doctorName = scanVm.state.doctor.name,
                        substitution = sub,
                        bioequivalenceNote = sub.pitch,
                        onClose = { pitchTarget = null },
                        onDownloadPdf = {
                            Toast.makeText(
                                context,
                                "PDF export is not available offline yet.",
                                Toast.LENGTH_LONG,
                            ).show()
                        },
                        onCopyPitch = {
                            copyToClipboard(context, sub.pitch, "Doctor pitch")
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * Puts export text on the system clipboard.
 *
 * There is no DocumentsUI write path yet, so "Export CSV" and "Copy" both land
 * here. That is deliberate: a clipboard result the officer can paste into
 * WhatsApp is genuinely useful, whereas a half-wired SAF picker is not.
 */
/**
 * Hands a URL to the browser.
 *
 * The job board's apply links are external career pages; there is no in-app
 * WebView, so a missing browser is reported rather than silently swallowed.
 */
private fun openUrl(context: Context, url: String) {
    val opened = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.isSuccess
    if (!opened) {
        Toast.makeText(
            context,
            "No browser available to open $url",
            Toast.LENGTH_LONG,
        ).show()
    }
}

private fun copyToClipboard(context: Context, text: String, label: String) {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "$label copied to the clipboard", Toast.LENGTH_SHORT).show()
}
