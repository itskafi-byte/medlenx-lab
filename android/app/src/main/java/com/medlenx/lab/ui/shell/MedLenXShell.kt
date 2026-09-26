package com.medlenx.lab.ui.shell

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.medlenx.lab.data.model.PitchCompliance
import com.medlenx.lab.data.repo.Compliance
import com.medlenx.lab.data.export.ExportDocuments
import com.medlenx.lab.ui.components.copyToClipboard
import com.medlenx.lab.ui.navigation.Destination
import com.medlenx.lab.ui.screens.PendingScreen
import com.medlenx.lab.ui.screens.analytics.AnalyticsScreen
import com.medlenx.lab.ui.screens.analytics.FilterSheet
import com.medlenx.lab.ui.screens.analytics.toRecentRxRows
import com.medlenx.lab.ui.screens.help.HelpScreen
import com.medlenx.lab.ui.screens.help.HelpViewModel
import com.medlenx.lab.ui.screens.help.HelpViewModelFactory
import com.medlenx.lab.ui.screens.search.SearchOverlay
import com.medlenx.lab.ui.screens.search.SearchViewModel
import com.medlenx.lab.ui.screens.search.SearchViewModelFactory
import com.medlenx.lab.ui.screens.settings.SettingsScreen
import com.medlenx.lab.ui.screens.settings.SettingsViewModel
import com.medlenx.lab.ui.screens.settings.SettingsViewModelFactory
import com.medlenx.lab.ui.screens.analytics.AnalyticsViewModel
import com.medlenx.lab.ui.screens.analytics.AnalyticsViewModelFactory
import com.medlenx.lab.ui.screens.hub.HubScreen
import com.medlenx.lab.ui.screens.hub.HubViewModel
import com.medlenx.lab.ui.screens.hub.HubViewModelFactory
import com.medlenx.lab.ui.screens.rx.PitchTarget
import com.medlenx.lab.ui.screens.rx.DoctorPitchCard
import com.medlenx.lab.ui.screens.rx.RxAuditScreen
import com.medlenx.lab.ui.screens.scan.ScanScreen
import com.medlenx.lab.ui.screens.scan.ScanViewModel
import com.medlenx.lab.ui.screens.scan.ScanViewModelFactory
import com.medlenx.lab.ui.screens.team.TeamScreen
import com.medlenx.lab.ui.screens.team.TeamViewModel
import com.medlenx.lab.ui.screens.team.TeamViewModelFactory
import com.medlenx.lab.ui.export.exportFileName
import com.medlenx.lab.ui.export.rememberDocumentSaver
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.launch

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
    /**
     * What the Doctor Pitch sheet is currently showing; null keeps it closed.
     *
     * Carries the rx number and doctor with the substitution rather than pointing at the
     * live scan, because the sheet is reachable from two places now: the Rx Audit screen,
     * which audits the scan in progress, and the audit drawer, which audits a saved
     * prescription that may have been captured by another device. Reading
     * `scanVm.state` for those two fields made the drawer's pitch card claim the wrong
     * Rx number whenever the drawer was opened without an active scan.
     */
    var pitchTarget by remember { mutableStateOf<PitchTarget?>(null) }
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
    val settingsVm: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(context.applicationContext as Application),
    )
    val helpVm: HelpViewModel = viewModel(
        factory = HelpViewModelFactory(context.applicationContext as Application),
    )
    val searchVm: SearchViewModel = viewModel(
        factory = SearchViewModelFactory(context.applicationContext as Application),
    )

    /** Global search overlay; the top bar's search field drives it. */
    var searchOpen by remember { mutableStateOf(false) }

    // Export plumbing. The saver owns the two CreateDocument launchers and the
    // bytes waiting for a destination; the scope is for the one export whose
    // payload has to come out of Room before the picker can open.
    val scope = rememberCoroutineScope()
    val documentSaver = rememberDocumentSaver { message ->
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    val exportFailed: (Throwable) -> Unit = { e ->
        Toast.makeText(
            context,
            "Could not build the export: ${e.message ?: "unknown error"}",
            Toast.LENGTH_LONG,
        ).show()
    }

    /** Analytics global filter sheet. */
    var filterOpen by remember { mutableStateOf(false) }
    // The scrolling content below is the haze source; the app bar is the haze child.
    val hazeState = rememberHazeState()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val selected = Destination.fromRoute(backStackEntry?.destination?.route)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Mlx.Screen,
        topBar = {
            Column(Modifier.statusBarsPadding()) {
                MlxTopBar(
                    query = query,
                    onQueryChange = {
                        query = it
                        searchVm.search(it)
                        searchOpen = true
                    },
                    onSearchClick = { searchOpen = true },
                    state = topBarState,
                    hazeState = hazeState,
                )
                HorizontalDivider(color = Mlx.Brand200, thickness = 1.dp)
            }
        },
        bottomBar = {
            Column {
                HorizontalDivider(color = Mlx.Brand200, thickness = 1.dp)
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
                .padding(
                    top = inner.calculateTopPadding(),
                    bottom = inner.calculateBottomPadding(),
                ),
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
                        // NO top inset here. Scaffold measures the topBar slot and
                        // reports its full height (status bar + 63dp bar + 1dp divider)
                        // through inner.calculateTopPadding(), which the Box below
                        // already applies. Adding AppBarHeight on top of that offset
                        // the content a second time and produced the uniform ~64dp
                        // white band above every card.
                        // Re-pull aggregates on entry. The ViewModels are hoisted and
                        // live across tabs, so a prescription saved on the Scan tab
                        // would otherwise not reach Analytics/Team/Hub until the app
                        // restarted.
                        LaunchedEffect(dest) {
                            when (dest) {
                                Destination.Analytics -> analyticsVm.load()
                                Destination.Team -> teamVm.load()
                                Destination.Hub -> hubVm.reload()
                                else -> Unit
                            }
                        }
                        Column(
                            Modifier.padding(
                                PaddingValues(
                                    start = MlxD.ScreenMargin,
                                    end = MlxD.ScreenMargin,
                                    bottom = MlxD.ContentBottomClearance,
                                ),
                            ),
                        ) {
                            // Dispatch on the destination itself. This used to be
                            // `when { scrollsUnderBar -> ScanScreen(...) }`, which also
                            // matched Analytics and so rendered the Scan screen on the
                            // Analytics tab - every AnalyticsScreen branch below it was
                            // unreachable.
                            when (dest) {
                                Destination.Scan -> ScanScreen(
                                    vm = scanVm,
                                    onOpenAudit = {
                                        navController.navigate(Destination.RxAudit.route)
                                    },
                                    recentRows = analyticsVm.recentPrescriptions.toRecentRxRows(),
                                    onSelectPrescription = analyticsVm::showBreakdown,
                                )
                                Destination.Analytics -> AnalyticsScreen(
                                    vm = analyticsVm,
                                    onOpenFilters = { filterOpen = true },
                                    // Already accepted by the screen and forwarded to
                                    // RecentPrescriptions, but never supplied here, so
                                    // the rows fell through to the empty default.
                                    onSelectPrescription = analyticsVm::showBreakdown,
                                    // The web navigates at /api/export/recent-medicines.csv
                                    // and lets the browser download it. There is no server
                                    // here, so the same rows are queried, serialised and
                                    // handed to the system picker.
                                    onExport = {
                                        scope.launch {
                                            runCatching { analyticsVm.buildExportCsv() }
                                                .onSuccess { csv ->
                                                    documentSaver.saveCsv(
                                                        exportFileName(
                                                            "recent_scanned_medicines",
                                                            extension = "csv",
                                                        ),
                                                        csv.toByteArray(Charsets.UTF_8),
                                                    )
                                                }
                                                .onFailure(exportFailed)
                                        }
                                    },
                                )
                                Destination.Hub -> HubScreen(
                                    vm = hubVm,
                                    onOpenJob = { url -> openUrl(context, url) },
                                    // The chamber summary, to both destinations the
                                    // web offers it from: the same body text that
                                    // becomes the PDF is what the WhatsApp message
                                    // carries, because _build_receipt_lines feeds
                                    // both there too.
                                    onShareDayPdf = { detail ->
                                        val focus = detail.brandFocus
                                        runCatching {
                                            ExportDocuments.chamberSummaryPdf(
                                                doctor = teamVm.officerProfile?.fullName.orEmpty(),
                                                ownCompany =
                                                    teamVm.officerProfile?.company.orEmpty(),
                                                // The web maps brand focus into one
                                                // medicine per brand with the other
                                                // fields blank, so "Key products
                                                // discussed" lists them.
                                                medicines = focus.map {
                                                    ExportDocuments.ReceiptMedicine(
                                                        brandName = it,
                                                    )
                                                },
                                                brandFocus = focus,
                                            )
                                        }
                                            .onSuccess { pdf ->
                                                documentSaver.savePdf(
                                                    ExportDocuments.chamberSummaryFileName(),
                                                    pdf,
                                                )
                                            }
                                            .onFailure(exportFailed)
                                    },
                                    onShareDayWhatsApp = { detail ->
                                        val focus = detail.brandFocus
                                        shareSummary(
                                            context,
                                            ExportDocuments.receiptLines(
                                                doctor =
                                                    teamVm.officerProfile?.fullName.orEmpty(),
                                                medicines = focus.map {
                                                    ExportDocuments.ReceiptMedicine(
                                                        brandName = it,
                                                    )
                                                },
                                                brandFocus = focus,
                                            ),
                                        )
                                    },
                                )
                                Destination.Team -> TeamScreen(
                                    vm = teamVm,
                                    // Purely local aggregation, so this one needs no
                                    // coroutine: the tiering and stewardship summaries are
                                    // already computed for the screen and the PDF is a
                                    // couple of pages of text.
                                    onExportPdf = {
                                        // `tiering` is a computed getter, so reading it
                                        // twice would run the tiering query twice for one
                                        // export.
                                        val tiering = teamVm.tiering
                                        runCatching {
                                            ExportDocuments.rsmReportPdf(
                                                officer = teamVm.officerProfile,
                                                tiering = tiering,
                                                stewardship = teamVm.stewardship,
                                                offTerritoryCount = teamVm.offTerritory.size,
                                                days = tiering.days,
                                            )
                                        }
                                            .onSuccess { pdf ->
                                                documentSaver.savePdf(
                                                    exportFileName(
                                                        "DGDA_Compliance_Audit",
                                                        extension = "pdf",
                                                    ),
                                                    pdf,
                                                )
                                            }
                                            .onFailure(exportFailed)
                                    },
                                )
                                Destination.Settings -> SettingsScreen(
                                    vm = settingsVm,
                                    onOpenHelp = {
                                        navController.navigate(Destination.Help.route)
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
                                bottom = MlxD.ContentBottomClearance,
                            ),
                        ),
                    ) {
                        HelpScreen(
                            vm = helpVm,
                            onTryScan = {
                                navController.navigate(Destination.Scan.route)
                            },
                        )
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
                        duplicateOfRxIds = scanVm.duplicateOfRxIds,
                        onBack = { navController.popBackStack() },
                        onPitchCard = { med ->
                            val sub = med.substitution
                            if (sub != null) {
                                pitchTarget = PitchTarget(
                                    rxId = s.receipt?.rxNumber ?: "Unsaved read",
                                    doctorName = s.doctor.name,
                                    doctorSpecialty = s.doctor.specialty,
                                    substitution = sub,
                                    compliance = PitchCompliance(
                                        nemlListed = med.neml?.listed == true,
                                        nemlMolecule = med.neml?.molecule
                                            ?.ifBlank { med.genericName }.orEmpty(),
                                        nemlClass = med.neml?.therapeuticClass.orEmpty(),
                                        dgdaFlagged = med.dgdaAlert?.flagged == true,
                                        dgdaReason = med.dgdaAlert?.reason.orEmpty(),
                                    ),
                                )
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
                        // The web downloads `/api/prescriptions/{id}/export.csv`. The
                        // string is already built by RxAudit.itemsToCsv, so this only
                        // changes where it goes.
                        onExportCsv = { csv ->
                            documentSaver.saveCsv(
                                exportFileName("rx_items", rxLabel(scanVm), extension = "csv"),
                                csv.toByteArray(Charsets.UTF_8),
                            )
                        },
                        onCopyClipboard = { text ->
                            copyToClipboard(context, text, "Market share")
                        },
                        modifier = Modifier.fillMaxSize(),
                        // The screen owns its verticalScroll, so the top offset is
                        // carried by its own padding and content passes under the bar.
                        contentPadding = PaddingValues(
                            start = MlxD.ScreenMargin,
                            end = MlxD.ScreenMargin,
                            bottom = MlxD.ContentBottomClearance,
                        ),
                    )
                }
            }

            pitchTarget?.let { target ->
                target.substitution?.let { sub ->
                    // The card's "Bioequivalence & dosage evidence" box was being fed
                    // sub.pitch, which is the pitch script — so the script rendered
                    // twice and the box said nothing about bioequivalence. The ported
                    // notes exist in Compliance; this is the one-line swap, applied to
                    // the card and the PDF together so they cannot disagree.
                    //
                    // Built here rather than inside the card so both consumers get the
                    // identical string, and memoised against the substitution because
                    // recomposition would otherwise rebuild it on every frame.
                    val notes = remember(sub) {
                        Compliance.substitutionEvidenceNotes(
                            generic = sub.generic,
                            competitorStrength = sub.competitor.strength,
                            competitorType = sub.competitor.type,
                            ownStrength = sub.ownBrand.strength,
                            ownType = sub.ownBrand.type,
                        )
                    }
                    DoctorPitchCard(
                        rxId = target.rxId,
                        doctorName = target.doctorName,
                        substitution = sub,
                        compliance = target.compliance,
                        bioequivalenceNote = notes.bioequiv + "\n" + notes.dosageAdvantage,
                        onClose = { pitchTarget = null },
                        onDownloadPdf = {
                            runCatching {
                                ExportDocuments.pitchCardPdf(
                                    rxId = target.rxId,
                                    doctorName = target.doctorName,
                                    doctorSpecialty = target.doctorSpecialty,
                                    substitution = sub,
                                    compliance = target.compliance,
                                    // Same string the card is given, so the exported PDF
                                    // and the screen it came from read identically.
                                    bioequivalenceNote = notes.bioequiv + "\n" + notes.dosageAdvantage,
                                )
                            }
                                .onSuccess { pdf ->
                                    documentSaver.savePdf(
                                        exportFileName(
                                            "Pitch_Card",
                                            sub.ownBrand.brandName,
                                            extension = "pdf",
                                        ),
                                        pdf,
                                    )
                                }
                                .onFailure(exportFailed)
                        },
                        onCopyPitch = {
                            copyToClipboard(context, sub.pitch, "Doctor pitch")
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (filterOpen) {
                FilterSheet(
                    options = analyticsVm.filterOptions,
                    initial = analyticsVm.filters,
                    onApply = {
                        analyticsVm.updateFilters(it)
                        filterOpen = false
                    },
                    onClose = { filterOpen = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (searchOpen) {
                SearchOverlay(
                    vm = searchVm,
                    onClose = { searchOpen = false },
                    modifier = Modifier.fillMaxSize(),
                )
            }

        // "Tap for item breakdown" on a Recent Prescriptions row. Rendered here rather
        // than inside the Scan screen because the prescription data belongs to the
        // analytics store, which outlives the tab the row was tapped on.
        // Open for the load and for a failed load, not only for a loaded drawer: the
        // sheet is what reports the failure, so closing it on error would leave the tap
        // with no visible outcome at all.
        if (analyticsVm.breakdownLoading || analyticsVm.breakdown != null ||
            analyticsVm.breakdownError != null
        ) {
            com.medlenx.lab.ui.screens.analytics.RxBreakdownSheet(
                drawer = analyticsVm.breakdown,
                loading = analyticsVm.breakdownLoading,
                loadError = analyticsVm.breakdownError,
                onDismiss = analyticsVm::dismissBreakdown,
                // The web downloads `/api/prescriptions/{id}/export.csv`; the string
                // comes from RxAudit.itemsToCsv, so this only changes where it goes.
                onExportCsv = { csv ->
                    documentSaver.saveCsv(
                        exportFileName(
                            "rx_items",
                            analyticsVm.breakdown?.prescription?.rxNo.orEmpty(),
                            extension = "csv",
                        ),
                        csv.toByteArray(Charsets.UTF_8),
                    )
                },
                onCopyList = { text -> copyToClipboard(context, text, "Rx items") },
                onCopyPitch = { pitch -> copyToClipboard(context, pitch, "Pitch note") },
                onPitchCard = { sub, compliance ->
                    val p = analyticsVm.breakdown?.prescription
                    pitchTarget = PitchTarget(
                        rxId = p?.rxNo ?: "Saved Rx",
                        doctorName = p?.doctorName.orEmpty(),
                        doctorSpecialty = p?.doctorSpecialty.orEmpty(),
                        substitution = sub,
                        compliance = compliance,
                    )
                },
                // The web's "Verify against Medex" opens the Drug Index tab of the Pharma
                // Intelligence Hub - not a re-scan, which would just reproduce the value
                // already on screen. Same destination the Rx Audit screen uses.
                onVerifyAgainstMedex = {
                    analyticsVm.dismissBreakdown()
                    navController.navigate(Destination.Hub.route)
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        }
    }
}

/**
 * Shares the chamber summary to WhatsApp — a native port of `receipt_whatsapp`.
 *
 * Called from the health-day campaign card, which is where the web puts this
 * button; the pitch card offers a PDF only.
 *
 * The web builds a `https://wa.me/?text=...` link and opens it in a browser tab
 * (main.py `receipt_whatsapp`). A native share intent is the equivalent and is
 * better on a phone: it opens WhatsApp's own contact picker rather than routing
 * through the browser, and it works with no network because the text is composed
 * here.
 *
 * WhatsApp is targeted directly when installed, which needs the <queries> entry
 * in the manifest on API 30+; without it resolveActivity() returns null and this
 * silently always took the chooser path. Falls back to the system share sheet
 * rather than failing, since a field officer with no WhatsApp still needs to send
 * the summary somehow.
 */
private fun shareSummary(context: Context, text: String) {
    val base = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    val direct = Intent(base).setPackage("com.whatsapp")
    val target = if (runCatching { context.packageManager.resolveActivity(direct, 0) }
            .getOrNull() != null
    ) {
        direct
    } else {
        Intent.createChooser(base, "Share chamber summary")
    }
    val started = runCatching {
        context.startActivity(target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess
    if (!started) {
        Toast.makeText(context, "No app available to share the summary", Toast.LENGTH_LONG)
            .show()
    }
}

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

/**
 * A filename stem for the Rx items export.
 *
 * Prefers the saved receipt number, because that is the identifier the rep can
 * find the prescription by later; falls back to the doctor's name so two unsaved
 * reads from different chambers do not overwrite each other.
 */
private fun rxLabel(scanVm: ScanViewModel): String =
    scanVm.state.receipt?.rxNumber?.takeIf { it.isNotBlank() }
        ?: scanVm.state.doctor.name.takeIf { it.isNotBlank() }
        ?: "items"

