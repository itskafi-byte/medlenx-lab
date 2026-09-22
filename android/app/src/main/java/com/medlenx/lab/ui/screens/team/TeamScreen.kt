package com.medlenx.lab.ui.screens.team

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.repo.DoctorTier
import com.medlenx.lab.data.repo.StewardshipDoctor
import com.medlenx.lab.ui.components.DarkHero
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxErrorLine
import com.medlenx.lab.ui.components.MlxFilterChip
import com.medlenx.lab.ui.components.MiniKpiTile
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.ProgressTrack
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType

/**
 * Territory Manager / RSM command screen.
 *
 * `TeamScreen` in `App.tsx` stacks six sections; this builds the two whose
 * aggregates are ported (`get_doctor_tiers`, `get_stewardship_summary`). The
 * territory map, leaderboard, brand targets and off-territory log still need
 * their own DAO queries.
 */
@Composable
fun TeamScreen(
    vm: TeamViewModel,
    modifier: Modifier = Modifier,
    onExportPdf: () -> Unit = {},
) {
    val tiering = vm.tiering
    val stewardship = vm.stewardship

    Column(modifier = modifier.fillMaxWidth()) {
        TeamHero(
            prescriptions = tiering.doctors.sumOf { it.rx },
            ownItems = tiering.doctors.sumOf { it.ownItems },
            items = tiering.doctors.sumOf { it.items },
            onExportPdf = onExportPdf,
        )

        Spacer(Modifier.height(MlxD.SectionGap))

        vm.error?.let { MlxErrorLine(it, Modifier.padding(bottom = MlxD.SectionGap)) }

        TeamMapSection(
            regions = vm.geoRegions,
            points = vm.scanPoints,
            mode = vm.mapMode,
            onModeChange = vm::updateMapMode,
        )

        Spacer(Modifier.height(MlxD.SectionGap))

        TeamTiersSection(
            doctors = tiering.doctors,
            ownCompany = tiering.ownCompany,
            days = tiering.days,
            tierFilter = vm.tierFilter,
            onTierFilter = vm::updateTierFilter,
        )

        Spacer(Modifier.height(MlxD.SectionGap))

        TeamLeaderboardSection(
            officerName = vm.officerProfile?.fullName.orEmpty(),
            officerRole = vm.officerProfile?.role.orEmpty(),
            territory = vm.officerProfile?.territory.orEmpty(),
            repCode = vm.officerProfile?.employeeId.orEmpty(),
            trends = vm.trends,
            prescriptions = tiering.doctors.sumOf { it.rx },
            items = tiering.doctors.sumOf { it.items },
            ownItems = tiering.doctors.sumOf { it.ownItems },
        )

        Spacer(Modifier.height(MlxD.SectionGap))

        TeamTargetsSection(
            brands = vm.targetProgress?.brands ?: emptyList(),
            month = vm.targetProgress?.month ?: "",
            doctorTargets = vm.doctorTargets,
            visitLog = vm.visitLog,
            onRemoveTarget = vm::removeDoctorTarget,
        )

        Spacer(Modifier.height(MlxD.SectionGap))

        TeamOffTerritorySection(vm.offTerritory)

        Spacer(Modifier.height(MlxD.SectionGap))

        TeamStewardshipSection(stewardship.doctors, stewardship.totals.abxSharePct)
    }
}

/**
 * The dark hero.
 *
 * The web version reports a 50+ MPO roster ("Team size 52", "Prescriptions
 * 2,841"). A standalone build has no sync, so the roster is this officer and
 * every figure is this device's own 30-day audit volume. The footnote says so
 * rather than letting the labels imply a team that does not exist here.
 */
@Composable
private fun TeamHero(
    prescriptions: Int,
    ownItems: Int,
    items: Int,
    onExportPdf: () -> Unit,
) {
    val sov = if (items > 0) ownItems * 100f / items else 0f
    DarkHero {
        Text(
            text = "Multi-tenant org hierarchy",
            style = MlxType.SectionLabel,
            color = Color.White.copy(alpha = 0.70f),
        )
        Spacer(Modifier.height(MlxD.Space2))
        Text(
            text = "Territory Manager / RSM Command",
            style = MlxType.HeroTitle,
            color = Color.White,
        )
        Spacer(Modifier.height(MlxD.Space2))
        Text(
            text = "Aggregated prescription audits with competitive Share of Voice.",
            style = MlxType.BodySmall,
            color = Color.White.copy(alpha = 0.80f),
        )
        Spacer(Modifier.height(MlxD.Space4))
        MlxButton(
            text = "Generate DGDA / Compliance Audit PDF",
            onClick = onExportPdf,
            icon = Icons.Filled.Description,
        )
        Spacer(Modifier.height(MlxD.Space5))
        Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            HeroKpi("Officers", "1", Modifier.weight(1f))
            HeroKpi("Prescriptions", prescriptions.toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(MlxD.Space2))
        Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            HeroKpi("Own items", ownItems.toString(), Modifier.weight(1f))
            HeroKpi("Own SoV", "${"%.0f".format(sov)}%", Modifier.weight(1f))
        }
        Spacer(Modifier.height(MlxD.Space3))
        Text(
            text = "Standalone build: figures cover this device's audits for the last 30 days, not a synced field team.",
            style = MlxType.Footnote,
            color = Color.White.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun HeroKpi(label: String, value: String, modifier: Modifier = Modifier) {
    MiniKpiTile(
        label = label,
        value = value,
        modifier = modifier,
        labelColor = Color.White.copy(alpha = 0.70f),
        valueColor = Color.White,
        background = Color.White.copy(alpha = 0.10f),
        borderColor = Color.White.copy(alpha = 0.16f),
    )
}

@Composable
private fun TeamTiersSection(
    doctors: List<DoctorTier>,
    ownCompany: String,
    days: Int,
    tierFilter: String,
    onTierFilter: (String) -> Unit,
) {
    // Counts are computed over the *unfiltered* set would require the unfiltered
    // list; the web app hardcodes them. Derive from what is on screen so the
    // four tiles always agree with the rows below.
    val countA = doctors.count { it.tier == "A" }
    val countB = doctors.count { it.tier == "B" }
    val countC = doctors.count { it.tier == "C" }
    val atRisk = doctors.count { it.atRisk }

    MlxCard {
        SectionHeader(
            title = "Doctor Prescribing Tiering Matrix (A/B/C)",
            icon = Icons.Filled.MedicalServices,
            subtitle = "Auto-classified from audit volume — Tier A >10 Rx, B 4–9 Rx, C ≤3 Rx/month. " +
                "At-risk = high-volume doctors with low own-brand share.",
        )
        Text(
            text = "Own brand: $ownCompany · last $days days",
            style = MlxType.Footnote,
            color = Mlx.Text500,
            modifier = Modifier.padding(bottom = MlxD.Space3),
        )

        FlowRowCompat(
            modifier = Modifier.padding(bottom = MlxD.Space3),
            horizontalSpacing = MlxD.Space2,
            verticalSpacing = MlxD.Space2,
        ) {
            listOf("" to "All", "A" to "Tier A", "B" to "Tier B", "C" to "Tier C")
                .forEach { (value, label) ->
                    MlxFilterChip(
                        label = label,
                        selected = tierFilter == value,
                        onClick = { onTierFilter(value) },
                    )
                }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            TierCountTile("Tier A · High prescriber", countA, Mlx.Violet, Mlx.VioletDeep, Mlx.VioletBg, Mlx.VioletBorder, Modifier.weight(1f))
            TierCountTile("Tier B · Medium", countB, Mlx.BlueText, Mlx.IndigoDeep, Mlx.BlueBg, Mlx.Blue200, Modifier.weight(1f))
        }
        Spacer(Modifier.height(MlxD.Space2))
        Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            TierCountTile("Tier C · Occasional", countC, Mlx.Text600, Mlx.Text700, Mlx.Screen, Mlx.Brand200, Modifier.weight(1f))
            TierCountTile("At-risk switchers", atRisk, Mlx.Danger, Mlx.DangerDeep, Mlx.DangerBg, Mlx.DangerBorder, Modifier.weight(1f))
        }

        Spacer(Modifier.height(MlxD.Space4))

        if (doctors.isEmpty()) {
            MlxEmptyState(
                message = "No tiered doctors yet — scan prescriptions to classify them by audit volume.",
                icon = Icons.Filled.MedicalServices,
            )
        } else {
            doctors.forEach { d ->
                TierDoctorCard(d)
                Spacer(Modifier.height(MlxD.Space2))
            }
        }
    }
}

@Composable
private fun TierCountTile(
    label: String,
    count: Int,
    labelColor: Color,
    valueColor: Color,
    background: Color,
    borderColor: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Text(text = label, style = MlxType.Footnote, fontWeight = FontWeight.Bold, color = labelColor)
        Spacer(Modifier.height(MlxD.Space1))
        Text(text = count.toString(), style = MlxType.MiniKpiValue, color = valueColor)
    }
}

@Composable
private fun TierDoctorCard(d: DoctorTier) {
    val tierColor = when (d.tier) {
        "A" -> Mlx.Violet
        "B" -> Mlx.BlueText
        else -> Mlx.Text600
    }
    MlxCard(padding = 12.dp, borderColor = Mlx.Brand100) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(MlxD.Space2),
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(tierColor.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    text = "Tier ${d.tier}",
                    style = MlxType.MicroPill,
                    fontWeight = FontWeight.Bold,
                    color = tierColor,
                )
            }
            Text(
                text = d.doctorName,
                style = MlxType.CardTitle,
                color = Mlx.Text900,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (d.atRisk) {
                StatusPill(
                    text = "At risk",
                    tone = PillTone.Red,
                    icon = Icons.Filled.Warning,
                )
            }
        }
        Spacer(Modifier.height(MlxD.Space2))
        Text(
            text = listOf(d.specialty, d.territory).filter { it.isNotBlank() }.joinToString(" · "),
            style = MlxType.Footnote,
            color = Mlx.Text500,
        )
        Spacer(Modifier.height(MlxD.Space3))
        FlowRowCompat(
            horizontalSpacing = MlxD.Space3,
            verticalSpacing = MlxD.Space1,
        ) {
            Text("${d.rx} Rx", style = MlxType.BodySmall, color = Mlx.Text600)
            Text("${d.items} items", style = MlxType.BodySmall, color = Mlx.Text600)
            Text("${d.ownItems} own", style = MlxType.BodySmall, fontWeight = FontWeight.SemiBold, color = Mlx.OkDeep)
            Text("${d.competitorItems} comp", style = MlxType.BodySmall, color = Mlx.WarnDeep)
        }
        Spacer(Modifier.height(MlxD.Space3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${"%.1f".format(d.sov)}%",
                style = MlxType.Delta,
                fontWeight = FontWeight.Bold,
                color = Mlx.Text900,
            )
            Spacer(Modifier.width(MlxD.Space2))
            ProgressTrack(
                progress = (d.sov.toFloat() / 100f).coerceIn(0f, 1f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TeamStewardshipSection(doctors: List<StewardshipDoctor>, abxSharePct: Double) {
    MlxCard {
        SectionHeader(
            title = "Antibiotic Stewardship Monitor",
            icon = Icons.Filled.Warning,
            iconTint = Mlx.Danger,
        )
        StatusPill(
            text = "${"%.1f".format(abxSharePct)}% ABX share",
            tone = shareTone(abxSharePct),
            modifier = Modifier.padding(bottom = MlxD.Space2),
        )
        Text(
            text = "Broad-spectrum antibiotic prescribing audited per doctor chamber (AWaRe watch list) " +
                "— regional stewardship compliance and detailing focus at a glance.",
            style = MlxType.BodySmall,
            color = Mlx.Text500,
            modifier = Modifier.padding(bottom = MlxD.Space3),
        )

        if (doctors.isEmpty()) {
            MlxEmptyState(
                message = "No stewardship data yet — scan prescriptions to audit ABX trends.",
                icon = Icons.Filled.Warning,
            )
        } else {
            doctors.forEach { c ->
                StewardshipChamberCard(c)
                Spacer(Modifier.height(MlxD.Space2))
            }
        }
    }
}

/** Figma's `shareChipVariant`: >=40 red, >=20 amber, otherwise slate. */
private fun shareTone(pct: Double): PillTone = when {
    pct >= 40 -> PillTone.Red
    pct >= 20 -> PillTone.Amber
    else -> PillTone.Slate
}

@Composable
private fun StewardshipChamberCard(c: StewardshipDoctor) {
    val barColor = when {
        c.abxSharePct >= 40 -> Mlx.Danger
        c.abxSharePct >= 20 -> Mlx.Amber500
        else -> Mlx.Ok500
    }
    MlxCard(padding = 12.dp, borderColor = Mlx.Brand100) {
        Text(text = c.doctorName, style = MlxType.CardTitle, color = Mlx.Text900)
        Text(
            text = listOf(c.specialty, c.district).filter { it.isNotBlank() }.joinToString(" · "),
            style = MlxType.Footnote,
            color = Mlx.Text500,
        )
        Spacer(Modifier.height(MlxD.Space2))
        FlowRowCompat(horizontalSpacing = MlxD.Space3, verticalSpacing = MlxD.Space1) {
            Text("${c.rx} Rx audited", style = MlxType.BodySmall, color = Mlx.Text600)
            Text(
                text = c.abxItems.toString(),
                style = MlxType.BodySmall,
                fontWeight = FontWeight.Bold,
                color = when {
                    c.abxItems >= 4 -> Mlx.Danger
                    c.abxItems > 0 -> Mlx.Warn600
                    else -> Mlx.Text400
                },
            )
            Text("/ ${c.items} items", style = MlxType.BodySmall, color = Mlx.Text400)
            Text(
                text = "★ ${c.broadItems}",
                style = MlxType.BodySmall,
                fontWeight = FontWeight.Bold,
                color = Mlx.Danger,
            )
        }
        Spacer(Modifier.height(MlxD.Space3))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressTrack(
                progress = (c.abxSharePct.toFloat() / 100f).coerceIn(0f, 1f),
                modifier = Modifier.weight(1f),
                fillColor = barColor,
            )
            Spacer(Modifier.width(MlxD.Space2))
            Text(
                text = "${"%.1f".format(c.abxSharePct)}%",
                style = MlxType.Footnote,
                color = Mlx.Text400,
            )
        }
        Spacer(Modifier.height(MlxD.Space2))
        Text(
            text = "Brands: " + if (c.abxBrands.isEmpty()) "—" else c.abxBrands.joinToString(", "),
            style = MlxType.Footnote,
            color = Mlx.Text500,
        )
    }
}
