package com.medlenx.lab.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Wrapping row used wherever the web app used `flex flex-wrap gap-1`.
 *
 * Wrapping is load-bearing for the anti-truncation contract: regulatory pills and
 * filter chips must never be clipped, they must flow to the next line.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FlowRowCompat(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 4.dp,
    verticalSpacing: Dp = 4.dp,
    content: @Composable () -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
    ) { content() }
}
