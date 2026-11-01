package com.medlenx.lab.ui.screens.scan

import androidx.compose.foundation.background
import com.medlenx.lab.data.model.GpsSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.repo.PyMath
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.MlxTextField
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxType

/** Location fields captured with a scan, plus the GPS pin. */
data class GeoStripState(
    val upazila: String = "",
    val district: String = "",
    val territory: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val pinnedDistrict: String = "",
    /** Which source produced the pin, so the audit caption can say so. */
    val pinSource: GpsSource? = null,
    val offTerritory: Boolean = false,
    val verdictReason: String = "",
)

/**
 * Preprocess quick bar.
 *
 * Web equivalent: the three inputs (Upazila / District / Territory) and the GPS pin
 * button under the image viewer, which embeds coordinates with the audit so RSM
 * Command can geofence it.
 */
@Composable
fun GeoStrip(
    state: GeoStripState,
    onUpazilaChange: (String) -> Unit,
    onDistrictChange: (String) -> Unit,
    onTerritoryChange: (String) -> Unit,
    onPinGps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Mlx.Brand50)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth < 480.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MlxTextField(state.upazila, onUpazilaChange, Modifier.weight(1f), label = "Upazila")
                        MlxTextField(state.district, onDistrictChange, Modifier.weight(1f), label = "District")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        MlxTextField(state.territory, onTerritoryChange, Modifier.weight(1f), label = "Territory")
                        GpsIconButton(onPinGps)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    MlxTextField(state.upazila, onUpazilaChange, Modifier.weight(1f), label = "Upazila")
                    MlxTextField(state.district, onDistrictChange, Modifier.weight(1f), label = "District")
                    MlxTextField(state.territory, onTerritoryChange, Modifier.weight(1f), label = "Territory")
                    GpsIconButton(onPinGps)
                }
            }
        }

        when {
            state.offTerritory -> StatusPill(
                text = "Off-Territory Audit — ${state.verdictReason}",
                tone = PillTone.Red,
                icon = Icons.Filled.LocationOn,
                modifier = Modifier.fillMaxWidth(),
            )

            state.lat != null && state.lng != null -> Text(
                text = buildString {
                    append(
                        when (val src = state.pinSource) {
                            null -> "GPS pinned · "
                            else -> "Pinned from ${src.label} · "
                        }
                    )
                    append(PyMath.fixedCoords(state.lat, state.lng))
                    if (state.pinnedDistrict.isNotBlank()) append(" · ${state.pinnedDistrict}")
                    if (state.verdictReason.isNotBlank()) append(" · ${state.verdictReason}")
                },
                style = MlxType.Footnote,
                color = Mlx.Ok600,
            )

            else -> Text(
                text = "Pin GPS to geofence this audit against the assigned territory.",
                style = MlxType.Footnote,
                color = Mlx.Text400,
            )
        }
    }
}

/** Square accessible GPS action, aligned beside the territory field. */
@Composable
fun GpsIconButton(onClick: () -> Unit) {
    MlxIconButton(icon = Icons.Filled.LocationOn, contentDescription = "Use GPS for location",
        onClick = onClick, tint = Mlx.Ok500, background = Mlx.Ok50, border = Mlx.Ok200)
}
