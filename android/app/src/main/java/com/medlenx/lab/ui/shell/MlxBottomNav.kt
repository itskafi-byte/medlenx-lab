package com.medlenx.lab.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medlenx.lab.ui.components.FrostedIcon
import com.medlenx.lab.ui.navigation.Destination
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType

/** Four full-height, accessible tabs; only the selected icon has a tinted tile. */
@Composable
fun MlxBottomNav(
    selected: Destination?,
    onSelect: (Destination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(MlxD.BottomNavHeight)
            .background(Color.White)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Destination.bottomBar.forEach { dest ->
            val active = dest == selected
            val color = when (dest) {
                Destination.Scan -> Color(0xFF0EA5E9)
                Destination.Analytics -> Color(0xFF8B5CF6)
                Destination.Hub -> Color(0xFF10B981)
                Destination.Team -> Color(0xFFF59E0B)
                else -> Mlx.Text500
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .selectable(selected = active, role = Role.Tab, onClick = { onSelect(dest) }),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (active) {
                        FrostedIcon(icon = dest.icon, baseColor = color)
                    } else {
                        Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            Icon(dest.icon, contentDescription = null, tint = Mlx.Text500, modifier = Modifier.size(24.dp))
                        }
                    }
                    Text(
                        text = dest.label,
                        style = MlxType.Footnote.copy(
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (active) color else Mlx.Text500,
                    )
                }
            }
        }
    }
}
