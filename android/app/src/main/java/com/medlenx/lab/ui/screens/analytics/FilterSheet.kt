package com.medlenx.lab.ui.screens.analytics

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.local.FilterOptions
import com.medlenx.lab.data.local.FilterState
import com.medlenx.lab.ui.components.ButtonTone
import com.medlenx.lab.ui.components.FlowRowCompat
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxFilterChip
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/** The sheet's date-range chips, mirroring the export's `ranges` array. */
private val dateRanges = listOf(
    "All Time" to null,
    "Last 7 Days" to 7,
    "Last 30 Days" to 30,
    "Last 90 Days" to 90,
    "Last 12 Months" to 365,
)

/** `FilterSheet` in `App.tsx:1212`. */
@Composable
fun FilterSheet(
    options: FilterOptions,
    initial: FilterState,
    onApply: (FilterState) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var draft by remember { mutableStateOf(initial) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Mlx.Text900.copy(alpha = 0.4f))
            .clickable(onClick = onClose),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(MlxShape.Sheet)
                .background(Mlx.Surface)
                // Consume taps so the sheet does not dismiss when tapped.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .verticalScroll(rememberScrollState()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .width(40.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(9999.dp))
                        .background(Mlx.Brand200),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MlxD.ScreenMargin, vertical = MlxD.Space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Filters",
                    style = MlxType.PanelTitle,
                    color = Mlx.Text900,
                    modifier = Modifier.weight(1f),
                )
                MlxButton(text = "Reset", tone = ButtonTone.Outline, onClick = { draft = FilterState.None })
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = MlxD.ScreenMargin)
                    .padding(bottom = MlxD.ScreenMargin),
            ) {
                DropdownField("Territory", "All Territories", options.territories, draft.territory) {
                    draft = draft.copy(territory = it)
                }
                DropdownField("District", "All Districts", options.districts, draft.district) {
                    draft = draft.copy(district = it)
                }
                DropdownField("Specialty", "All Specialties", options.specialties, draft.specialty) {
                    draft = draft.copy(specialty = it)
                }
                DropdownField("MR", "All MRs", options.mrIds, draft.mrId) {
                    draft = draft.copy(mrId = it)
                }

                Spacer(Modifier.height(MlxD.Space3))
                Text(
                    text = "Date Range",
                    style = MlxType.FieldLabel,
                    color = Mlx.Text500,
                    modifier = Modifier.padding(bottom = MlxD.Space2),
                )
                FlowRowCompat(
                    horizontalSpacing = MlxD.Space2,
                    verticalSpacing = MlxD.Space2,
                ) {
                    dateRanges.forEach { (label, value) ->
                        MlxFilterChip(
                            label = label,
                            selected = draft.days == value,
                            onClick = { draft = draft.copy(days = value) },
                        )
                    }
                }

                Spacer(Modifier.height(MlxD.Space4))
                Text(
                    // The web pluralises this; "1 filters active" did not.
                    text = if (draft.activeCount == 1) {
                        "1 filter active"
                    } else {
                        "${draft.activeCount} filters active"
                    },
                    style = MlxType.Footnote,
                    color = Mlx.Text400,
                    modifier = Modifier.padding(bottom = MlxD.Space3),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(MlxD.Space2)) {
                    MlxButton(
                        text = "Cancel",
                        tone = ButtonTone.Outline,
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                    )
                    MlxButton(
                        text = "Apply filters",
                        onClick = { onApply(draft) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * One filter dimension.
 *
 * The export uses a native `<select>`; on Android this is a tappable field that
 * expands into the available values. Selecting the "All …" entry clears the
 * dimension, which is how the Python's empty-string check reads it.
 */
@Composable
private fun DropdownField(
    label: String,
    allLabel: String,
    values: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Text(
        text = label,
        style = MlxType.FieldLabel,
        color = Mlx.Text500,
        modifier = Modifier.padding(bottom = MlxD.Space1),
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Mlx.Surface)
            .clickable { expanded = !expanded }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = selected ?: allLabel,
            style = MlxType.BodySmall,
            color = if (selected == null) Mlx.Text400 else Mlx.Text900,
        )
    }
    if (expanded) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Mlx.Screen)
                .padding(vertical = MlxD.Space1),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        onSelect(null)
                        expanded = false
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(text = allLabel, style = MlxType.BodySmall, color = Mlx.Text500)
            }
            values.forEach { v ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(v)
                            expanded = false
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = v,
                        style = MlxType.BodySmall,
                        fontWeight = if (v == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (v == selected) Mlx.Text900 else Mlx.Text600,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(MlxD.Space3))
}
