package com.medlenx.lab.ui.screens.team

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.local.DoctorTargetRow
import com.medlenx.lab.data.local.DoctorVisitRow
import com.medlenx.lab.data.local.OffTerritoryRow
import com.medlenx.lab.data.repo.BrandProgress
import com.medlenx.lab.data.repo.GeoRegion
import com.medlenx.lab.data.repo.RsmTrends
import com.medlenx.lab.data.repo.ScanPoint
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.MlxSegmented
import com.medlenx.lab.ui.components.MiniKpiTile
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.ProgressTrack
import com.medlenx.lab.ui.components.RegulatoryPill
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `TeamMap`: territory penetration heatmap.
 *
 * The Figma export hardcodes three bubbles at fixed percentages as a stand-in;
 * this draws the real thing. SoV mode uses `get_geo_heatmap` regions — bubble
 * size is item volume, colour is own-brand share, exactly the fields the web
 * popup shows. Density mode clusters `get_scan_points` by district. There is no
 * map tile provider in an offline build, so the backdrop is a labelled field
 * with the markers projected equirectangularly over Bangladesh's bounds.
 */
@Composable
fun TeamMapSection(
    regions: List<GeoRegion>,
    points: List<ScanPoint>,
    mode: MapMode,
    onModeChange: (MapMode) -> Unit,
) {
    val bubbles = remember(regions, points, mode) {
        if (mode == MapMode.SOV) {
            regions.filter { it.lat != null && it.lng != null }.map { r ->
                MapBubbleSpec(
                    label = r.upazila,
                    caption = "Items: ${r.items} (own ${r.ownItems} / comp ${r.competitorItems})",
                    volume = r.items,
                    sovPct = r.sov,
                    lat = r.lat!!,
                    lng = r.lng!!,
                    rx = r.rx,
                    tint = sovColor(r.sov),
                )
            }
        } else {
            points.groupBy { it.district.ifBlank { "Unknown" } }.map { (district, pts) ->
                MapBubbleSpec(
                    label = district,
                    caption = "${pts.size} scans",
                    volume = pts.size,
                    sovPct = 0.0,
                    lat = pts.map { it.lat }.average(),
                    lng = pts.map { it.lng }.average(),
                    rx = pts.size,
                    tint = densityColor(pts.size),
                )
            }
        }
    }

    val districts = regions.map { it.district }.filter { it.isNotBlank() }.toSet().size
    val totalItems = regions.sumOf { it.items }
    val ownTotal = regions.sumOf { it.ownItems }
    val penetration = if (totalItems > 0) ownTotal * 100.0 / totalItems else 0.0
    val ownHeavy = regions.count { it.sov >= 50 }
    val compHeavy = regions.count { it.items > 0 && it.sov < 50 }

    MlxCard {
        SectionHeader(
            title = "Territory Penetration Heatmap",
            icon = Icons.Filled.Map,
            subtitle = "Live scan locations mapped across Bangladesh — bubble size = volume, " +
                "colour = your company's market penetration",
        )
        MlxSegmented(
            options = MapMode.entries.map { it.label },
            selectedIndex = MapMode.entries.indexOf(mode),
            onSelect = { onModeChange(MapMode.entries[it]) },
            modifier = Modifier.padding(bottom = MlxD.Space3),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            MiniKpiTile("Districts covered", districts.toString(), Modifier.weight(1f))
            MiniKpiTile("Total items", totalItems.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(MlxD.Space2))
        Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            MiniKpiTile("Market penetration", "${"%.0f".format(penetration)}%", Modifier.weight(1f))
            MiniKpiTile("Own vs Comp-heavy", "$ownHeavy / $compHeavy", Modifier.weight(1f))
        }

        Spacer(Modifier.height(MlxD.Space3))

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(MlxD.MapHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(Mlx.Screen)
                .border(BorderStroke(1.dp, Mlx.Brand200), RoundedCornerShape(12.dp)),
        ) {
            if (bubbles.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("Bangladesh Territory Map", style = MlxType.Footnote, color = Mlx.Text400)
                    Text(
                        text = "No geo-resolved audits in this window yet.",
                        style = MlxType.Footnote,
                        color = Mlx.Text400,
                    )
                }
            } else {
                val w = maxWidth
                val h = maxHeight
                bubbles.forEach { b ->
                    val size = (14 + b.volume.coerceAtMost(60) * 0.8).dp
                    val f = project(b.lat, b.lng)
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = w * f.x - size / 2, y = h * f.y - size / 2)
                            .size(size)
                            .clip(CircleShape)
                            .background(b.tint.copy(alpha = 0.5f))
                            .border(BorderStroke(2.dp, b.tint), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (size >= 24.dp) {
                            Text(
                                text = b.volume.toString(),
                                style = MlxType.MicroPill,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(MlxD.Space3))
        FlowRowCompat(horizontalSpacing = MlxD.Space3, verticalSpacing = MlxD.Space1) {
            if (mode == MapMode.SOV) {
                LegendDot(Mlx.Ok500, "Own-company dominant")
                LegendDot(Mlx.Amber500, "Competitive / mixed")
                LegendDot(Mlx.Danger, "Competitor-heavy")
            } else {
                LegendDot(Mlx.Violet, "≥10 scans")
                LegendDot(Mlx.Cyan600, "5–9")
                LegendDot(Mlx.Ok500, "2–4")
                LegendDot(Mlx.Text400, "1")
            }
            Text(
                text = "Bubble size = prescription volume",
                style = MlxType.Footnote,
                color = Mlx.Text500,
            )
        }
    }
}

/** Bangladesh's rough bounding box, for the equirectangular fit. */
private const val BD_LAT_MAX = 26.7
private const val BD_LAT_MIN = 20.5
private const val BD_LNG_MIN = 88.0
private const val BD_LNG_MAX = 92.7

/** Projects a fix onto 0..1 canvas fractions, clamped so a stray pin stays visible. */
private fun project(lat: Double, lng: Double): Offset = Offset(
    x = ((lng - BD_LNG_MIN) / (BD_LNG_MAX - BD_LNG_MIN)).toFloat().coerceIn(0f, 1f),
    y = ((BD_LAT_MAX - lat) / (BD_LAT_MAX - BD_LAT_MIN)).toFloat().coerceIn(0f, 1f),
)

private data class MapBubbleSpec(
    val label: String,
    val caption: String,
    val volume: Int,
    val sovPct: Double,
    val lat: Double,
    val lng: Double,
    val rx: Int,
    val tint: Color,
)

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(MlxD.Space1))
        Text(text = label, style = MlxType.Footnote, color = Mlx.Text500)
    }
}

/** SoV mode colours by own-brand share; density mode by raw scan count. */
private fun sovColor(sov: Double): Color = when {
    sov >= 50 -> Mlx.Ok500
    sov > 0 -> Mlx.Amber500
    else -> Mlx.Danger
}

private fun densityColor(volume: Int): Color = when {
    volume >= 10 -> Mlx.Violet
    volume >= 5 -> Mlx.Cyan600
    volume >= 2 -> Mlx.Ok500
    else -> Mlx.Text400
}

/**
 * `TeamLeaderboard`.
 *
 * `get_rsm_dashboard` reads a `team_members` table that has no Android
 * equivalent, so a standalone build's roster is one officer. The row is still
 * the real aggregate — prescriptions, items, own/competitor split, SoV and the
 * week-over-week sparkline from `get_rsm_trends`.
 */
@Composable
fun TeamLeaderboardSection(
    officerName: String,
    officerRole: String,
    territory: String,
    repCode: String,
    trends: RsmTrends?,
    prescriptions: Int,
    items: Int,
    ownItems: Int,
) {
    val compItems = maxOf(items - ownItems, 0)
    val sov = if (items > 0) ownItems * 100.0 / items else 0.0
    val wow = trends?.ownGrowth ?: 0.0

    MlxCard {
        SectionHeader(title = "Team Leaderboard", icon = Icons.Filled.EmojiEvents)
        MlxCard(padding = 12.dp, borderColor = Mlx.Brand100) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Mlx.Screen),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("1", style = MlxType.BodySmall, fontWeight = FontWeight.Bold, color = Mlx.Text900)
                }
                Spacer(Modifier.width(MlxD.Space3))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = listOf(repCode, officerName).filter { it.isNotBlank() }.joinToString(" "),
                        style = MlxType.CardTitle,
                        color = Mlx.Text900,
                    )
                    Text(
                        text = listOf(officerRole, territory).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MlxType.Footnote,
                        color = Mlx.Text500,
                    )
                }
                Text(
                    text = "${if (wow >= 0) "▲" else "▼"} ${"%.1f".format(kotlin.math.abs(wow))}% WoW",
                    style = MlxType.BodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (wow >= 0) Mlx.Ok500 else Mlx.Danger,
                )
            }
            Spacer(Modifier.height(MlxD.Space2))
            FlowRowCompat(horizontalSpacing = MlxD.Space3, verticalSpacing = MlxD.Space1) {
                Text("$prescriptions Rx · $items items", style = MlxType.BodySmall, color = Mlx.Text600)
                Text("$ownItems own", style = MlxType.BodySmall, fontWeight = FontWeight.SemiBold, color = Mlx.OkDeep)
                Text("$compItems comp", style = MlxType.BodySmall, color = Mlx.WarnDeep)
            }
            Spacer(Modifier.height(MlxD.Space3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressTrack(
                    progress = (sov.toFloat() / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(MlxD.Space2))
                Text(
                    text = "${"%.1f".format(sov)}%",
                    style = MlxType.Delta,
                    fontWeight = FontWeight.Bold,
                    color = Mlx.Text900,
                )
                Spacer(Modifier.width(MlxD.Space2))
                Sparkline(trends?.series?.map { it.own } ?: emptyList())
            }
        }
        Text(
            text = "Standalone build: the roster is this officer. Team aggregation needs the server's team_members table.",
            style = MlxType.Footnote,
            color = Mlx.Text500,
            modifier = Modifier.padding(top = MlxD.Space2),
        )
    }
}

/** Own-prescriptions-per-week sparkline; flat line when there is no series. */
@Composable
private fun Sparkline(values: List<Int>, modifier: Modifier = Modifier) {
    val line = Mlx.Ok500
    Canvas(
        modifier = modifier
            .width(MlxD.SparklineWidth)
            .height(MlxD.SparklineHeight),
    ) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 0).coerceAtLeast(1).toFloat()
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = step * i
            val y = size.height - (v / max) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
    }
}

/** `get_target_progress` plus the RSM doctor detailing tracker. */
@Composable
fun TeamTargetsSection(
    brands: List<BrandProgress>,
    month: String,
    doctorTargets: List<DoctorTargetRow>,
    visitLog: List<DoctorVisitRow>,
    onRemoveTarget: (Long) -> Unit,
) {
    MlxCard {
        SectionHeader(
            title = "Brand Target Progress",
            icon = Icons.Filled.Flag,
            subtitle = "This month's captured prescriptions against each brand target.",
        )
        if (brands.isEmpty()) {
            MlxEmptyState(
                message = "No brand targets set for $month — add them on the Settings screen.",
                icon = Icons.Filled.Flag,
            )
        } else {
            brands.forEach { b ->
                Column(Modifier.padding(bottom = MlxD.Space3)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = b.brandName,
                            style = MlxType.CardTitle,
                            color = Mlx.Text900,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${b.captured} / ${b.monthlyTarget}",
                            style = MlxType.BodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (b.captured >= b.monthlyTarget) Mlx.OkDeep else Mlx.Text600,
                        )
                    }
                    Spacer(Modifier.height(MlxD.Space1))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressTrack(
                            progress = (b.percent.toFloat() / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(MlxD.Space2))
                        Text(
                            text = "${"%.1f".format(b.percent)}%",
                            style = MlxType.Footnote,
                            color = Mlx.Text400,
                        )
                    }
                    Text(
                        text = "Remaining ${b.remaining} · ${b.month}",
                        style = MlxType.Footnote,
                        color = Mlx.Text500,
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(MlxD.SectionGap))

    MlxCard {
        SectionHeader(
            title = "Doctor Detailing Target Tracker",
            icon = Icons.Filled.PinDrop,
            iconTint = Mlx.Danger,
            subtitle = "Attach target doctor lists to MPOs. Every scanned prescription whose doctor " +
                "matches a target auto-logs a visit — duplicates of the same physical Rx never count twice.",
        )
        if (doctorTargets.isEmpty()) {
            MlxEmptyState(
                message = "No doctor targets attached yet.",
                icon = Icons.Filled.PinDrop,
            )
        } else {
            doctorTargets.forEach { t ->
                val pct = if (t.monthlyTarget != 0) t.visits * 100.0 / t.monthlyTarget else 0.0
                Column(Modifier.padding(bottom = MlxD.Space3)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = t.doctorName,
                            style = MlxType.CardTitle,
                            color = Mlx.Text900,
                            modifier = Modifier.weight(1f),
                        )
                        MlxIconButton(
                            icon = Icons.Filled.Delete,
                            contentDescription = "Remove target for ${t.doctorName}",
                            onClick = { onRemoveTarget(t.targetId) },
                            tint = Mlx.Text400,
                        )
                    }
                    Text(
                        text = listOf(t.specialty).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MlxType.Footnote,
                        color = Mlx.Text500,
                    )
                    Spacer(Modifier.height(MlxD.Space1))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ProgressTrack(
                            progress = (pct.toFloat() / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(MlxD.Space2))
                        Text(
                            text = "${t.visits} / ${t.monthlyTarget} · ${"%.0f".format(pct)}%",
                            style = MlxType.BodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (t.visits >= t.monthlyTarget) Mlx.OkDeep else Mlx.Text600,
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(MlxD.Space4))
        Text(
            text = "Auto-visit log (from prescription scans)",
            style = MlxType.SectionLabel,
            color = Mlx.Text500,
        )
        Spacer(Modifier.height(MlxD.Space2))
        if (visitLog.isEmpty()) {
            MlxEmptyState(
                message = "No auto-logged visits yet.",
                icon = Icons.Filled.Route,
            )
        } else {
            visitLog.forEach { v ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Mlx.Screen)
                        .border(BorderStroke(1.dp, Mlx.Brand100), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Mlx.Ok500),
                    )
                    Spacer(Modifier.width(MlxD.Space2))
                    Text(
                        text = v.doctorName,
                        style = MlxType.BodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Mlx.Text900,
                    )
                    Spacer(Modifier.width(MlxD.Space2))
                    Text(
                        text = "visited by ${v.mrId}",
                        style = MlxType.BodySmall,
                        color = Mlx.Text500,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatStamp(v.visitedAt),
                        style = MlxType.Footnote,
                        color = Mlx.Text400,
                    )
                    if (v.rxNo.isNotBlank()) {
                        Spacer(Modifier.width(MlxD.Space2))
                        Text(
                            text = v.rxNo,
                            style = MlxType.Footnote,
                            color = Mlx.BlueText,
                        )
                    }
                }
                Spacer(Modifier.height(MlxD.Space2))
            }
        }
    }
}

/** `find_off_territory_audits`. */
@Composable
fun TeamOffTerritorySection(flags: List<OffTerritoryRow>) {
    MlxCard {
        SectionHeader(
            title = "Off-Territory Audit Verification",
            icon = Icons.Filled.Flag,
            iconTint = Mlx.Danger,
        )
        StatusPill(
            text = "${flags.size} flagged",
            tone = if (flags.isEmpty()) PillTone.Slate else PillTone.Red,
            modifier = Modifier.padding(bottom = MlxD.Space2),
        )
        Text(
            text = "GPS-pinned and location-resolved audits are geofenced against the assigned " +
                "territory — off-territory uploads are listed here alongside the pHash duplicate guard.",
            style = MlxType.BodySmall,
            color = Mlx.Text500,
            modifier = Modifier.padding(bottom = MlxD.Space3),
        )
        if (flags.isEmpty()) {
            MlxEmptyState(
                message = "No off-territory audits in this window.",
                icon = Icons.Filled.Flag,
            )
        } else {
            flags.forEach { f ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Mlx.DangerBg)
                        .border(BorderStroke(1.dp, Mlx.DangerBorder), RoundedCornerShape(12.dp))
                        .padding(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Mlx.Surface)
                            .border(BorderStroke(1.dp, Mlx.DangerBorder), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Filled.Assignment,
                            contentDescription = null,
                            tint = Mlx.Danger,
                            modifier = Modifier.size(MlxD.IconSmall),
                        )
                    }
                    Spacer(Modifier.width(MlxD.Space3))
                    Column(Modifier.weight(1f)) {
                        FlowRowCompat(
                            horizontalSpacing = MlxD.Space2,
                            verticalSpacing = MlxD.Space1,
                        ) {
                            Text(
                                text = f.doctorName,
                                style = MlxType.BodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Mlx.Text900,
                            )
                            RegulatoryPill(
                                text = "Off-Territory Audit",
                                tone = PillTone.Red,
                                icon = Icons.Filled.PinDrop,
                            )
                        }
                        Text(
                            text = "Assigned: ${f.territory.ifBlank { "—" }} · Scanned in: " +
                                listOf(f.upazila, f.district).filter { it.isNotBlank() }
                                    .joinToString(", ").ifBlank { "unknown" },
                            style = MlxType.BodySmall,
                            color = Mlx.Text600,
                        )
                        Text(
                            text = "MR ${f.mrId.ifBlank { "—" }} · ${formatStamp(f.createdAt)}",
                            style = MlxType.Footnote,
                            color = Mlx.Text400,
                        )
                        f.territoryNote?.takeIf { it.isNotBlank() }?.let {
                            Text(text = it, style = MlxType.Footnote, color = Mlx.DangerText)
                        }
                    }
                }
                Spacer(Modifier.height(MlxD.Space2))
            }
        }
    }
}

private val stampFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())

private fun formatStamp(epochMillis: Long): String =
    stampFormat.format(Instant.ofEpochMilli(epochMillis))
