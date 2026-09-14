package com.medlenx.lab.ui.screens.scan

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.components.ButtonTone
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxTextField
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/** Location fields captured with a scan, plus the GPS pin. */
data class GeoStripState(
    val upazila: String = "",
    val district: String = "",
    val territory: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val pinnedDistrict: String = "",
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MlxTextField(
                value = state.upazila,
                onValueChange = onUpazilaChange,
                label = "Upazila",
                modifier = Modifier.weight(1f),
            )
            MlxTextField(
                value = state.district,
                onValueChange = onDistrictChange,
                label = "District",
                modifier = Modifier.weight(1f),
            )
            MlxTextField(
                value = state.territory,
                onValueChange = onTerritoryChange,
                label = "Territory",
                modifier = Modifier.weight(1f),
            )
            MlxButton(
                text = "GPS",
                onClick = onPinGps,
                tone = ButtonTone.Outline,
                icon = Icons.Filled.LocationOn,
                iconTint = Mlx.Ok500,
            )
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
                    append("GPS pinned · ")
                    append("%.4f, %.4f".format(state.lat, state.lng))
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
