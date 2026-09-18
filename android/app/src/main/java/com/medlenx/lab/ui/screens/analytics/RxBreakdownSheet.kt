package com.medlenx.lab.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.medlenx.lab.data.local.ScannedMedicineEntity
import com.medlenx.lab.ui.components.MedicineThumb
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/**
 * "Tap for item breakdown" — the medicine list behind one recent prescription.
 *
 * The web build ships the caption but never wires its own `onSelect`, so the row is
 * inert there; this is the screen that caption promises. Each line shows the pack photo
 * (already stored on the row by the scan) next to the brand, strength and manufacturer.
 */
@Composable
fun RxBreakdownSheet(
    doctor: String,
    items: List<ScannedMedicineEntity>,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            MlxCard {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Item breakdown",
                                style = MlxType.CardTitle.copy(fontWeight = FontWeight.SemiBold),
                                color = Mlx.Text900,
                            )
                            Text(
                                text = doctor,
                                style = MlxType.Meta,
                                color = Mlx.Text500,
                            )
                        }
                        MlxIconButton(Icons.Filled.Close, "Close", onDismiss)
                    }

                    if (items.isEmpty()) {
                        Text(
                            text = "No itemised medicines were stored for this prescription.",
                            style = MlxType.BodySmall,
                            color = Mlx.Text500,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            items.forEach { item -> BreakdownRow(item) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakdownRow(item: ScannedMedicineEntity) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Mlx.Screen, MlxShape.Small)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MedicineThumb(
            name = item.brandName,
            imageUrl = item.imageUrl,
            size = 48.dp,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.brandName,
                style = MlxType.BodySmall,
                color = Mlx.Text900,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(item.strength, item.dosageForm, item.dosage)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                    .ifBlank { item.generic },
                style = MlxType.Meta,
                color = Mlx.Text500,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (item.companyName.orEmpty().isNotBlank()) {
                Text(
                    text = item.companyName.orEmpty(),
                    style = MlxType.MicroPill,
                    color = Mlx.Text600,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "${(item.confidenceScore * 100).toInt()}%",
            style = MlxType.MicroPill,
            color = if (item.confidenceScore >= 0.8) Mlx.Ok600 else Mlx.Warn600,
        )
    }
}
