package com.medlenx.lab.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxType

/** Base white card — `bg-white rounded-xl border border-slate-200 shadow-sm`. */
@Composable
fun MlxCard(
    modifier: Modifier = Modifier,
    padding: androidx.compose.ui.unit.Dp = MlxD.CardPadding,
    shape: androidx.compose.ui.unit.Dp = 12.dp,
    background: Color = Mlx.Surface,
    borderColor: Color = Mlx.Brand200,
    content: @Composable ColumnScope.() -> Unit,
) {
    val s = RoundedCornerShape(shape)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(s)
            .background(background)
            .border(BorderStroke(1.dp, borderColor), s)
            .padding(padding),
        content = content,
    )
}

/** Section header with a coloured leading icon — `text-sm font-semibold` + `fas`. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    iconTint: Color = Mlx.Brand600,
    subtitle: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MlxType.CardTitle)
            if (subtitle != null) {
                Text(subtitle, style = MlxType.Meta, modifier = Modifier.padding(top = 2.dp))
            }
        }
        trailing?.invoke()
    }
}

/** Tinted icon tile used in the top-right of a KPI card — `w-10 h-10 rounded-xl`. */
@Composable
fun TintedIconTile(
    icon: ImageVector,
    tint: Color,
    background: Color,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 40.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/**
 * KPI card. Web layout: 11px grey label, 24px/700 value, delta chip, 40dp tinted
 * icon tile top-right, optional micro-pill row and a 6dp progress track.
 */
@Composable
fun KpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    delta: String? = null,
    deltaPositive: Boolean = true,
    caption: String? = null,
    microPills: List<String> = emptyList(),
    progress: Float? = null,
) {
    MlxCard(modifier = modifier, padding = 20.dp) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MlxType.Meta, color = Mlx.Text500)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(value, style = MlxType.KpiValue)
                    if (delta != null) {
                        Text(
                            text = delta,
                            style = MlxType.Delta,
                            color = if (deltaPositive) Mlx.Ok500 else Mlx.Danger,
                        )
                    }
                }
                if (caption != null) Text(caption, style = MlxType.Meta, color = Mlx.Text500)
            }
            if (icon != null) {
                TintedIconTile(icon = icon, tint = Mlx.Brand700, background = Mlx.Accent50)
            }
        }
        if (microPills.isNotEmpty()) {
            FlowRowCompat(Modifier.padding(top = 12.dp), 8.dp, 8.dp) {
                microPills.forEach { StatusPill(it, PillTone.Slate) }
            }
        }
        if (progress != null) {
            ProgressTrack(progress = progress, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

/** Mini KPI tile — `rounded-xl border p-3` with a 10px label and 20px/700 value. */
@Composable
fun MiniKpiTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    labelColor: Color = Mlx.Text500,
    valueColor: Color = Mlx.Text900,
    background: Color = Mlx.Surface,
    borderColor: Color = Mlx.Brand200,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(label, style = MlxType.Footnote, color = labelColor)
        Text(value, style = MlxType.MiniKpiValue, color = valueColor)
    }
}

/**
 * 6dp track.
 *
 * The fill is a flat colour: the web's bars do not change hue with their value. Its
 * `#kpiMarketBar` is `bg-emerald-600`, its target-progress bar is the brand accent
 * (`--brand-accent`, `#059669`), its scan-progress bar is `bg-brand-600` and its
 * doctor-leaderboard bar is `bg-emerald-500` - and where the web *does* colour by
 * threshold it colours the number beside the bar, not the bar (the target percentage
 * text, and the leaderboard's "N% conv"). This used to invent thresholds
 * (emerald at 100%, amber at 50%, rose below) and so painted a 14% Target Share bar
 * pink under a green delta, and the scan bar rose at 15%.
 *
 * [fillColor] defaults to emerald-600, the web's brand accent; callers whose web
 * counterpart is a different flat colour pass it (the scan bar is brand blue).
 */
@Composable
fun ProgressTrack(
    progress: Float,
    modifier: Modifier = Modifier,
    thickness: androidx.compose.ui.unit.Dp = MlxD.ProgressBarThin,
    fillColor: Color? = null,
    trackColor: Color = Mlx.Brand200,
) {
    val clamped = progress.coerceIn(0f, 1f)
    val fill = fillColor ?: Mlx.Ok500
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .clip(RoundedCornerShape(percent = 50))
            .background(trackColor),
    ) {
        Box(
            Modifier
                .fillMaxWidth(clamped)
                .height(thickness)
                .clip(RoundedCornerShape(percent = 50))
                .background(fill),
        )
    }
}

/** Dark gradient hero — `bg-gradient-to-r from-slate-900 to-blue-800 rounded-2xl`. */
@Composable
fun DarkHero(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(Mlx.Brand900, Mlx.Brand800)))
            .padding(MlxD.PanelPadding),
    ) {
        Column(content = content)
    }
}
