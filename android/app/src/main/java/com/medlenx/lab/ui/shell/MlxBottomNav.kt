package com.medlenx.lab.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medlenx.lab.ui.navigation.Destination
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/**
 * Bottom navigation — Figma `BottomNav` (App.tsx:359-390).
 *
 * Bar is 60dp tall with a 1dp #E2E8F0 top border. The **active** item is a filled
 * #0F172A *pill* (`borderRadius:9999`, `padding:"6px 16px"`) that hugs its icon and
 * label, centred inside the cell — not a full-cell rectangle. Active icon is 14dp and
 * the label 10sp/600; inactive icon is 16dp and the label 10sp/500, both #64748B.
 *
 * Five destinations, matching `navItems`. The web's sixth destination (Help) lives
 * behind Settings, exactly as the Figma export does it (App.tsx:2056).
 *
 * The clickable cell keeps the full 60dp height and a fifth of the screen width, so
 * every target stays well above the 48dp minimum even though the visible pill is
 * much smaller.
 */
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
            .background(Color.White),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Destination.bottomBar.forEach { dest ->
            val active = dest == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onSelect(dest) },
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    modifier = Modifier
                        .background(
                            color = if (active) Mlx.Brand900 else Color.Transparent,
                            shape = MlxShape.Pill,
                        )
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Icon(
                        imageVector = dest.icon,
                        contentDescription = dest.label,
                        tint = if (active) Color.White else Mlx.Text500,
                        modifier = Modifier.size(if (active) 14.dp else 16.dp),
                    )
                    Text(
                        text = dest.label,
                        style = MlxType.Footnote.copy(
                            fontSize = 10.sp,
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        ),
                        color = if (active) Color.White else Mlx.Text500,
                    )
                }
            }
        }
    }
}
