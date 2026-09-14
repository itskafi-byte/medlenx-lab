package com.medlenx.lab.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Biotech
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxType

/**
 * The brand mark used in the app bar and on the splash.
 *
 * Web equivalent: a rounded tile with `bg-gradient-to-br from-slate-900 to-blue-800`
 * holding `fa-microscope`, next to "MedLenX Lab" 16px/700 and the sub-label
 * "Prescription Intelligence" 10px slate-500.
 */
@Composable
fun BrandTile(size: Dp = 32.dp, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(size / 4))
            .background(Brush.linearGradient(listOf(Mlx.Brand900, Mlx.Brand700))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Biotech,
            contentDescription = "MedLenX Lab",
            tint = Color.White,
            modifier = Modifier.size(size * 0.55f),
        )
    }
}

/** Full lockup for the splash screen and large headers. */
@Composable
fun BrandLockup(tileSize: Dp = 40.dp, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BrandTile(size = tileSize)
        Column {
            Text(
                text = "MedLenX Lab",
                style = MlxType.CardTitle.copy(fontSize = 16.sp),
                color = Mlx.Text900,
            )
            Text(
                text = "Prescription Intelligence",
                style = MlxType.Footnote,
                color = Mlx.Text500,
            )
        }
    }
}
