package com.medlenx.lab.ui.screens.rx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.Substitution
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.RegulatoryPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType

/**
 * What the Doctor Pitch sheet shows, decoupled from the scan that produced it.
 *
 * The sheet used to be opened only from the Rx Audit screen and read its rx number and
 * doctor straight from `ScanViewModel.state`. The audit drawer opens it too, for a
 * prescription that was saved earlier - possibly on another device, and with no scan in
 * progress at all - so those two fields have to travel with the substitution instead.
 */
data class PitchTarget(
    val rxId: String,
    val doctorName: String,
    val doctorSpecialty: String,
    val substitution: Substitution,
)

/**
 * Doctor Pitch Card - Figma `DoctorPitchCard` (App.tsx:898-991).
 *
 * A bottom sheet the rep opens from a competitor row in the audit drawer. It sets the
 * competitor against the own-portfolio substitute, states the price position, and
 * carries the pitch script the rep reads out during a chamber visit.
 *
 * The layout is the Figma's; the *content* is the caller's. Every line of the compare
 * table comes from the two [MedexProduct] records on the [Substitution], the pitch
 * script is [Substitution.pitch], and the bioequivalence paragraph is passed in rather
 * than hardcoded - the export's copy asserts DGDA registration and bioequivalence
 * certification for products that may not have either, and shipping that text verbatim
 * would put an unverifiable clinical claim in front of a doctor.
 *
 * A [Dialog], deliberately. The web stacks this over the audit drawer (`z-[10001]`
 * against the drawer's `z-[9999]`, `index.html:553` / `:644`) and leaves the drawer open
 * behind it. A Compose [Dialog] is its own window and the drawer is one too, so the same
 * relationship needs the same mechanism: while the pitch sheet was a plain Box in the
 * activity's window, opening it from the drawer composed it *underneath* the drawer's
 * window - visible only through the scrim, and unreachable, because the dialog window
 * takes the touches. From the audit screen it looked right, which is why the audit-screen
 * path did not catch it. Back now closes the sheet before the drawer, as on the web.
 */
@Composable
fun DoctorPitchCard(
    rxId: String,
    doctorName: String,
    substitution: Substitution,
    bioequivalenceNote: String,
    onClose: () -> Unit,
    onDownloadPdf: () -> Unit,
    onCopyPitch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val competitor = substitution.competitor
    val own = substitution.ownBrand

    // The web version is position:fixed inset:0 with a 50% black scrim and the sheet
    // pinned to the bottom edge; reproduced as a full-size Box with a bottom-aligned
    // sheet so it can be shown from any screen by flipping a flag.
    //
    // In a Dialog, because the audit drawer is one and this has to sit *above* it - see
    // the note on this composable. `usePlatformDefaultWidth = false` is what makes the
    // window the whole screen; the platform default would inset the sheet to a
    // phone-sized column and leave the scrim short of the edges.
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(modifier = modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable(onClick = onClose),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.88f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(Mlx.Surface)
                    .verticalScroll(rememberScrollState()),
            ) {
                PitchHeader(
                    subtitle = "$doctorName · Rx #$rxId · " +
                        "${competitor.brandName} → ${own.brandName}",
                    onClose = onClose,
                )

                Column(modifier = Modifier.padding(MlxD.Space4)) {
                    // Compliance pills carried over from the medicine being displaced.
                    FlowRowCompat(horizontalSpacing = 4.dp, verticalSpacing = 4.dp) {
                        RegulatoryPill(text = "NEML Listed", tone = PillTone.Emerald, icon = Icons.Filled.Check)
                        RegulatoryPill(
                            text = "DGDA Price Alert",
                            tone = PillTone.RedSoft,
                            icon = Icons.Filled.Block,
                        )
                    }

                    CompareTable(competitor = competitor, own = own)

                    // Price position - #ECFDF5 / #A7F3D0 / #047857.
                    NoteBox(
                        icon = Icons.Filled.Balance,
                        iconTint = Mlx.Ok600,
                        background = Mlx.Ok50,
                        border = Mlx.Ok200,
                        textColor = Mlx.Ok600,
                        text = "Price position: ${substitution.unitDifferenceLabel.ifBlank {
                            formatDifference(substitution.unitDifference)
                        }} lower per unit than ${competitor.brandName}",
                    )

                    // Bioequivalence - rgba(239,246,255,.60) / #DBEAFE / #1D4ED8.
                    EvidenceBox(
                        title = "Bioequivalence & dosage evidence",
                        titleIcon = Icons.Filled.Biotech,
                        titleColor = Mlx.Brand600,
                        background = Color(0xFFEFF6FF).copy(alpha = 0.60f),
                        border = Mlx.Accent100,
                        paragraphs = bioequivalenceNote.split("\n").filter { it.isNotBlank() },
                    )

                    // Pitch script - #F8FAFC / #E2E8F0 / #64748B.
                    EvidenceBox(
                        title = "Smart pitch script",
                        titleIcon = Icons.Filled.Campaign,
                        titleColor = Mlx.Text500,
                        background = Mlx.Brand50,
                        border = Mlx.Brand200,
                        paragraphs = listOf(substitution.pitch),
                        italic = true,
                    )

                    Text(
                        text = "Bioequivalence & pack data from the MedEx-audited catalogue " +
                            "· verify sample stock before the visit.",
                        style = MlxType.Footnote,
                        color = Mlx.Text400,
                        modifier = Modifier.padding(bottom = MlxD.Space3),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
                        PitchFooterButton(
                            text = "Download PDF",
                            icon = Icons.Filled.Description,
                            filled = true,
                            onClick = onDownloadPdf,
                            modifier = Modifier.weight(1f),
                        )
                        PitchFooterButton(
                            text = "Copy pitch script",
                            icon = Icons.Filled.ContentCopy,
                            filled = false,
                            onClick = onCopyPitch,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // The web's pitch card offers a PDF only. A WhatsApp button lived
                    // here between gap 6 and the health-day sheet, as an approximation
                    // of the campaign card that actually has one; it is gone now that
                    // that card exists, so this screen has no action the web does not.
                    Text(
                        text = "Show during chamber visit",
                        style = MlxType.Footnote,
                        color = Mlx.Text400,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = MlxD.Space2),
                    )
                }
            }
        }
    }
}

/** Violet-to-indigo sticky header (App.tsx:903-910). */
@Composable
private fun PitchHeader(subtitle: String, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.horizontalGradient(listOf(Mlx.Violet, Mlx.Indigo)))
            .padding(horizontal = MlxD.Space4, vertical = MlxD.Space5),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MedicalInformation,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Doctor Pitch Card",
                    style = MlxType.CardTitle.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                    color = Color.White,
                )
            }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.20f))
                    .clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Close pitch card",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Text(
            text = subtitle,
            style = MlxType.Footnote,
            color = Color.White.copy(alpha = 0.80f),
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * The competitor-vs-own compare grid (App.tsx:920-942). Three columns: a nowrap label,
 * then competitor and own brand at equal width, the own column in violet.
 */
@Composable
private fun CompareTable(competitor: MedexProduct, own: MedexProduct) {
    val rows = listOf(
        Triple("Brand", competitor.brandName, own.brandName),
        Triple("Company", competitor.company, own.company),
        Triple("Generic", competitor.generic, own.generic),
        Triple(
            "Strength/Form",
            strengthForm(competitor),
            strengthForm(own),
        ),
        Triple("MRP", mrpLabel(competitor), mrpLabel(own)),
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = MlxD.Space3, bottom = MlxD.Space3)
            .clip(RoundedCornerShape(12.dp))
            .background(Mlx.Surface)
            .border(1.dp, Mlx.Brand200, RoundedCornerShape(12.dp)),
    ) {
        // Header row.
        Row(modifier = Modifier.fillMaxWidth().background(Mlx.Brand50)) {
            TableCell(text = "", weight = 0f, color = Mlx.Text400, bold = true)
            TableCell(text = "Competitor", weight = 1f, color = Mlx.Text400, bold = true)
            TableCell(text = "Your Brand", weight = 1f, color = Mlx.Violet, bold = true)
        }
        rows.forEachIndexed { i, (label, compValue, ownValue) ->
            Row(modifier = Modifier.fillMaxWidth()) {
                TableCell(text = label, weight = 0f, color = Mlx.Text400, bold = false)
                TableCell(text = compValue, weight = 1f, color = Mlx.Text600, bold = false)
                TableCell(text = ownValue, weight = 1f, color = Mlx.Violet, bold = true)
            }
            if (i < rows.lastIndex) {
                // Row divider: full width, 1dp, #F1F5F9.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(Mlx.Brand100),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(
    text: String,
    weight: Float,
    color: Color,
    bold: Boolean,
) {
    val modifier = if (weight > 0f) Modifier.weight(weight) else Modifier
    Text(
        text = text.ifBlank { "—" },
        style = MlxType.Footnote.copy(
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        ),
        color = color,
        modifier = modifier.padding(horizontal = 10.dp, vertical = MlxD.Space2),
    )
}

private fun strengthForm(p: MedexProduct): String =
    listOf(p.strength, p.type.ifBlank { p.form }).filter { it.isNotBlank() }.joinToString(" ")

private fun mrpLabel(p: MedexProduct): String {
    val mrp = p.mrp ?: return "—"
    val price = if (mrp % 1.0 == 0.0) mrp.toInt().toString() else mrp.toString()
    return if (p.pack.isBlank()) "$price BDT" else "$price BDT (${p.pack})"
}

private fun formatDifference(unitDifference: Double): String {
    val pct = unitDifference * 100.0
    val rounded = Math.rint(pct * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) "${rounded.toInt()}%" else "$rounded%"
}

/** The green price-position and the two evidence boxes share this frame. */
@Composable
private fun NoteBox(
    icon: ImageVector,
    iconTint: Color,
    background: Color,
    border: Color,
    textColor: Color,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MlxD.Space3)
            .clip(RoundedCornerShape(10.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .padding(horizontal = MlxD.Space3, vertical = MlxD.Space2),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(14.dp),
        )
        Text(text = text, style = MlxType.BodySmall, color = textColor)
    }
}

@Composable
private fun EvidenceBox(
    title: String,
    titleIcon: ImageVector,
    titleColor: Color,
    background: Color,
    border: Color,
    paragraphs: List<String>,
    italic: Boolean = false,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = MlxD.Space3)
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .padding(MlxD.Space3),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 6.dp),
        ) {
            Icon(
                imageVector = titleIcon,
                contentDescription = null,
                tint = titleColor,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = title.uppercase(),
                style = MlxType.SectionLabel,
                color = titleColor,
            )
        }
        paragraphs.forEachIndexed { i, para ->
            Text(
                text = para,
                style = MlxType.BodySmall.copy(
                    fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
                ),
                color = Mlx.Text700,
                modifier = Modifier.padding(bottom = if (i < paragraphs.lastIndex) 6.dp else 0.dp),
            )
        }
    }
}

@Composable
private fun PitchFooterButton(
    text: String,
    icon: ImageVector,
    filled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (filled) Mlx.Danger else Mlx.Surface)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (filled) Color.White else Mlx.Text600,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            style = MlxType.BodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = if (filled) Color.White else Mlx.Text600,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}
