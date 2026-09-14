package com.medlenx.lab.ui.screens.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.medlenx.lab.data.model.ConfidenceBand
import com.medlenx.lab.data.model.confidenceBand
import com.medlenx.lab.ui.components.CompanyPill
import com.medlenx.lab.ui.components.CompanyVerification
import com.medlenx.lab.ui.components.ConfidenceBadge
import com.medlenx.lab.ui.theme.Mlx
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
    modifier: Modifier = Modifier,
) {
    val skin = skinFor(data.confidencePct)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(skin.bg, MlxShape.Medium)
            .border(1.dp, skin.border, MlxShape.Medium)
            .padding(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PackImage(modifier = Modifier.size(64.dp))

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
private fun PackImage(modifier: Modifier = Modifier) {
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
