package com.medlenx.lab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Medication
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxType

/**
 * Empty state — a 48dp #F1F5F9 circle holding a #94A3B8 glyph, a 12px slate-500 line
 * and an optional pill CTA. The web app has 15 distinct empty/error strings; every one
 * of them must survive the port verbatim.
 */
@Composable
fun MlxEmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Medication,
    ctaLabel: String? = null,
    onCta: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(Mlx.Brand100, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = Mlx.Brand400, modifier = Modifier.size(20.dp))
        }
        Text(
            text = message,
            style = MlxType.BodySmall,
            color = Mlx.Text500,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        if (ctaLabel != null && onCta != null) {
            MlxButton(
                text = ctaLabel,
                onClick = onCta,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

/** Inline error line — `text-xs text-red-600`. */
@Composable
fun MlxErrorLine(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        style = MlxType.BodySmall,
        color = Mlx.Danger,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp),
    )
}
