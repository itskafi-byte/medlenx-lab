package com.medlenx.lab.ui.screens.rx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.repo.Compliance
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType

// The analytics strips the Prescription Audit Summary is built from.
//
// Lifted out of RxAuditScreen.kt when the audit drawer arrived, for the reason
// copyToClipboard was lifted out of MedLenXShell.kt: the drawer and the audit screen
// render the same therapeutic-class bar and the same market-share footer, and a
// `private` composable cannot be shared - so the second copy would have been written
// by hand, and the two would then have drifted. Their inputs were already primitives;
// only ClassSlice had to travel with them.

/** One slice of the therapeutic-class stacked bar. */
data class ClassSlice(val label: String, val count: Int)

/** Groups the Rx by therapeutic class, largest first, blank class folded into "Other". */
fun classBreakdown(medicines: List<EnrichedMedicine>): List<ClassSlice> =
    classBreakdownOf(medicines.map { it.therapeuticClass })

/**
 * The same grouping for callers holding only the class strings.
 *
 * The audit drawer reads saved rows, where the class is already a column and the
 * medicine is no longer an [EnrichedMedicine]. Splitting the grouping out rather than
 * writing a second one keeps "largest first, blank folds into Other" in one place -
 * the ordering rule is the visible part of this bar, and two copies of it would drift
 * on the tie-break.
 */
fun classBreakdownOf(classes: List<String?>): List<ClassSlice> {
    val grouped = LinkedHashMap<String, Int>()
    for (raw in classes) {
        val key = raw?.takeIf { it.isNotBlank() } ?: "Other"
        grouped[key] = (grouped[key] ?: 0) + 1
    }
    return grouped.entries
        .sortedByDescending { it.value }
        .map { ClassSlice(it.key, it.value) }
}

/**
 * Clinical strip: the stewardship badges plus the therapeutic-class stacked bar.
 *
 * `antibiotics` and `broadSpectrum` are counts of the rows the caller judged to carry
 * those flags, and `slices` comes from [classBreakdown]. The bar's segment colours come
 * from `Mlx.ChartSeries` by index rather than from `Compliance.THERAPY_COLORS` - that is
 * the existing deliberate divergence in `RxAuditScreen`, and it is preserved here
 * rather than quietly corrected in passing.
 */
@Composable
fun ClinicalStrip(
    antibiotics: Int,
    broadSpectrum: Int,
    total: Int,
    offTerritory: Boolean,
    slices: List<ClassSlice>,
    /**
     * Hide the stewardship badge when nothing in the Rx is an antibiotic.
     *
     * The audit screen shows it either way - a zero there is the point, since it is the
     * screen for the scan in progress. The audit drawer hides it, which is what the web
     * does (`index.html:2875`: `if(abx.count>0)` show, else hide), because a drawer that
     * shows an all-zero clinical badge reads as a broken widget rather than as "none".
     */
    hideEmptyAntibiotics: Boolean = false,
    modifier: Modifier = Modifier,
) {
    MlxCard(modifier = modifier) {
        FlowRowCompat(horizontalSpacing = 6.dp, verticalSpacing = 6.dp) {
            if (antibiotics > 0 || !hideEmptyAntibiotics) {
                StatusPill(
                    text = "Antibiotic Stewardship: $antibiotics in Rx · " +
                        "$broadSpectrum broad-spectrum",
                    tone = PillTone.Amber,
                    icon = Icons.Filled.Biotech,
                )
            }
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

/**
 * Footer: own-vs-competitor share for this Rx, with the drawer's two export actions.
 *
 * `ownLabel` is the own company when one is set, so the line reads
 * "Square Pharmaceuticals: 3 / 7 (42%)" rather than a generic "Your company".
 */
@Composable
fun MarketShareCard(
    ownLabel: String,
    ownCount: Int,
    competitorCount: Int,
    total: Int,
    onExportCsv: () -> Unit,
    onCopyClipboard: () -> Unit,
    /**
     * The "Long-press a medicine name to preview the prescription crop" footnote.
     *
     * Off in the audit drawer, which has no long-press handler on its rows - the
     * caption would describe a gesture that does nothing, which is the defect class
     * `android/checks/deadparams.py` exists to catch elsewhere. It is still on in
     * `RxAuditScreen`, where it was already, and where it is *also* unimplemented: no
     * `combinedClickable` or `onLongClick` exists anywhere in this project. That
     * pre-existing caption is recorded in STATE.md's open work rather than changed here,
     * because removing it would be a silent change to a screen this module did not set
     * out to touch.
     */
    showCropHint: Boolean = true,
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

        if (showCropHint) {
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
}

fun sharePct(count: Int, total: Int): Int =
    if (total == 0) 0 else Math.round(count * 100f / total)

@Composable
fun ExportButton(
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

/**
 * The two violet pill buttons under a competitor row (App.tsx:861-864). Drawn by hand
 * because neither is a [com.medlenx.lab.ui.components.MlxButton] tone.
 *
 * `filled` is the pair the web uses: the Own Portfolio Match pill is `bg-violet-100
 * text-violet-600`, the Generate Doctor Pitch Card pill is `bg-violet-600 text-white`.
 * Which action each one fires is the caller's business - in the audit screen both open
 * the pitch card, in the drawer the first expands the match inline and the second opens
 * the card, which is exactly what the web does in each place.
 */
@Composable
fun PillButton(
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
