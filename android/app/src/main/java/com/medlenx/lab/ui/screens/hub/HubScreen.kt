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
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Paid
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
import com.medlenx.lab.ui.components.DarkHero
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxFilterChip
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.MlxSegmented
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
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
            HubTab.HealthDays -> HealthDaysTab(vm)
            HubTab.Jobs -> JobsTab(vm, onOpenJob)
            HubTab.Index, HubTab.Trips, HubTab.News -> HubTabPending(tabs[tabIndex])
        }
    }
}

/** Honest placeholder for the three tabs still to be built, not an empty card. */
@Composable
private fun HubTabPending(tab: HubTab) {
    MlxEmptyState(
        message = when (tab) {
            HubTab.Index -> "25K+ Drug Index — catalogue browse and search land next."
            HubTab.Trips -> "TRIPS Waiver Tracker — needs per-molecule field volume from " +
                "prescription scans, which the on-device audit store does not aggregate yet."
            HubTab.News -> "Industry News — the web app streams this from RSS feeds through " +
                "the FastAPI backend. The standalone build is offline-first and has no proxy."
            else -> ""
        },
    )
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
            FlowRowCompat(
                modifier = Modifier.padding(top = MlxD.Space2),
                horizontalSpacing = MlxD.Space2,
                verticalSpacing = MlxD.Space2,
            ) {
                MlxFilterChip(
                    label = "All roles",
                    selected = vm.jobCategory.isEmpty(),
                    onClick = { vm.setJobCategory("") },
                )
                board.categories.forEach { cat ->
                    MlxFilterChip(
                        label = cat,
                        selected = vm.jobCategory == cat,
                        onClick = { vm.setJobCategory(cat) },
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
                    onClick = { vm.setJobLocation("") },
                )
                board.locations.forEach { loc ->
                    MlxFilterChip(
                        label = loc,
                        selected = vm.jobLocation == loc,
                        onClick = { vm.setJobLocation(loc) },
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
                        icon = Icons.Filled.OpenInNew,
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
