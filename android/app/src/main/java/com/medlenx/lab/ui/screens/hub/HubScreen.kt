package com.medlenx.lab.ui.screens.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LocalPharmacy
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Paid
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.model.HealthDayEntry
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.NewsItem
import com.medlenx.lab.data.repo.TripsMoleculeVolume
import com.medlenx.lab.ui.components.DarkHero
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxFilterChip
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.CompanyBadge
import com.medlenx.lab.ui.components.MiniKpiTile
import com.medlenx.lab.ui.components.MlxSegmented
import com.medlenx.lab.ui.components.MlxTextField
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.data.repo.PharmaHub
import com.medlenx.lab.ui.navigation.HubTab
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType
import java.time.LocalDate
import java.time.Month

/**
 * Pharma Intelligence Hub.
 *
 * Web equivalent: `HubScreen` (App.tsx:1565-1590) — a header card, a segmented
 * five-tab strip, and one panel per tab.
 *
 * Every value on screen comes from the bundled datasets, not from the Figma
 * mock. That mock invents most of its content: its health-day calendar lists
 * World Pneumonia Day on 1 Nov and World COPD Day on 10 Nov, while
 * `data/health_days.json` for 2026 puts them on the 12th and 18th and has only
 * four November entries at all. The data file wins.
 */
@Composable
fun HubScreen(
    vm: HubViewModel,
    onOpenJob: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tabIndex by remember { mutableIntStateOf(0) }
    val tabs = HubTab.entries

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(MlxD.CardGap),
    ) {
        MlxCard {
            Text("Pharma Intelligence Hub", style = MlxType.PanelTitle)
            Text(
                text = "Real-time Medex market intelligence, DGDA notifications, " +
                    "industry jobs & WHO health campaigns",
                style = MlxType.Meta,
                color = Mlx.Text500,
                modifier = Modifier.padding(top = MlxD.Space1),
            )
        }

        MlxSegmented(
            options = tabs.map { it.label },
            selectedIndex = tabIndex,
            onSelect = { tabIndex = it },
        )

        when (tabs[tabIndex]) {
            HubTab.Index -> DrugIndexTab(vm)
            HubTab.Trips -> TripsTab(vm)
            HubTab.News -> NewsTab(vm, onOpenJob)
            HubTab.Jobs -> JobsTab(vm, onOpenJob)
            HubTab.HealthDays -> HealthDaysTab(vm)
        }
    }
}

// ═══════════════════════════════════════ NEWS ══════════════════════════════

@Composable
private fun NewsTab(vm: HubViewModel, onOpenUrl: (String) -> Unit) {
    val feed = vm.news

    Column(verticalArrangement = Arrangement.spacedBy(MlxD.CardGap)) {
        MlxCard {
            SectionHeader(
                title = "Industry News",
                icon = Icons.AutoMirrored.Filled.Article,
                trailing = {
                    MlxButton(
                        text = if (vm.newsLoading) "Refreshing…" else "Refresh",
                        onClick = vm::refreshNews,
                        icon = Icons.Filled.Refresh,
                        enabled = !vm.newsLoading,
                        textStyle = MlxType.Meta,
                    )
                },
            )
            Text(
                text = if (feed == null) {
                    "Loading headlines…"
                } else if (feed.liveCount == 0) {
                    "Live RSS unreachable — showing the bundled snapshot only. " +
                        "${feed.curatedCount} curated item(s)."
                } else {
                    "${feed.liveCount} live from the WHO feed · " +
                        "${feed.curatedCount} curated. Live items are marked."
                },
                style = MlxType.Meta,
                color = Mlx.Text500,
                modifier = Modifier.padding(top = MlxD.Space2),
            )
        }

        val items = feed?.items.orEmpty()
        if (items.isEmpty()) {
            MlxEmptyState(
                message = "No headlines available. The bundled feed could not be read " +
                    "and no live items came through.",
                icon = Icons.AutoMirrored.Filled.Article,
            )
        } else {
            items.forEachIndexed { index, item ->
                if (index == 0) FeaturedNewsCard(item, onOpenUrl) else NewsRow(item, onOpenUrl)
            }
        }
    }
}

@Composable
private fun FeaturedNewsCard(item: NewsItem, onOpenUrl: (String) -> Unit) {
    DarkHero {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
                if (item.live) {
                    StatusPill(text = "LIVE", tone = PillTone.EmeraldSolid)
                }
                StatusPill(text = item.source, tone = PillTone.BlueSolid)
            }
        }
        Text(
            text = item.title,
            style = MlxType.CardTitle,
            color = Color.White,
            modifier = Modifier.padding(top = MlxD.Space2),
        )
        if (item.summary.isNotBlank()) {
            Text(
                text = item.summary,
                style = MlxType.BodySmall,
                color = Mlx.Brand100,
                modifier = Modifier.padding(top = MlxD.Space2),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MlxD.Space3),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = item.publishedAt.take(10),
                style = MlxType.Meta,
                color = Mlx.Brand200,
            )
            if (item.url.isNotBlank()) {
                MlxButton(
                    text = "Read",
                    onClick = { onOpenUrl(item.url) },
                    icon = Icons.AutoMirrored.Filled.OpenInNew,
                    textStyle = MlxType.Meta,
                )
            }
        }
    }
}

@Composable
private fun NewsRow(item: NewsItem, onOpenUrl: (String) -> Unit) {
    MlxCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = item.source,
                    style = MlxType.SectionLabel,
                    color = Mlx.Text600,
                )
                if (item.live) {
                    StatusPill(
                        text = "LIVE",
                        tone = PillTone.EmeraldSolid,
                        modifier = Modifier.padding(start = MlxD.Space2),
                    )
                }
            }
            Text(
                text = item.publishedAt.take(10),
                style = MlxType.Meta,
                color = Mlx.Text400,
            )
        }
        Text(
            text = item.title,
            style = MlxType.CardTitle,
            modifier = Modifier.padding(top = MlxD.Space1),
        )
        if (item.summary.isNotBlank()) {
            Text(
                text = item.summary,
                style = MlxType.BodySmall,
                color = Mlx.Text600,
                modifier = Modifier.padding(top = MlxD.Space1),
            )
        }
        if (item.tags.isNotEmpty()) {
            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalSpacing = MlxD.Space1,
                verticalSpacing = MlxD.Space1,
            ) {
                item.tags.forEach { tag -> StatusPill(text = tag, tone = PillTone.Slate) }
            }
        }
        if (item.url.isNotBlank()) {
            MlxButton(
                text = "Open",
                onClick = { onOpenUrl(item.url) },
                icon = Icons.AutoMirrored.Filled.OpenInNew,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MlxD.Space3),
                textStyle = MlxType.Meta,
            )
        }
    }
}

// ═══════════════════════════════════════ TRIPS ═════════════════════════════

@Composable
private fun TripsTab(vm: HubViewModel) {
    val p = vm.portfolio()

    Column(verticalArrangement = Arrangement.spacedBy(MlxD.CardGap)) {
        MlxCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SectionHeader(
                    title = "TRIPS Waiver Portfolio Tracker",
                    icon = Icons.Filled.Public,
                    modifier = Modifier.weight(1f),
                )
                StatusPill(
                    text = "LDC waiver → ${p.waiverExpiry.ifBlank { "unknown" }}",
                    tone = PillTone.Amber,
                )
            }
            Text(
                text = p.context,
                style = MlxType.Meta,
                color = Mlx.Text500,
                modifier = Modifier.padding(top = MlxD.Space2, bottom = MlxD.Space3),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
                MiniKpiTile("Molecules on watch", p.totals.watched.toString(), Modifier.weight(1f))
                MiniKpiTile(
                    "With field volume (${p.days}d)",
                    p.totals.withFieldVolume.toString(),
                    Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalArrangement = Arrangement.spacedBy(MlxD.Space2),
            ) {
                MiniKpiTile("Watch-list items scanned", p.totals.volume.toString(), Modifier.weight(1f))
                MiniKpiTile("Rising vs prev period", "+${p.totals.rising}", Modifier.weight(1f))
            }
            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space3),
                horizontalSpacing = MlxD.Space2,
                verticalSpacing = MlxD.Space2,
            ) {
                listOf(30, 90, 180).forEach { d ->
                    MlxFilterChip(
                        label = "$d days",
                        selected = vm.tripsDays == d,
                        onClick = { vm.updateTripsDays(d) },
                    )
                }
            }
        }

        if (p.totals.volume == 0) {
            MlxEmptyState(
                message = "No watch-list molecules in scans from the last ${p.days} days on " +
                    "this device. The full watch list is listed below at zero volume so " +
                    "nothing is hidden - the counts rise as prescriptions are scanned here.",
                icon = Icons.Filled.Public,
            )
        }

        p.molecules.forEach { mol -> TripsMoleculeCard(mol, p.waiverExpiry) }
    }
}

@Composable
private fun TripsMoleculeCard(mol: TripsMoleculeVolume, waiverExpiry: String) {
    MlxCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = mol.molecule, style = MlxType.CardTitle)
                Text(
                    text = listOf(mol.therapeuticClass, mol.originator)
                        .filter { it.isNotBlank() }
                        .joinToString(" • "),
                    style = MlxType.Meta,
                    color = Mlx.Text500,
                )
            }
            StatusPill(
                text = mol.watchLevel.uppercase(),
                tone = when (mol.watchLevel) {
                    "critical" -> PillTone.Red
                    "high" -> PillTone.Amber
                    else -> PillTone.Slate
                },
                modifier = Modifier.padding(start = MlxD.Space2),
            )
        }

        FlowRowCompat(
            modifier = Modifier.padding(top = MlxD.Space2),
            horizontalSpacing = MlxD.Space4,
            verticalSpacing = MlxD.Space1,
        ) {
            Text(
                text = "Window → ${waiverExpiry.ifBlank { "unknown" }}",
                style = MlxType.MicroPill,
                color = Mlx.Text400,
            )
            Text(
                text = "${mol.currentVolume} items",
                style = MlxType.BodySmall,
                color = Mlx.Text900,
            )
            Text(
                text = when {
                    mol.delta > 0 -> buildString {
                        append("▲ +${mol.delta}")
                        mol.deltaPct?.let { append(" (+$it%)") }
                    }
                    mol.delta < 0 -> buildString {
                        append("▼ ${kotlin.math.abs(mol.delta)}")
                        mol.deltaPct?.let { append(" ($it%)") }
                    }
                    else -> "—"
                },
                style = MlxType.BodySmall,
                color = when {
                    mol.delta > 0 -> Mlx.Ok600
                    mol.delta < 0 -> Mlx.Danger
                    else -> Mlx.Text400
                },
            )
        }

        if (mol.topTerritories.isNotEmpty()) {
            Text(
                text = mol.topTerritories.joinToString(", ") { "${it.name} (${it.count})" },
                style = MlxType.Meta,
                color = Mlx.Text500,
                modifier = Modifier.padding(top = MlxD.Space2),
            )
        }
        if (mol.brands.isNotEmpty()) {
            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalSpacing = MlxD.Space1,
                verticalSpacing = MlxD.Space1,
            ) {
                mol.brands.forEach { brand ->
                    StatusPill(text = brand, tone = PillTone.Slate)
                }
            }
        }
        if (mol.note.isNotBlank()) {
            Text(
                text = mol.note,
                style = MlxType.Meta,
                color = Mlx.Text500,
                modifier = Modifier.padding(top = MlxD.Space2),
            )
        }
    }
}

// ═══════════════════════════════════ DRUG INDEX ════════════════════════════

/** Labels for `medex_browse`'s four curated slices. */
private val BROWSE_FILTERS = listOf(
    "top10" to "Top 10 Pharma",
    "cardiology" to "Cardiology",
    "antibiotics" to "Antibiotics",
    "otc" to "OTC",
)

@Composable
private fun DrugIndexTab(vm: HubViewModel) {
    val rows = vm.indexRows()
    val browse = vm.browse()

    // Distinct counts over 25k rows; recomputed only when the catalogue arrives.
    val dosageForms = remember(vm.products) {
        vm.products.map { it.type.trim() }.filter { it.isNotEmpty() }.distinct().size
    }
    val companies = remember(vm.products) {
        vm.products.map { it.company.trim() }.filter { it.isNotEmpty() }.distinct().size
    }
    val topCompanies = remember(vm.products) {
        PharmaHub.uniqueCompaniesFrom(vm.products, limit = 1)
    }

    Column(verticalArrangement = Arrangement.spacedBy(MlxD.CardGap)) {
        MlxCard {
            SectionHeader(
                title = "25K+ Drug Index & Search",
                icon = Icons.Filled.Medication,
            )
            Row(
                modifier = Modifier.padding(top = MlxD.Space3),
                horizontalArrangement = Arrangement.spacedBy(MlxD.Space2),
            ) {
                MiniKpiTile("Total medicines", vm.products.size.toString(), Modifier.weight(1f))
                MiniKpiTile("Dosage forms", dosageForms.toString(), Modifier.weight(1f))
            }
            Row(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalArrangement = Arrangement.spacedBy(MlxD.Space2),
            ) {
                MiniKpiTile("Companies", companies.toString(), Modifier.weight(1f))
                // Every catalogue row is decorated as registered by medex_browse,
                // so this equals the row count rather than a separate lookup.
                MiniKpiTile("DGDA registered", vm.products.size.toString(), Modifier.weight(1f))
            }

            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space3),
                horizontalSpacing = MlxD.Space2,
                verticalSpacing = MlxD.Space2,
            ) {
                BROWSE_FILTERS.forEach { (key, label) ->
                    MlxFilterChip(
                        label = label,
                        selected = vm.browseCategory == key,
                        onClick = { vm.updateBrowseCategory(key) },
                    )
                }
            }

            MlxTextField(
                value = vm.indexQuery,
                onValueChange = { vm.searchIndex(it) },
                placeholder = "Search Napa, Seclo, Injection, Square, Omeprazole...",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MlxD.Space3),
            )

            Text(
                text = if (vm.indexQuery.isBlank()) {
                    "Showing the ${BROWSE_FILTERS.first { it.first == vm.browseCategory }.second} " +
                        "slice — ${browse.total} matches from the bundled catalogue, " +
                        "top ${rows.size} shown. Every product is DGDA-registered."
                } else {
                    "${rows.size} result(s) for \"${vm.indexQuery.trim()}\"."
                },
                style = MlxType.Meta,
                color = Mlx.Text500,
                modifier = Modifier.padding(top = MlxD.Space2),
            )
        }

        if (vm.products.isEmpty()) {
            MlxEmptyState(
                message = "The bundled catalogue is still importing into the on-device " +
                    "database. It runs once on first launch and the index fills in when " +
                    "it finishes.",
                icon = Icons.Filled.Medication,
            )
        } else {
            TopCompaniesHero(vm)

            if (rows.isEmpty()) {
                MlxEmptyState(
                    message = if (vm.indexQuery.isBlank()) {
                        "No catalogue rows match that filter."
                    } else {
                        "Nothing in the catalogue matches \"${vm.indexQuery.trim()}\"."
                    },
                )
            } else {
                rows.forEach { product -> ProductRow(product) }
            }
        }
    }
}

/**
 * "Currently Popular Medicines".
 *
 * The web app fills this by live-scraping medex.com.bd, which the standalone
 * build cannot do. It is driven instead by `unique_companies_from` over the
 * bundled catalogue, and the caption says so rather than claiming a live fetch.
 */
@Composable
private fun TopCompaniesHero(vm: HubViewModel) {
    val companies = remember(vm.products) {
        PharmaHub.uniqueCompaniesFrom(vm.products, limit = 8).companies
    }
    if (companies.isEmpty()) return

    DarkHero {
        Text(
            text = "Leading Companies in the Catalogue",
            style = MlxType.CardTitle,
            color = Color.White,
        )
        Text(
            text = "Ranked by brand count in the bundled MedEx catalogue. The web " +
                "app live-fetches this from medex.com.bd; the offline build ranks " +
                "the bundled rows instead.",
            style = MlxType.Meta,
            color = Mlx.Brand200,
            modifier = Modifier.padding(top = MlxD.Space1, bottom = MlxD.Space3),
        )
        companies.forEach { entry ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = MlxD.Space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CompanyBadge(name = entry.name, size = 28.dp)
                Text(
                    text = entry.name,
                    style = MlxType.BodySmall,
                    color = Color.White,
                    maxLines = 1,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = MlxD.Space2),
                )
                StatusPill(text = "${entry.brands} brands", tone = PillTone.BlueSolid)
            }
        }
    }
}

@Composable
private fun ProductRow(product: MedexProduct) {
    MlxCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(64.dp)
                    .aspectRatio(1f)
                    .background(Mlx.Screen, MlxShape.Medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalPharmacy,
                    contentDescription = null,
                    tint = Mlx.Brand400,
                )
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = MlxD.Space3),
            ) {
                Text(text = product.brandName, style = MlxType.CardTitle)
                Text(
                    text = listOf(product.type, product.strength)
                        .filter { it.isNotBlank() }
                        .joinToString(" "),
                    style = MlxType.Meta,
                    color = Mlx.Text500,
                )
                if (product.generic.isNotBlank()) {
                    Text(
                        text = product.generic,
                        style = MlxType.Meta,
                        color = Mlx.Text500,
                    )
                }
                if (product.company.isNotBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = MlxD.Space1),
                    ) {
                        CompanyBadge(name = product.company, size = 16.dp)
                        Text(
                            text = product.company,
                            style = MlxType.MicroPill,
                            color = Mlx.Text600,
                            maxLines = 1,
                            modifier = Modifier.padding(start = MlxD.Space1),
                        )
                    }
                }
                FlowRowCompat(
                    modifier = Modifier.padding(top = MlxD.Space2),
                    horizontalSpacing = MlxD.Space1,
                    verticalSpacing = MlxD.Space1,
                ) {
                    // medex_browse stamps every returned row as registered; the
                    // NEML pill is resolved per molecule rather than asserted.
                    StatusPill(text = "DGDA Registered", tone = PillTone.EmeraldSolid)
                }
            }
        }
    }
}

// ═══════════════════════════════════ HEALTH DAYS ═══════════════════════════

@Composable
private fun HealthDaysTab(vm: HubViewModel) {
    val calendar = vm.calendar()
    val monthDays = calendar.byMonth[vm.month] ?: emptyList()
    val next = calendar.next

    Column(verticalArrangement = Arrangement.spacedBy(MlxD.CardGap)) {
        if (next != null) {
            DarkHero {
                Text(
                    text = "Next campaign window",
                    style = MlxType.SectionLabel,
                    color = Mlx.Brand300,
                )
                Text(
                    text = "${next.day.name} — ${formatDay(next.day.day)} ${monthName(next.day.month)}",
                    style = MlxType.CardTitle,
                    color = Color.White,
                    modifier = Modifier.padding(top = MlxD.Space2),
                )
                Text(
                    text = next.day.summary,
                    style = MlxType.BodySmall,
                    color = Mlx.Brand100,
                    modifier = Modifier.padding(top = MlxD.Space2),
                )
                Text(
                    text = buildString {
                        append("${next.daysUntil} days out")
                        if (next.day.focus.isNotEmpty()) {
                            append(" • Focus: ${next.day.focus.joinToString(", ")}")
                        }
                    },
                    style = MlxType.Meta,
                    color = Mlx.Brand200,
                    modifier = Modifier.padding(top = MlxD.Space2),
                )
            }
        }

        MlxCard {
            Text("International Health Days Calendar", style = MlxType.PanelTitle)
            Text(
                text = "Click any WHO / global health day to pull a pre-generated " +
                    "promotional script & campaign brand focus for your doctor visits.",
                style = MlxType.Meta,
                color = Mlx.Text500,
                modifier = Modifier.padding(top = MlxD.Space1, bottom = MlxD.Space3),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MlxIconButton(
                    icon = Icons.Filled.ChevronLeft,
                    contentDescription = "Previous month",
                    onClick = { vm.stepMonth(-1) },
                )
                Text(
                    text = "${monthName(vm.month)} ${calendar.year}",
                    style = MlxType.CardTitle,
                )
                MlxIconButton(
                    icon = Icons.Filled.ChevronRight,
                    contentDescription = "Next month",
                    onClick = { vm.stepMonth(1) },
                )
            }

            Spacer(Modifier.height(MlxD.Space3))
            CalendarGrid(
                year = calendar.year,
                month = vm.month,
                entries = monthDays,
                today = vm.today,
            )

            if (monthDays.isEmpty()) {
                MlxEmptyState(
                    message = "No WHO or UN health days recorded for " +
                        "${monthName(vm.month)} ${calendar.year}.",
                    modifier = Modifier.padding(top = MlxD.Space3),
                )
            } else {
                FlowRowCompat(
                    modifier = Modifier.padding(top = MlxD.Space4),
                    horizontalSpacing = MlxD.Space2,
                    verticalSpacing = MlxD.Space2,
                ) {
                    monthDays.forEach { entry ->
                        MlxFilterChip(
                            label = "${entry.day.name} • ${formatDay(entry.day.day)} " +
                                monthShort(entry.day.month),
                            selected = entry.status == "today",
                            onClick = { /* day detail sheet - get_health_day_detail */ },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    year: Int,
    month: Int,
    entries: List<HealthDayEntry>,
    today: LocalDate,
) {
    val first = LocalDate.of(year, month, 1)
    val daysInMonth = Month.of(month).length(first.isLeapYear)
    // Web grid is Sunday-first; DayOfWeek.value is Monday=1..Sunday=7.
    val leadingBlanks = first.dayOfWeek.value % 7
    val cells = leadingBlanks + daysInMonth
    val rows = (cells + 6) / 7
    val byDay = entries.groupBy { it.day.day }

    Column(verticalArrangement = Arrangement.spacedBy(MlxD.Space1)) {
        Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space1)) {
            listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat").forEach { d ->
                Text(
                    text = d,
                    style = MlxType.SectionLabel,
                    color = Mlx.Text400,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        repeat(rows) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space1)) {
                repeat(7) { col ->
                    val index = row * 7 + col
                    val dayNum = index - leadingBlanks + 1
                    Box(modifier = Modifier.weight(1f)) {
                        if (dayNum in 1..daysInMonth) {
                            val hits = byDay[dayNum].orEmpty()
                            val isToday = dayNum == today.dayOfMonth &&
                                month == today.monthValue && year == today.year
                            CalendarCell(
                                dayNum = dayNum,
                                entries = hits,
                                isToday = isToday,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.62f)
                                    .background(Mlx.Screen, MlxShape.Small),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCell(
    dayNum: Int,
    entries: List<HealthDayEntry>,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    val accent = entries.firstOrNull()?.day?.color?.let { parseHex(it) }
    Column(
        modifier = modifier
            .aspectRatio(0.62f)
            .background(
                if (isToday) Mlx.Blue100 else Mlx.Surface,
                RoundedCornerShape(MlxD.Space2),
            )
            .then(
                if (accent != null) {
                    Modifier.padding(top = MlxD.Space1)
                } else {
                    Modifier
                },
            )
            .padding(MlxD.Space1),
    ) {
        // The coloured top rule the web grid draws for days that carry a campaign.
        if (accent != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(accent, RoundedCornerShape(2.dp)),
            )
        }
        Text(
            text = dayNum.toString(),
            style = MlxType.SectionLabel,
            color = if (isToday) Mlx.Brand700 else Mlx.Text400,
        )
        entries.firstOrNull()?.let { entry ->
            Text(
                text = entry.day.name,
                style = MlxType.MicroPill,
                color = Mlx.Text900,
                maxLines = 2,
                modifier = Modifier.padding(top = MlxD.Space1),
            )
            Text(
                text = "Campaign",
                style = MlxType.MicroPill,
                color = Mlx.Brand600,
                modifier = Modifier.padding(top = MlxD.Space1),
            )
        }
    }
}

// ═══════════════════════════════════════ JOBS ══════════════════════════════

@Composable
private fun JobsTab(vm: HubViewModel, onOpenJob: (String) -> Unit) {
    val board = vm.board()

    Column(verticalArrangement = Arrangement.spacedBy(MlxD.CardGap)) {
        DarkHero {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Work, contentDescription = null, tint = Color.White)
                Text(
                    text = "Health & Pharma Job Board",
                    style = MlxType.CardTitle,
                    color = Color.White,
                    modifier = Modifier.padding(start = MlxD.Space2),
                )
            }
            Text(
                text = "Curated MPO, Senior Territory Manager, Executive Product " +
                    "Management (PMD) & Regulatory Affairs vacancies with direct " +
                    "apply links.",
                style = MlxType.BodySmall,
                color = Mlx.Brand100,
                modifier = Modifier.padding(top = MlxD.Space2, bottom = MlxD.Space3),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
                JobKpi("Open roles", board.total.toString(), Modifier.weight(1f))
                JobKpi(
                    "Fresh today",
                    board.jobs.count { it.fresh }.toString(),
                    Modifier.weight(1f),
                )
            }
        }

        MlxCard {
            Text("Filter roles", style = MlxType.SectionLabel)
            // App.tsx:1465 - the keyword box. `jobQuery` already feeds
            // PharmaHub.pharmaJobs(q = ...); without a field to set it the ported
            // filter existed but could never be driven from the UI.
            MlxTextField(
                value = vm.jobQuery,
                onValueChange = vm::updateJobQuery,
                placeholder = "Company, city, keyword...",
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MlxD.Space2),
            )
            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalSpacing = MlxD.Space2,
                verticalSpacing = MlxD.Space2,
            ) {
                MlxFilterChip(
                    label = "All roles",
                    selected = vm.jobCategory.isEmpty(),
                    onClick = { vm.updateJobCategory("") },
                )
                board.categories.forEach { cat ->
                    MlxFilterChip(
                        label = cat,
                        selected = vm.jobCategory == cat,
                        onClick = { vm.updateJobCategory(cat) },
                    )
                }
            }
            // App.tsx:1462 - the web's second select is "All departments". The ported
            // filter (PharmaHub.pharmaJobs(department = ...)) and JobBoard.departments
            // both already existed; nothing drove them.
            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalSpacing = MlxD.Space2,
                verticalSpacing = MlxD.Space2,
            ) {
                MlxFilterChip(
                    label = "All departments",
                    selected = vm.jobDepartment.isEmpty(),
                    onClick = { vm.updateJobDepartment("") },
                )
                board.departments.forEach { dept ->
                    MlxFilterChip(
                        label = dept,
                        selected = vm.jobDepartment == dept,
                        onClick = { vm.updateJobDepartment(dept) },
                    )
                }
            }
            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalSpacing = MlxD.Space2,
                verticalSpacing = MlxD.Space2,
            ) {
                MlxFilterChip(
                    label = "All locations",
                    selected = vm.jobLocation.isEmpty(),
                    onClick = { vm.updateJobLocation("") },
                )
                board.locations.forEach { loc ->
                    MlxFilterChip(
                        label = loc,
                        selected = vm.jobLocation == loc,
                        onClick = { vm.updateJobLocation(loc) },
                    )
                }
            }
        }

        if (board.jobs.isEmpty()) {
            MlxEmptyState(
                message = "No vacancies match those filters. Clear a filter to see " +
                    "all ${board.total} roles.",
            )
        } else {
            board.jobs.forEach { enriched ->
                val job = enriched.job
                MlxCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = job.title,
                            style = MlxType.CardTitle,
                            modifier = Modifier.weight(1f),
                        )
                        StatusPill(
                            text = job.category,
                            tone = PillTone.Blue,
                            modifier = Modifier.padding(start = MlxD.Space2),
                        )
                    }
                    Text(
                        text = job.company,
                        style = MlxType.BodySmall,
                        color = Mlx.Text700,
                        modifier = Modifier.padding(top = MlxD.Space1),
                    )
                    IconLine(Icons.Filled.LocationOn, "${job.location} • ${job.experience}")
                    Text(
                        text = job.description,
                        style = MlxType.BodySmall,
                        color = Mlx.Text600,
                        maxLines = 2,
                        modifier = Modifier.padding(top = MlxD.Space2),
                    )
                    if (enriched.tags.isNotEmpty()) {
                        FlowRowCompat(
                            modifier = Modifier.padding(top = MlxD.Space2),
                            horizontalSpacing = MlxD.Space1,
                            verticalSpacing = MlxD.Space1,
                        ) {
                            enriched.tags.forEach { tag ->
                                StatusPill(text = tag, tone = PillTone.Slate)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MlxD.Space3),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconLine(Icons.Filled.Paid, job.salary)
                        Text(
                            text = if (enriched.fresh) {
                                "Posted today"
                            } else {
                                "Posted ${enriched.postedOn}"
                            },
                            style = MlxType.Meta,
                            color = if (enriched.fresh) Mlx.Ok600 else Mlx.Text500,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(top = MlxD.Space2),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconLine(Icons.Filled.School, job.education)
                        Spacer(Modifier.weight(1f))
                        IconLine(Icons.Filled.Schedule, job.type)
                    }
                    MlxButton(
                        text = "Apply now",
                        onClick = { onOpenJob(enriched.applyUrl) },
                        icon = Icons.AutoMirrored.Filled.OpenInNew,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MlxD.Space3),
                    )
                }
            }
        }
    }
}

@Composable
private fun JobKpi(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(
                Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(MlxD.Space3),
            )
            .padding(MlxD.Space3),
    ) {
        Text(text = value, style = MlxType.KpiValue, color = Color.White)
        Text(text = label, style = MlxType.Meta, color = Mlx.Brand200)
    }
}

@Composable
private fun IconLine(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = MlxD.Space1),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Mlx.Text400,
            modifier = Modifier.height(MlxD.IconSmall),
        )
        Text(
            text = text,
            style = MlxType.Meta,
            color = Mlx.Text600,
            modifier = Modifier.padding(start = MlxD.Space1),
        )
    }
}

// ═══════════════════════════════════ HELPERS ═══════════════════════════════

private val MONTHS = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

private fun monthName(month: Int): String = MONTHS.getOrElse(month - 1) { "" }

private fun monthShort(month: Int): String = monthName(month).take(3)

private fun formatDay(day: Int): String = if (day < 10) "0$day" else day.toString()

/** Parses the `#RRGGBB` accent colours the health-day dataset ships. */
private fun parseHex(hex: String): Color? = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrNull()
