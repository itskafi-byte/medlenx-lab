package com.medlenx.lab.ui.screens.rx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.needsAuditFollowUp
import com.medlenx.lab.data.repo.RxAudit
import com.medlenx.lab.ui.components.CompanyBadge
import com.medlenx.lab.ui.components.ConfidenceBadge
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxFilterChip
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.data.repo.Compliance
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.RegulatoryPill
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType

/** The four pills across the top of the drawer (App.tsx:787). */
enum class RxAuditFilter(val label: String) {
    All("All"),
    OwnPharma("Own Pharma"),
    Competitors("Competitors"),
    LowConfidence("<80%"),
}

/**
 * Prescription Audit Summary - Figma `RxAuditSummary` (App.tsx:778-897).
 *
 * Everything here is derived from the real scan result rather than the export's mock
 * array, so the counts, the market-share split and the export payloads all agree with
 * what the rep actually scanned. The arithmetic lives in
 * [com.medlenx.lab.data.repo.RxAudit], ported from `app/rx_audit.py`.
 *
 * Two deliberate departures from the mock:
 *
 *  - The export renders `<RegPill label="Duplicate Rx Detected"/>` on every
 *    *competitor* row (App.tsx:857), which is a placeholder bug - it has nothing to
 *    do with duplication. Here the pill is driven by [duplicateOfRxIds] instead.
 *  - The therapeutic-class bar is computed from the medicines' own classes rather
 *    than the hardcoded 33/17/17/17/16.
 *
 * @param duplicateOfRxIds Rx ids whose perceptual hash matched this prescription, so
 *   the duplicate alert only fires when it is true.
 */
@Composable
fun RxAuditScreen(
    rxId: String,
    doctorName: String,
    doctorSpecialty: String,
    repId: String,
    district: String,
    medicines: List<EnrichedMedicine>,
    ownCompany: String,
    offTerritory: Boolean,
    duplicateOfRxIds: List<String>,
    onBack: () -> Unit,
    onPitchCard: (EnrichedMedicine) -> Unit,
    onVerifyAgainstMedex: (EnrichedMedicine) -> Unit,
    onExportCsv: (String) -> Unit,
    onCopyClipboard: (String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    var filter by remember { mutableStateOf(RxAuditFilter.All) }

    val own = remember(medicines, ownCompany) {
        medicines.filter { RxAudit.sameCompanyLoose(it.company, ownCompany) }
    }
    val filtered = when (filter) {
        RxAuditFilter.All -> medicines
        RxAuditFilter.OwnPharma -> own
        RxAuditFilter.Competitors -> medicines.filter { it !in own }
        RxAuditFilter.LowConfidence ->
            medicines.filter { needsAuditFollowUp(it.confidencePercent) }
    }
    val share = remember(medicines, ownCompany) {
        RxAudit.buildMarketShare(medicines, ownCompany)
    }
    val antibiotics = medicines.count { it.isAntibiotic }
    val broadSpectrum = medicines.count { it.broadSpectrum }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = MlxD.ScreenMargin, vertical = MlxD.Space3),
        verticalArrangement = Arrangement.spacedBy(MlxD.Space3),
    ) {
        AuditHeader(
            rxId = rxId,
            subtitle = buildString {
                append("$doctorName ($doctorSpecialty)")
                append("  •  ${medicines.size} Medicines Detected")
                append("  •  MR $repId  •  $district")
            },
            duplicateDetected = duplicateOfRxIds.isNotEmpty(),
            onBack = onBack,
        )

        // Filter pills - horizontal scroll, exactly as the web's overflow-x row.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            RxAuditFilter.entries.forEach { f ->
                val count = when (f) {
                    RxAuditFilter.All -> medicines.size
                    RxAuditFilter.OwnPharma -> own.size
                    RxAuditFilter.Competitors -> medicines.size - own.size
                    RxAuditFilter.LowConfidence ->
                        medicines.count { needsAuditFollowUp(it.confidencePercent) }
                }
                MlxFilterChip(
                    label = "${f.label} ($count)",
                    selected = filter == f,
                    onClick = { filter = f },
                )
            }
        }

        ClinicalStrip(
            antibiotics = antibiotics,
            broadSpectrum = broadSpectrum,
            total = medicines.size,
            offTerritory = offTerritory,
            slices = classBreakdown(medicines),
        )

        if (duplicateOfRxIds.isNotEmpty()) {
            DuplicateFraudNote(duplicateOfRxIds)
        }

        filtered.forEach { med ->
            AuditItemCard(
                medicine = med,
                isOwn = med in own,
                isDuplicate = rxId in duplicateOfRxIds,
                onPitchCard = { onPitchCard(med) },
                onVerifyAgainstMedex = { onVerifyAgainstMedex(med) },
            )
        }

        MarketShareCard(
            ownLabel = share.ownCompany.ifBlank { "Own portfolio" },
            ownCount = share.ownCount,
            competitorCount = share.competitorCount,
            total = share.totalMedicines,
            onExportCsv = { onExportCsv(RxAudit.itemsToCsv(medicines, ownCompany)) },
            onCopyClipboard = {
                onCopyClipboard(RxAudit.itemsToClipboard(medicines, "Rx $rxId"))
            },
        )
    }
}

// ------------------------------------------------------------------ header ----

@Composable
private fun AuditHeader(
    rxId: String,
    subtitle: String,
    duplicateDetected: Boolean,
    onBack: () -> Unit,
) {
    MlxCard {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            MlxIconButton(
                icon = Icons.Filled.ArrowBack,
                contentDescription = "Back to verification",
                onClick = onBack,
            )
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Medication,
                        contentDescription = null,
                        tint = Mlx.Brand600,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = "Prescription Audit Summary — Rx #$rxId",
                        style = MlxType.CardTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                        color = Mlx.Text900,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (duplicateDetected) {
                        RegulatoryPill(
                            text = "Duplicate Rx Detected",
                            tone = PillTone.Red,
                            icon = Icons.Filled.Warning,
                        )
                    }
                }
                Text(
                    text = subtitle,
                    style = MlxType.Meta,
                    color = Mlx.Text500,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// ----------------------------------------------------------- clinical strip ----

/** One slice of the therapeutic-class stacked bar. */
private data class ClassSlice(val label: String, val count: Int)

/** Groups the Rx by therapeutic class, largest first, blank class folded into "Other". */
private fun classBreakdown(medicines: List<EnrichedMedicine>): List<ClassSlice> {
    val grouped = LinkedHashMap<String, Int>()
    for (m in medicines) {
        val key = m.therapeuticClass?.takeIf { it.isNotBlank() } ?: "Other"
        grouped[key] = (grouped[key] ?: 0) + 1
    }
    return grouped.entries
        .sortedByDescending { it.value }
        .map { ClassSlice(it.key, it.value) }
}

@Composable
private fun ClinicalStrip(
    antibiotics: Int,
    broadSpectrum: Int,
    total: Int,
    offTerritory: Boolean,
    slices: List<ClassSlice>,
) {
    MlxCard {
        FlowRowCompat(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
            StatusPill(
                text = "Antibiotic Stewardship: $antibiotics in Rx · $broadSpectrum broad-spectrum",
                tone = PillTone.Amber,
                icon = Icons.Filled.Biotech,
            )
            if (offTerritory) {
                StatusPill(
                    text = "Off-Territory Audit",
                    tone = PillTone.Red,
                    icon = Icons.Filled.LocationOn,
                )
            }
            // The web escalates this one badge by level - amber from 5 medicines, red
            // from 8, with a warning icon - because it is a clinical safety signal, not a
            // count. Rendering the neutral slate label at every size hid that, so the
            // ported `polypharmacy_index` picks the tone. Its label carries the emoji the
            // web inlines; this app uses Material icons instead, so the glyph is stripped.
            val poly = Compliance.polypharmacyIndex(total)
            StatusPill(
                text = poly.label.replace("\u26A0\uFE0F", "").replace("\u26A0", "").trim(),
                tone = when (poly.level) {
                    "high" -> PillTone.Red
                    "moderate" -> PillTone.Amber
                    else -> PillTone.Slate
                },
                icon = if (poly.level == "normal") null else Icons.Filled.Warning,
            )
        }

        Text(
            text = "THERAPEUTIC CLASS BREAKDOWN",
            style = MlxType.SectionLabel,
            color = Mlx.Text600,
            modifier = Modifier.padding(top = MlxD.Space3, bottom = 6.dp),
        )

        if (total == 0) {
            Text("No medicines to break down.", style = MlxType.Footnote, color = Mlx.Text400)
        } else {
            // Stacked bar: height 12dp, fully rounded, segments weighted by count.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(CircleShape),
            ) {
                slices.forEachIndexed { i, slice ->
                    Box(
                        modifier = Modifier
                            .weight(slice.count.toFloat())
                            .fillMaxSize()
                            .background(Mlx.ChartSeries[i % Mlx.ChartSeries.size]),
                    )
                }
            }
            FlowRowCompat(
                modifier = Modifier.padding(top = 6.dp),
                horizontalSpacing = 4.dp,
                verticalSpacing = 4.dp,
            ) {
                slices.forEachIndexed { i, slice ->
                    val pct = Math.round(slice.count * 100f / total)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Mlx.ChartSeries[i % Mlx.ChartSeries.size]),
                        )
                        Text(
                            text = "${slice.label}: $pct%",
                            style = MlxType.Footnote,
                            color = Mlx.Text600,
                        )
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------ fraud notice ----

@Composable
private fun DuplicateFraudNote(duplicateOfRxIds: List<String>) {
    // #FEF2F2 / #FECACA / #B31D1D, per App.tsx:838.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Mlx.DangerSoftBg)
            .padding(MlxD.Space3),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = Mlx.DangerDeep,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Fraud alert: this physical prescription appears to have been " +
                    "scanned before — first captured as " +
                    duplicateOfRxIds.joinToString(", ") { "Rx #$it" } +
                    ". Excluded from target credit pending RSM review.",
                style = MlxType.BodySmall,
                color = Mlx.DangerDeep,
            )
        }
    }
}

// ------------------------------------------------------------ item cards ----

@Composable
private fun AuditItemCard(
    medicine: EnrichedMedicine,
    isOwn: Boolean,
    isDuplicate: Boolean,
    onPitchCard: () -> Unit,
    onVerifyAgainstMedex: () -> Unit,
) {
    val lowConfidence = needsAuditFollowUp(medicine.confidencePercent)
    MlxCard(
        background = if (lowConfidence) Mlx.GuessBg else Mlx.Surface,
        borderColor = Mlx.Brand200,
        padding = MlxD.Space3,
    ) {
        // Brand + strength, dotted-underline as the web does, then the type/dosage meta.
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            Text(
                text = "${medicine.brandName} ${medicine.strength}".trim(),
                style = MlxType.Body.copy(
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textDecoration = TextDecoration.Underline,
                ),
                color = Mlx.Text900,
            )
            Text(
                text = buildString {
                    append(medicine.type.ifBlank { medicine.form })
                    append(" • ")
                    append(medicine.dosage.ifBlank { medicine.frequency })
                },
                style = MlxType.Footnote,
                color = Mlx.Text400,
            )
        }

        // Regulatory chips - only the flags this medicine actually carries.
        FlowRowCompat(
            modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            horizontalSpacing = 4.dp,
            verticalSpacing = 4.dp,
        ) {
            if (medicine.neml != null) {
                RegulatoryPill(text = "NEML Listed", tone = PillTone.Emerald, icon = Icons.Filled.Check)
            }
            when {
                medicine.broadSpectrum -> RegulatoryPill(
                    text = "ABX Broad-Spectrum",
                    tone = PillTone.AmberSolid,
                    icon = Icons.Filled.Star,
                )
                medicine.isAntibiotic -> RegulatoryPill(
                    text = "ABX",
                    tone = PillTone.Amber,
                    icon = Icons.Filled.Biotech,
                )
            }
            if (medicine.dgdaAlert != null) {
                RegulatoryPill(
                    text = "DGDA Price Alert",
                    tone = PillTone.RedSoft,
                    icon = Icons.Filled.Block,
                )
            }
            if (medicine.tripsWatch != null) {
                RegulatoryPill(text = "TRIPS Watch", tone = PillTone.Blue, icon = Icons.Filled.Flag)
            }
            if (isDuplicate) {
                RegulatoryPill(
                    text = "Duplicate Rx Detected",
                    tone = PillTone.Red,
                    icon = Icons.Filled.Warning,
                )
            }
            medicine.therapeuticClass?.takeIf { it.isNotBlank() }?.let { cls ->
                StatusPill(text = cls, tone = PillTone.Slate)
            }
        }

        // Competitor rows offer the substitution and the pitch card.
        if (!isOwn) {
            FlowRowCompat(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
                medicine.substitution?.let { sub ->
                    PillButton(
                        text = "Own Portfolio Match: ${sub.ownBrand.brandName.ifBlank { "—" }}",
                        filled = false,
                        icon = Icons.Filled.AutoAwesome,
                        onClick = onPitchCard,
                    )
                }
                PillButton(
                    text = "Generate Doctor Pitch Card",
                    filled = true,
                    icon = Icons.Filled.Badge,
                    onClick = onPitchCard,
                )
            }
        }

        if (lowConfidence) {
            // The web makes the whole underlined line clickable; keep the 48dp target.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = MlxD.TouchTarget)
                    .clickable(onClick = onVerifyAgainstMedex),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Verify against Medex",
                    style = MlxType.BodySmall.copy(
                        color = Mlx.GuessAccent,
                        textDecoration = TextDecoration.Underline,
                    ),
                )
            }
        }

        Text(
            text = medicine.genericName,
            style = MlxType.BodySmall,
            color = Mlx.Text600,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                CompanyBadge(name = medicine.company.orEmpty(), size = 20.dp)
                Text(
                    text = medicine.company.orEmpty().ifBlank { "Unknown Brand" },
                    style = MlxType.BodySmall.copy(
                        fontWeight = if (isOwn) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (isOwn) FontStyle.Normal else FontStyle.Italic,
                    ),
                    color = if (isOwn) Mlx.Violet else Mlx.Text400,
                )
                if (isOwn) {
                    Icon(
                        imageVector = Icons.Filled.Home,
                        contentDescription = "Own portfolio",
                        tint = Mlx.Violet,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
            ConfidenceBadge(percent = medicine.confidencePercent)
        }
    }
}

/**
 * The two violet pill buttons under a competitor row (App.tsx:861-864). Drawn by hand
 * because neither is a [com.medlenx.lab.ui.components.MlxButton] tone.
 */
@Composable
private fun PillButton(
    text: String,
    filled: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(if (filled) Mlx.Violet else Mlx.VioletBg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (filled) Color.White else Mlx.Violet,
            modifier = Modifier.size(11.dp),
        )
        Text(
            text = text,
            style = MlxType.MicroPill.copy(fontWeight = FontWeight.SemiBold),
            color = if (filled) Color.White else Mlx.Violet,
        )
    }
}

// --------------------------------------------------------- market share ----

@Composable
private fun MarketShareCard(
    ownLabel: String,
    ownCount: Int,
    competitorCount: Int,
    total: Int,
    onExportCsv: () -> Unit,
    onCopyClipboard: () -> Unit,
) {
    MlxCard {
        Text(
            text = "Market Share Summary for this Rx:",
            style = MlxType.BodySmall.copy(fontWeight = FontWeight.Bold),
            color = Mlx.Text600,
            modifier = Modifier.padding(bottom = MlxD.Space2),
        )
        Text(
            text = "• $ownLabel: $ownCount / $total (${sharePct(ownCount, total)}%)",
            style = MlxType.BodySmall,
            color = Mlx.Ok600,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        Text(
            text = "• Competitor brands identified: $competitorCount / $total " +
                "(${sharePct(competitorCount, total)}%)",
            style = MlxType.BodySmall,
            color = Mlx.Warn500,
            modifier = Modifier.padding(bottom = MlxD.Space3),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
            ExportButton(
                text = "Export Rx CSV",
                icon = Icons.Filled.FileDownload,
                tint = Mlx.Ok500,
                onClick = onExportCsv,
                modifier = Modifier.weight(1f),
            )
            ExportButton(
                text = "Copy to Clipboard",
                icon = Icons.Filled.ContentCopy,
                tint = Mlx.Brand600,
                onClick = onCopyClipboard,
                modifier = Modifier.weight(1f),
            )
        }

        Text(
            text = "Long-press a medicine name to preview the prescription crop",
            style = MlxType.Footnote,
            color = Mlx.Text400,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MlxD.Space2),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

private fun sharePct(count: Int, total: Int): Int =
    if (total == 0) 0 else Math.round(count * 100f / total)

@Composable
private fun ExportButton(
    text: String,
    icon: ImageVector,
    tint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Mlx.Surface)
            .defaultMinSize(minHeight = MlxD.TouchTarget)
            .clickable(onClick = onClick)
            .padding(horizontal = MlxD.Space2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        Text(
            text = text,
            style = MlxType.BodySmall,
            color = tint,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
