package com.medlenx.lab.ui.screens.analytics

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.medlenx.lab.ui.components.ButtonTone
import com.medlenx.lab.ui.components.ConfidenceBadge
import com.medlenx.lab.ui.components.DarkHero
import com.medlenx.lab.ui.components.KpiCard
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxFilterChip
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.ProgressTrack
import com.medlenx.lab.ui.components.RegulatoryPill
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/**
 * Analytics — Figma `AnalyticsOverview` (App.tsx:1069-1211).
 *
 * Dark hero, filter bar, the four summary KPIs, the four chart widgets, the live
 * medicine feed and the recent-prescriptions list. Every figure is the export's own
 * dataset; the Room-backed aggregation that replaces it is a later step.
 */
@Composable
fun AnalyticsScreen(
    vm: AnalyticsViewModel,
    modifier: Modifier = Modifier,
    onOpenFilters: () -> Unit = {},
    onExport: () -> Unit = {},
    onSelectPrescription: (RecentRxRow) -> Unit = {},
) {
    Column(
        // This screen owns its scrolling (the NavHost must never be wrapped in one).
        //
        // No top padding: MedLenXShell's Scaffold already places this below the app
        // bar via inner.calculateTopPadding(). Padding here as well offset the hero
        // a second time, which is where the gap above "Prescription Capture" came
        // from -- not from a window inset and not from AppBarHeight.
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        HeroBanner()
        FilterBar(
            activeCount = vm.filters.activeCount,
            onOpenFilters = onOpenFilters,
            onExport = onExport,
        )

        SectionHeader(title = "Top Summary KPIs", icon = Icons.Filled.AutoGraph)
        // Two across, matching the export's `gridTemplateColumns:"1fr 1fr"`.
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            (vm.kpis?.toKpiData() ?: emptyList()).chunked(2).forEach { row ->
                // Intrinsic sizing makes both cards in a row the same height. Without
                // it each card sized to its own content, so a card with micro-pills or a
                // progress bar sat taller than its neighbour and the pair looked ragged.
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    row.forEach { kpi ->
                        KpiCard(
                            label = kpi.label,
                            value = kpi.value,
                            delta = kpi.delta,
                            deltaPositive = kpi.deltaUp,
                            microPills = listOfNotNull(
                                kpi.today?.let { "Today $it" },
                                kpi.week?.let { "Week $it" },
                                kpi.month?.let { "Month $it" },
                            ),
                            progress = kpi.track?.let { it / 100f },
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    }
                    // Odd trailing row: keep the columns equal width.
                    if (row.size == 1) Spacer1Cell()
                }
            }
        }

        MlxCard {
            SectionHeader(title = "A. Most Prescribed Medicines — Bar Chart", icon = Icons.Filled.AutoGraph)
            MostPrescribedBarChart(
                data = vm.mostPrescribed.toBarData(),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        MlxCard {
            SectionHeader(title = "B. Company Share of Voice — Donut", icon = Icons.Filled.PieChart)
            ShareOfVoiceDonut(
                data = vm.companyShare.toDonutData(),
                centreLabel = vm.companyShare.centreSoVLabel(),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        MlxCard {
            SectionHeader(title = "C. Top Doctor Prescribers — Leaderboard", icon = Icons.Filled.Person)
            Column(modifier = Modifier.padding(top = 12.dp)) {
                vm.leaders.toLeaderRows(vm.leaderOffset).forEach { doctor -> DoctorLeaderRowView(doctor) }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = vm.leaderPageLabel, style = MlxType.MicroPill, color = Mlx.Text500)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MlxButton(
                        text = "Prev",
                        tone = ButtonTone.Outline,
                        enabled = vm.canPagePrev,
                        onClick = { vm.pageLeaders(forward = false) },
                    )
                    MlxButton(
                        text = "Next",
                        tone = ButtonTone.Outline,
                        enabled = vm.canPageNext,
                        onClick = { vm.pageLeaders(forward = true) },
                    )
                }
            }
        }

        MlxCard {
            SectionHeader(
                title = "D. Generic vs Brand Matrix — Stacked Bar by Specialty",
                icon = Icons.Filled.AutoGraph,
            )
            SpecialtyStackedBarChart(
                data = vm.genericMatrix?.rows.orEmpty()
                    .map { StackedDatum(it.specialty, it.values) },
                series = vm.genericMatrix?.series.orEmpty(),
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        LiveRecentScans(
            vm = vm,
            chamberFilter = vm.chamberFilter,
            onChamberFilter = vm::updateChamberFilter,
            onExportCsv = onExport,
        )
        RecentPrescriptions(
            rows = vm.recentPrescriptions.toRecentRxRows(),
            onSelect = onSelectPrescription,
        )
    }
}

@Composable
private fun RowScope.Spacer1Cell() {
    Box(modifier = Modifier.weight(1f))
}

/**
 * The dark hero — Figma `DarkHero` plus the dashed upload banner inside it.
 *
 * The banner is a shortcut into the Scan tab; on the web it is the same drop zone.
 */
@Composable
private fun HeroBanner() {
    DarkHero {
        Text(
            text = "Prescription Capture",
            style = MlxType.HeroTitle.copy(letterSpacing = (-0.02).em),
            color = Color.White,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        Text(
            text = "Drop prescription image here or click to upload. Supports camera, rotation, contrast.",
            style = MlxType.Body,
            color = Color.White.copy(alpha = 0.8f),
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White.copy(alpha = 0.08f), MlxShape.Medium)
                .border(
                    1.dp,
                    Color.White.copy(alpha = 0.3f),
                    MlxShape.Medium,
                )
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.CloudUpload,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier
                    .padding(bottom = 4.dp)
                    .size(20.dp),
            )
            Text(
                text = "Drop prescription or click",
                style = MlxType.Body.copy(fontWeight = FontWeight.Medium),
                color = Color.White,
            )
            Text(
                text = "Full-width banner - primary action",
                style = MlxType.Meta,
                color = Color.White.copy(alpha = 0.7f),
            )
        }
    }
}

/** The filter summary card — Figma App.tsx:1085-1094. */
@Composable
private fun FilterBar(activeCount: Int, onOpenFilters: () -> Unit, onExport: () -> Unit) {
    MlxCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "FILTERS",
                style = MlxType.MicroPill.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
                color = Mlx.Text500,
            )
            Text(text = "$activeCount active", style = MlxType.MicroPill, color = Mlx.Brand400)
        }
        // The two buttons get their own row. Sharing a row with the label made them
        // compete with it for width: on a phone the row overran the card's inner
        // width, so the buttons were squeezed to different sizes and the card read as
        // crooked. Each now takes half the row, so they are always equal.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MlxButton(
                text = "Filters ($activeCount)",
                tone = ButtonTone.Primary,
                onClick = onOpenFilters,
                icon = Icons.Filled.Tune,
                modifier = Modifier.weight(1f),
            )
            MlxButton(
                text = "Export Data",
                tone = ButtonTone.Primary,
                onClick = onExport,
                icon = Icons.Filled.FileDownload,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "Dhaka South · Last 30 Days · All Specialties · All MRs",
            style = MlxType.Meta,
            color = Mlx.Brand400,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
}

/** One leaderboard row — Figma App.tsx:1157-1174. */
@Composable
private fun DoctorLeaderRowView(row: DoctorLeaderRow) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Mlx.Surface, MlxShape.Medium)
            .border(1.dp, Mlx.Brand200, MlxShape.Medium)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(Mlx.Accent50, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = row.rank.toString(),
                style = MlxType.Meta.copy(fontWeight = FontWeight.Bold),
                color = Mlx.Brand700,
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = MlxType.BodySmall.copy(fontWeight = FontWeight.SemiBold),
                color = Mlx.Text900,
            )
            Text(
                text = row.meta,
                style = MlxType.MicroPill,
                color = Mlx.Text500,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            // Share of voice drives the bar colour: >=100 green, >=50 amber, else red.
            ProgressTrack(progress = row.shareOfVoice / 100f)
        }

        Column(horizontalAlignment = Alignment.End) {
            Row {
                Text(
                    text = "${row.own}",
                    style = MlxType.Meta.copy(fontWeight = FontWeight.Bold),
                    color = Mlx.Ok600,
                )
                Text(
                    text = " / ${row.competitor}",
                    style = MlxType.Meta.copy(fontWeight = FontWeight.Bold),
                    color = Mlx.Brand400,
                )
            }
            Text(
                text = "${row.conversion}% conv",
                style = MlxType.MicroPill,
                color = if (row.conversion >= 50) Mlx.Ok500 else Mlx.Warn500,
            )
            Text(text = "${row.rxCount} Rx", style = MlxType.RegulatoryPill, color = Mlx.Brand400)
        }
    }
}

/**
 * Live medicine feed — Figma `LiveRecentScans` (App.tsx:992-1068).
 *
 * The green dot carries the `ping` animation: `scale(1) → scale(2.5)` at 60% opacity,
 * 1.5s ease-in-out, infinite (index.css `.live-dot::after`).
 */
@Composable
fun LiveRecentScans(
    vm: AnalyticsViewModel,
    modifier: Modifier = Modifier,
    chamberFilter: ChamberFilter = ChamberFilter.ALL,
    onChamberFilter: (ChamberFilter) -> Unit = {},
    onExportCsv: () -> Unit = {},
) {
    var sort by remember { mutableStateOf("Time") }

    MlxCard(modifier = modifier, padding = 20.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PingDot()
                Text(
                    text = "Live Recent Scans",
                    style = MlxType.CardTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = Mlx.Text900,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MlxButton(text = "CSV", tone = ButtonTone.Outline, onClick = onExportCsv)
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Mlx.Surface, MlxShape.Chip)
                        .border(1.dp, Mlx.Brand200, MlxShape.Chip)
                        .clickable { },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Mlx.Text600, modifier = Modifier.size(13.dp))
                }
            }
        }

        Text(
            text = "Every individual medicine detected, per MR",
            style = MlxType.MicroPill,
            color = Mlx.Text500,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "TAGS",
                style = MlxType.MicroPill.copy(fontWeight = FontWeight.Bold),
                color = Mlx.Brand400,
            )
            MlxFilterChip(
                label = "Hospital",
                selected = chamberFilter == ChamberFilter.HOSPITAL,
                onClick = { onChamberFilter(ChamberFilter.HOSPITAL) },
            )
            MlxFilterChip(
                label = "Private Chamber",
                selected = chamberFilter == ChamberFilter.PRIVATE,
                onClick = { onChamberFilter(ChamberFilter.PRIVATE) },
            )
        }

        Row(
            modifier = Modifier.padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LiveScanSortColumns.forEach { column ->
                val active = column == sort
                Box(
                    modifier = Modifier
                        .background(if (active) Mlx.Brand900 else Mlx.Surface, MlxShape.Pill)
                        .border(1.dp, if (active) Mlx.Brand900 else Mlx.Brand200, MlxShape.Pill)
                        .clickable { sort = column }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        text = "$column ${if (active) "\u2191" else "\u2195"}",
                        style = MlxType.MicroPill,
                        color = if (active) Color.White else Mlx.Text600,
                    )
                }
            }
        }

        vm.livePage.toLiveScanRows().forEach { scan -> LiveScanRowView(scan) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Showing ${vm.livePageLabel}",
                style = MlxType.Meta,
                color = Mlx.Text500,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MlxButton(
                    text = "Prev",
                    tone = ButtonTone.Outline,
                    enabled = vm.canLivePrev,
                    onClick = { vm.pageLiveScans(forward = false) },
                )
                MlxButton(
                    text = "Next",
                    tone = ButtonTone.Outline,
                    enabled = vm.canLiveNext,
                    onClick = { vm.pageLiveScans(forward = true) },
                )
            }
        }
    }
}

/** 8dp emerald dot with the CSS `ping` halo, reproduced as a scaled fading overlay. */
@Composable
private fun PingDot(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "ping")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 2.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500),
            repeatMode = RepeatMode.Restart,
        ),
        label = "pingScale",
    )
    Box(modifier = modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .scale(scale)
                .background(Mlx.Ok400.copy(alpha = 0.6f), CircleShape),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(Mlx.Ok400, CircleShape),
        )
    }
}

@Composable
private fun LiveScanRowView(scan: LiveScanRow) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .background(Mlx.Surface, MlxShape.Medium)
            .border(1.dp, Mlx.Brand100, MlxShape.Medium)
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            // The export splits the brand into its first word and the remainder.
            val parts = scan.brand.split(" ", limit = 2)
            Row {
                Text(
                    text = parts[0] + " ",
                    style = MlxType.Body.copy(fontWeight = FontWeight.SemiBold),
                    color = Mlx.Text900,
                )
                Text(
                    text = parts.getOrElse(1) { "" },
                    style = MlxType.MicroPill,
                    color = Mlx.Text500,
                )
            }
            ConfidenceBadge(percent = scan.confidence)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (scan.verified) Mlx.Ok400 else Mlx.Amber500,
                            CircleShape,
                        ),
                )
                // The export truncates company names past 22 characters.
                Text(
                    text = if (scan.company.length > 22) scan.company.take(22) + "…" else scan.company,
                    style = MlxType.MicroPill,
                    color = Mlx.Text600,
                )
                Text(
                    text = if (scan.verified) "\u2713" else "\u26A0",
                    style = MlxType.MicroPill,
                    color = Mlx.Text600,
                )
            }
            StatusPill(text = scan.specialty, tone = PillTone.Slate)
        }

        Text(
            text = "${scan.doctor} · ${scan.time} · ${scan.location}",
            style = MlxType.MicroPill,
            color = Mlx.Text500,
        )
    }
}

/**
 * Recent prescriptions — Figma `RecentPrescriptions` (App.tsx:748-777).
 *
 * Shared with `ScanSavedSection`, which is why it lives here rather than in the scan
 * package. Empty state text is the export's own.
 */
@Composable
fun RecentPrescriptions(
    modifier: Modifier = Modifier,
    rows: List<RecentRxRow>,
    onSelect: (RecentRxRow) -> Unit = {},
) {
    MlxCard(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Recent Prescriptions",
                style = MlxType.CardTitle.copy(fontWeight = FontWeight.SemiBold),
                color = Mlx.Text900,
            )
            StatusPill(
                text = "Refresh",
                tone = PillTone.Slate,
                icon = Icons.Filled.Refresh,
            )
        }

        if (rows.isEmpty()) {
            MlxEmptyState(
                message = "No history yet — scan your first prescription to unlock analytics",
                icon = Icons.Filled.Medication,
            )
        } else {
            rows.forEach { rx ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .background(Mlx.Surface, MlxShape.Medium)
                        .border(1.dp, Mlx.Brand200, MlxShape.Medium)
                        .clickable { onSelect(rx) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Mlx.Brand100, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Medication,
                            contentDescription = null,
                            tint = Mlx.Text500,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "${rx.doctor} • ${rx.bmdc}",
                                style = MlxType.BodySmall.copy(fontWeight = FontWeight.Medium),
                                color = Mlx.Text900,
                            )
                            if (rx.duplicate) {
                                RegulatoryPill(text = "Duplicate Rx Detected", tone = PillTone.Red)
                            }
                        }
                        Text(
                            text = "${rx.meds} meds • ${rx.area} • ${rx.mr} • tap for item breakdown",
                            style = MlxType.MicroPill,
                            color = Mlx.Text500,
                        )
                    }
                    Text(text = "\u203A", style = MlxType.CardTitle, color = Mlx.Brand300)
                }
            }
        }
    }
}
