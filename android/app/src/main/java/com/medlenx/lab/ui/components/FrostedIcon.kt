package com.medlenx.lab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** A decorative icon in a translucent, color-tinted container. */
@Composable
fun FrostedIcon(
    icon: ImageVector,
    baseColor: Color,
    modifier: Modifier = Modifier,
    iconSize: Dp = 24.dp,
    containerSize: Dp = 40.dp,
    shape: Shape = RoundedCornerShape(12.dp),
) {
    Box(
        modifier = modifier.size(containerSize).clip(shape).background(baseColor.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = baseColor, modifier = Modifier.size(iconSize))
    }
}
