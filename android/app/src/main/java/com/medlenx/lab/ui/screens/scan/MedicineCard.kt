package com.medlenx.lab.ui.screens.scan

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.Substitution
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.medlenx.lab.data.model.ConfidenceBand
import com.medlenx.lab.data.model.confidenceBand
import com.medlenx.lab.ui.components.CompanyPill
import com.medlenx.lab.ui.components.CompanyVerification
import com.medlenx.lab.ui.components.ConfidenceBadge
import com.medlenx.lab.ui.components.MedicineThumb
import com.medlenx.lab.data.repo.PyMath
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/**
 * One verified medicine row.
 *
 * `rawText`, `matchType` and `lineRef` build the card footer; the web renders them as
 * `Raw: "seclo…" • MedEx exact match • #L2`.
 */
data class MedicineCardData(
    val brand: String,
    val confidencePct: Int,
    val type: String,
    val ingredient: String,
    val strength: String,
    val dosage: String,
    val company: String,
    val companyVerification: CompanyVerification,
    val catalogueNote: String? = null,
    val rawText: String? = null,
    val matchType: String? = null,
    val lineRef: String? = null,
    val bbox: List<Float> = emptyList(),
    /** MedEx pack photo URL resolved for this SKU, when the catalogue matched it. */
    val packImage: String? = null,
    /**
     * Every catalogue variant of the matched brand except the one chosen, so the
     * officer can correct the strength, the dosage form or the manufacturer without
     * retyping the brand.
     *
     * The web renders these directly under the company badge, in the order
     * company → conflict note → alternatives (`index.html:1962-1964`). `MedicineEnricher`
     * has always computed them; `toCardData()` did not carry them, so they stopped at
     * this boundary and no picker could be built.
     */
    val alternatives: List<MedexProduct> = emptyList(),
    /**
     * The competitor → own-brand card, or null when this medicine is already the
     * officer's own or the catalogue holds no equivalent.
     *
     * Built by `Intelligence.genericSubstitution` during enrichment and rendered in
     * the web's verification card between the alternatives picker and the DGDA flag
     * (`index.html:1965`). Like [alternatives] it was computed and then dropped at the
     * card boundary, so the review stream had no substitution card at all.
     */
    val substitution: Substitution? = null,
)

/** The three card variants Figma derives from the confidence band. */
private data class CardSkin(val bg: Color, val border: Color)

private fun skinFor(confidencePct: Int): CardSkin = when (confidenceBand(confidencePct)) {
    // Exhaustive over the band, so a future fourth state is a compile error, not a
    // silently mis-skinned card.
    ConfidenceBand.ManualFlag -> CardSkin(Mlx.Warn50, Mlx.Warn200)     // amber wash
    ConfidenceBand.AiGuess -> CardSkin(Mlx.GuessBg, Mlx.GuessBorder)   // orange wash
    ConfidenceBand.High -> CardSkin(Mlx.Surface, Mlx.Brand200)
}

/**
 * Medicine card — Figma `MedicineCardComponent` (App.tsx:499-565).
 *
 * Card: radius 12, padding 12, colour driven by the confidence band. Pack image 64dp
 * with a 20dp image badge hanging 6dp off its bottom-right corner. Brand is an
 * **editable** field with a 2dp bottom rule; the dosage tile is editable too. The 2x2
 * fact tiles each carry a 9sp uppercase label at 0.08em tracking.
 */
@Composable
fun MedicineCard(
    data: MedicineCardData,
    onBrandChange: (String) -> Unit,
    onDosageChange: (String) -> Unit,
    onVerifyAgainstMedex: () -> Unit,
    onReportMisId: () -> Unit,
    onSelected: () -> Unit = {},
    suggestions: List<MedexProduct> = emptyList(),
    onPickSuggestion: (MedexProduct) -> Unit = {},
    onPickAlternative: (MedexProduct) -> Unit = {},
    onCopyPitch: (String) -> Unit = {},
    onMarkWon: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val skin = skinFor(data.confidencePct)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelected)
            .background(skin.bg, MlxShape.Medium)
            .border(1.dp, skin.border, MlxShape.Medium)
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PackImage(
                company = data.company,
                packImage = data.packImage,
                modifier = Modifier.size(64.dp),
            )

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    UnderlinedTextField(
                        value = data.brand,
                        onValueChange = onBrandChange,
                        modifier = Modifier.weight(1f),
                        textStyle = MlxType.BrandName.copy(
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Mlx.Text900,
                        ),
                    )
                    ConfidenceBadge(percent = data.confidencePct)
                }

                if (suggestions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .background(Mlx.Surface, MlxShape.Small)
                            .border(1.dp, Mlx.Brand200, MlxShape.Small),
                    ) {
                        suggestions.forEach { p ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onPickSuggestion(p) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                AsyncImage(
                                    model = p.packImage?.takeIf { it.isNotBlank() }
                                        ?: p.imageUrl?.takeIf { it.isNotBlank() }
                                        ?: ("https://logo.clearbit.com/" +
                                            p.company.trim().lowercase().replace(Regex("[^a-z0-9]"), "") + ".com"),
                                    contentDescription = "Pack image for ${p.brandName}",
                                    // Every catalogue row ships a MedEx pack photo
                                    // (medex.com.bd/storage/images/packaging/...). At
                                    // 20dp it was too small to read as a pack at all,
                                    // which looked exactly like "no image is shown".
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(Mlx.Brand50, MlxShape.Small),
                                )
                                // Stacked single lines, not side by side. Unwighted,
                                // the generic name claimed its full intrinsic width
                                // and squeezed the brand into a column one word tall.
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = p.brandName,
                                        style = MlxType.BodySmall,
                                        color = Mlx.Text900,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = p.generic.ifBlank { p.ingredient }
                                            .ifBlank { p.strength.ifBlank { p.form } },
                                        style = MlxType.MicroPill,
                                        color = Mlx.Text600,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                    }
                }

                // 2x2 fact tiles. Two weighted Rows rather than an experimental grid.
                Column(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FactTile(
                            label = "Type",
                            labelColor = Mlx.Brand500,
                            bg = Mlx.Accent50,
                            border = Mlx.Accent100,
                            modifier = Modifier.weight(1f),
                            leadingIcon = Icons.Filled.Medication,
                            value = data.type,
                        )
                        FactTile(
                            label = "Ingredient",
                            labelColor = Mlx.Brand700,
                            bg = Mlx.Accent50,
                            border = Mlx.Accent100,
                            modifier = Modifier.weight(1f),
                            value = data.ingredient,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FactTile(
                            label = "MG/Strength",
                            labelColor = Mlx.Ok500,
                            bg = Mlx.Ok50,
                            border = Mlx.Ok100,
                            modifier = Modifier.weight(1f),
                            value = data.strength,
                            valueStyle = MlxType.Body.copy(fontWeight = FontWeight.Bold),
                        )
                        // Dosage is the second editable field (placeholder "1+0+1").
                        FactTile(
                            label = "Dosage",
                            labelColor = Mlx.Warn500,
                            bg = Mlx.Warn50,
                            border = Mlx.Warn100,
                            modifier = Modifier.weight(1f),
                            editableValue = data.dosage,
                            onEditableValueChange = onDosageChange,
                            placeholder = "1+0+1",
                        )
                    }
                }
            }
        }

        CompanyPill(
            name = data.company,
            verification = data.companyVerification,
            modifier = Modifier.padding(top = 10.dp, bottom = 8.dp),
        )

        if (confidenceBand(data.confidencePct) != ConfidenceBand.High) {
            NoteStrip(
                icon = Icons.Filled.SmartToy,
                fg = Mlx.GuessAccent,
                bg = Mlx.GuessBg,
                border = Mlx.GuessBorder,
                leadText = "AI Guess — ",
                actionText = "Tap to verify against Medex",
                onAction = onVerifyAgainstMedex,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        data.catalogueNote?.let { note ->
            NoteStrip(
                icon = Icons.Filled.Description,
                fg = Mlx.Warn600,
                bg = Mlx.Warn50,
                border = Mlx.Warn100,
                leadText = note,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        AlternativesPicker(
            alternatives = data.alternatives,
            onPick = onPickAlternative,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        data.substitution?.let { substitution ->
            SubstitutionCard(
                substitution = substitution,
                onCopyPitch = onCopyPitch,
                onMarkWon = onMarkWon,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        // Footer: provenance on the left, escalation on the right.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            val provenance = listOfNotNull(
                data.rawText?.let { "Raw: \"${it.lowercase()}…\"" },
                data.matchType,
                data.lineRef,
            ).joinToString("  •  ")
            Text(
                text = provenance,
                style = MlxType.MicroPill.copy(fontWeight = FontWeight.Normal),
                color = Mlx.Brand400,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "Report mis-ID",
                style = MlxType.MicroPill.copy(
                    fontWeight = FontWeight.Normal,
                    textDecoration = TextDecoration.Underline,
                ),
                color = Mlx.Warn600,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clickable(onClick = onReportMisId),
            )
        }
    }
}

/**
 * 64dp pack-image placeholder — `#F8FAFC` with a 2dp `#F1F5F9` border, radius 10, and
 * a 20dp circular badge hanging 6dp off the bottom-right corner.
 */
@Composable
private fun PackImage(
    company: String,
    packImage: String? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(Mlx.Brand50, RoundedCornerShape(10.dp))
                .border(2.dp, Mlx.Brand100, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Medication,
                contentDescription = "Pack image",
                tint = Mlx.Brand400,
                modifier = Modifier.size(28.dp),
            )
            // Online live fetch of the manufacturer logo; when offline or the company
            // is unidentified the icon above remains as the offline fallback.
            // Prefer the MedEx pack photo resolved for this exact SKU. The Clearbit
            // logo is only a manufacturer fallback and needs a company name, which an
            // AI-detected line often does not carry -- that is why a detected medicine
            // could sit here with no image at all.
            val photo = packImage?.takeIf { it.isNotBlank() }
                ?: company.takeIf { it.isNotBlank() }?.let { name ->
                    "https://logo.clearbit.com/" +
                        name.trim().lowercase().replace(Regex("[^a-z0-9]"), "") + ".com"
                }
            if (photo != null) {
                AsyncImage(
                    model = photo,
                    contentDescription = if (packImage.isNullOrBlank()) {
                        "Company logo"
                    } else {
                        "Pack image"
                    },
                    // Fills the 64dp tile: the photo is the point of the tile, not a
                    // 40dp badge floating inside it.
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Figma: bottom:-6, right:-6, so the badge hangs outside the tile.
                .size(20.dp)
                .offset(x = 6.dp, y = 6.dp)
                .background(Color.White, CircleShape)
                .border(1.dp, Mlx.Brand200, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.Image,
                contentDescription = "Pack photo available",
                tint = Mlx.Text600,
                modifier = Modifier.size(9.dp),
            )
        }
    }
}

/** One cell of the 2x2 fact grid. Value may be static or editable. */
@Composable
private fun FactTile(
    label: String,
    labelColor: Color,
    bg: Color,
    border: Color,
    modifier: Modifier = Modifier,
    value: String? = null,
    valueStyle: androidx.compose.ui.text.TextStyle = MlxType.Meta,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    editableValue: String? = null,
    onEditableValueChange: ((String) -> Unit)? = null,
    placeholder: String? = null,
) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = modifier
            .background(bg, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = MlxType.RegulatoryPill.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.08.em,
            ),
            color = labelColor,
        )
        if (editableValue != null && onEditableValueChange != null) {
            BasicTextField(
                value = editableValue,
                onValueChange = onEditableValueChange,
                singleLine = true,
                textStyle = MlxType.Meta.copy(color = Mlx.Text900),
                decorationBox = { inner ->
                    if (editableValue.isEmpty() && placeholder != null) {
                        Text(text = placeholder, style = MlxType.Meta, color = Mlx.Brand400)
                    }
                    inner()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.padding(top = 2.dp),
            ) {
                if (leadingIcon != null) {
                    Icon(
                        leadingIcon,
                        contentDescription = null,
                        tint = labelColor,
                        modifier = Modifier.size(11.dp),
                    )
                }
                Text(text = value.orEmpty(), style = valueStyle, color = Mlx.Text900)
            }
        }
    }
}

/** AI-guess and catalogue-note strips: radius 8, `6x10` padding, 11sp. */
@Composable
private fun NoteStrip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    fg: Color,
    bg: Color,
    border: Color,
    leadText: String,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val shape = MlxShape.Chip
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(bg, shape)
            .border(1.dp, border, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
        Text(text = leadText, style = MlxType.Meta, color = fg)
        if (actionText != null && onAction != null) {
            // CSS uses `underline dotted`; Compose only offers a solid underline.
            Text(
                text = actionText,
                style = MlxType.Meta.copy(textDecoration = TextDecoration.Underline),
                color = fg,
                modifier = Modifier.clickable(onClick = onAction),
            )
        }
    }
}

/** Editable text over a 2dp bottom rule — Figma's brand input. */
@Composable
private fun UnderlinedTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    textStyle: androidx.compose.ui.text.TextStyle = MlxType.Body,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        modifier = modifier
            .drawBehind {
                drawLine(
                    color = Mlx.Brand200,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 2.dp.toPx(),
                )
            }
            .padding(vertical = 2.dp),
    )
}

/**
 * "N other matches for this brand" — the web's `<details>` block (`index.html:1909`).
 *
 * A detected brand can exist in the catalogue several times over: the same name from
 * a different manufacturer, or the same manufacturer at another strength or dosage
 * form. When the automatic pick lands on the wrong one, the brand was already right —
 * so the correction belongs here, next to the badge, rather than in a retyped name.
 *
 * Collapsed until asked, like the web's `<details>`, and it folds itself away once a
 * variant is chosen so the card the officer is reading is not left covered by the list
 * they picked from. Only the chosen variant's fields are rewritten; see
 * `ScanViewModel.applyAlternative`.
 */
@Composable
private fun AlternativesPicker(
    alternatives: List<MedexProduct>,
    onPick: (MedexProduct) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (alternatives.isEmpty()) {
        return
    }
    // Per-card state, which is safe here because the review list is a Column inside
    // one verticalScroll, not a LazyColumn: composition slots are not recycled, so
    // this cannot attach itself to a neighbour's card.
    var expanded by remember { mutableStateOf(false) }
    // The web pluralises the same way, and only ever at two or more.
    val summary = "${alternatives.size} other match" +
        (if (alternatives.size > 1) "es" else "") + " for this brand"

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = summary,
                style = MlxType.MicroPill.copy(fontWeight = FontWeight.SemiBold),
                color = Mlx.Indigo,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = if (expanded) {
                    "Hide the other matches for this brand"
                } else {
                    "Show the other matches for this brand"
                },
                tint = Mlx.Indigo,
                modifier = Modifier.size(16.dp),
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                alternatives.forEach { alt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Mlx.Screen, MlxShape.Small)
                            .border(1.dp, Mlx.Brand200, MlxShape.Small)
                            .clickable {
                                onPick(alt)
                                expanded = false
                            }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = alt.company.ifBlank { "Unknown company" },
                            style = MlxType.MicroPill.copy(fontWeight = FontWeight.SemiBold),
                            color = Mlx.IndigoDeep,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // `strength` and `type` are the two the officer is choosing
                        // between; a blank one collapses rather than leaving a stray
                        // separator, which is how the web's template reads too.
                        Text(
                            text = listOf(alt.strength, alt.type)
                                .filter { it.isNotBlank() }
                                .joinToString(" "),
                            style = MlxType.MicroPill,
                            color = Mlx.Text500,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Competitor → own-brand substitution card — the web's `substitutionCard`
 * (`index.html:1696`), rendered in the review card at `index.html:1965`.
 *
 * Two product cells with a transfer arrow between them, the price position as a
 * per-unit delta, and the two-sentence pitch the officer reads out. The web's wording
 * for the price is a **BDT difference, not a percentage** (`unit_difference_label`:
 * "12.50 BDT lower per unit"), so the badge says that and nothing more.
 *
 * Both actions are the web's: `Copy pitch` writes the script to the clipboard, and
 * `Mark as won` records the conversion. They are the reason this card exists in the
 * review stream rather than only in the audit drawer — a rep reading the pitch has to
 * be able to take it away while the prescription is still on screen.
 */
@Composable
private fun SubstitutionCard(
    substitution: Substitution,
    onCopyPitch: (String) -> Unit,
    onMarkWon: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val competitor = substitution.competitor
    val own = substitution.ownBrand
    // The web gates the saving/premium suffix on both gazette prices being known, and
    // treats a zero delta as neither: `unit_difference < 0 ? ' (saving)' : > 0 ? ' (premium)' : ''`.
    // Without that guard a card whose prices are unknown would still be labelled.
    val bothPrices = competitor.mrp != null && own.mrp != null
    val priceSuffix = when {
        !bothPrices -> ""
        substitution.unitDifference < 0 -> " (saving)"
        substitution.unitDifference > 0 -> " (premium)"
        else -> ""
    }
    val priceColor = if (substitution.unitDifference < 0) Mlx.Ok600 else Mlx.Warn500

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Mlx.BlueBg.copy(alpha = 0.60f), MlxShape.Medium)
            .border(1.dp, Mlx.Blue200, MlxShape.Medium)
            .padding(MlxD.Space3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.SwapHoriz,
                contentDescription = null,
                tint = Mlx.Brand600,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "GENERIC SUBSTITUTION",
                style = MlxType.RegulatoryPill.copy(letterSpacing = 0.08.em),
                color = Mlx.Brand600,
                // Weighted rather than paired with a Spacer, so the trailing label
                // sits on the right without another import in this file.
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "MPO Smart Pitch ready",
                style = MlxType.RegulatoryPill,
                color = Mlx.Brand500,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MlxD.Space2),
            horizontalArrangement = Arrangement.spacedBy(MlxD.Space2),
        ) {
            ProductCell(product = competitor, own = false, modifier = Modifier.weight(1f))
            // The arrow straddles the gap between the cells, as the web's absolutely
            // positioned badge does (`-left-2 top-1/2`), so it reads as one product
            // becoming the other rather than as a decoration inside the green cell.
            Box(modifier = Modifier.weight(1f)) {
                ProductCell(product = own, own = true, modifier = Modifier.fillMaxWidth())
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = (-8).dp)
                        .size(16.dp)
                        .background(Mlx.Ok500, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp),
                    )
                }
            }
        }

        if (substitution.unitDifferenceLabel.isNotBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = MlxD.Space2),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MonetizationOn,
                    contentDescription = null,
                    tint = priceColor,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = substitution.unitDifferenceLabel + priceSuffix,
                    style = MlxType.Footnote.copy(fontWeight = FontWeight.SemiBold),
                    color = priceColor,
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = MlxD.Space2)
                .background(Mlx.Surface, MlxShape.Small)
                .border(1.dp, Mlx.Blue100, MlxShape.Small)
                .padding(MlxD.Space2),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Campaign,
                    contentDescription = null,
                    tint = Mlx.Brand600,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = "SMART PITCH NOTE",
                    style = MlxType.RegulatoryPill.copy(letterSpacing = 0.08.em),
                    color = Mlx.Brand600,
                )
            }
            Text(
                text = substitution.pitch,
                style = MlxType.Footnote,
                color = Mlx.Text700,
                modifier = Modifier.padding(top = MlxD.Space1),
            )
            Row(
                modifier = Modifier.padding(top = MlxD.Space1 + MlxD.Space1),
                horizontalArrangement = Arrangement.spacedBy(MlxD.Space1 + MlxD.Space1),
            ) {
                PitchActionButton(
                    text = "Copy pitch",
                    icon = Icons.Filled.ContentCopy,
                    background = Mlx.Brand600,
                    onClick = { onCopyPitch(substitution.pitch) },
                )
                PitchActionButton(
                    text = "Mark as won",
                    icon = Icons.Filled.CheckCircle,
                    background = Mlx.Ok500,
                    onClick = onMarkWon,
                )
            }
        }
    }
}

/** One side of the substitution card: pack photo, brand, and what identifies it. */
@Composable
private fun ProductCell(
    product: MedexProduct,
    own: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = modifier
            .background(if (own) Mlx.Ok50 else Mlx.Surface, shape)
            .border(1.dp, if (own) Mlx.Ok200 else Mlx.Brand200, shape)
            .padding(MlxD.Space2),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MedicineThumb(
            name = product.brandName,
            imageUrl = product.imageUrl?.takeIf { it.isNotBlank() } ?: product.packImage,
            size = 48.dp,
        )
        Text(
            text = product.brandName,
            style = MlxType.BodySmall.copy(fontWeight = FontWeight.Bold),
            color = if (own) Mlx.Ok600 else Mlx.Text900,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = MlxD.Space1),
        )
        if (own) {
            Text(
                text = "Our portfolio",
                style = MlxType.RegulatoryPill,
                color = Mlx.Ok500,
            )
        } else {
            // The web prints only the manufacturer's first word here (`c.company.split(' ')[0]`)
            // to keep the narrow cell to one line - "Square", not "Square Pharmaceuticals Ltd.".
            Text(
                text = product.company.trim().split(" ").firstOrNull().orEmpty(),
                style = MlxType.Footnote,
                color = Mlx.Text500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = listOf(product.strength, product.type)
                .filter { it.isNotBlank() }
                .joinToString(" "),
            style = MlxType.Footnote,
            color = if (own) Mlx.Text500 else Mlx.Text400,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        product.mrp?.let { mrp ->
            // `toFixed(2)`, so 12.5 prints as "12.50" - PyMath.fixed2 is the Locale.US
            // formatter the rest of the port already uses for the same reason.
            Text(
                text = "BDT ${PyMath.fixed2(mrp)}",
                style = MlxType.RegulatoryPill.copy(fontWeight = FontWeight.SemiBold),
                color = if (own) Mlx.Ok600 else Mlx.Text600,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * The card's two small filled buttons.
 *
 * Not `MlxButton`: the web's are 9px in a 24px pill, and `MlxButton` has no blue tone
 * — it would have meant either a wrong colour or a new shared enum member for one
 * caller. These carry the web's exact fills and stay above a tappable height.
 */
@Composable
private fun PitchActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    background: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 32.dp)
            .background(background, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = MlxD.Space2, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(12.dp),
        )
        Text(
            text = text,
            style = MlxType.RegulatoryPill,
            color = Color.White,
        )
    }
}
