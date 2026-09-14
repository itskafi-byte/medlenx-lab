package com.medlenx.lab.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxType
import com.medlenx.lab.ui.theme.MlxShape

enum class ButtonTone {
    /** `bg-slate-900 text-white` — primary action. */
    Primary,

    /** `bg-emerald-600 hover:bg-emerald-700 text-white` — Verify & Save. */
    Success,

    /** `bg-amber-600 text-white` — Queue for training. */
    Warning,

    /** `bg-white border border-slate-200` — Cancel, CSV, Refresh. */
    Outline,

    /** `bg-slate-100 rounded-full` — small refresh pills. */
    Soft,

    /** White fill on a dark hero. */
    OnDark,
}

/**
 * Buttons keep the web app's 12dp radius but grow to a 48dp minimum touch target —
 * the anti-truncation/accessibility rule that overrides the web's 10–14px sizing.
 */
@Composable
fun MlxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: ButtonTone = ButtonTone.Primary,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    enabled: Boolean = true,
    textStyle: TextStyle = MlxType.CardTitle.copy(fontSize = 14.sp),
) {
    val (bg, fg, border) = when (tone) {
        ButtonTone.Primary -> Triple(Mlx.Brand900, Color.White, Mlx.Brand900)
        ButtonTone.Success -> Triple(Mlx.Ok500, Color.White, Mlx.Ok500)
        ButtonTone.Warning -> Triple(Mlx.Warn500, Color.White, Mlx.Warn500)
        ButtonTone.Outline -> Triple(Mlx.Surface, Mlx.Text900, Mlx.Brand200)
        ButtonTone.Soft -> Triple(Mlx.Brand100, Mlx.Text600, Mlx.Brand100)
        ButtonTone.OnDark -> Triple(Color.White, Mlx.Brand900, Color.White)
    }
    val alpha = if (enabled) 1f else 0.4f

    Row(
        modifier = modifier
            .defaultMinSize(minHeight = MlxTouchTarget)
            .background(bg.copy(alpha = alpha), MlxShape.Medium)
            .border(BorderStroke(1.dp, border.copy(alpha = alpha)), MlxShape.Medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint ?: fg,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(text = text, style = textStyle, color = fg.copy(alpha = alpha))
    }
}

/** 32dp square icon button — `w-8 h-8 rounded-lg border`. */
@Composable
fun MlxIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = Mlx.Text600,
    background: Color = Mlx.Surface,
    border: Color = Mlx.Brand200,
) {
    Box(
        modifier = modifier
            .size(MlxTouchTarget)
            .background(background, MlxShape.Chip)
            .border(BorderStroke(1.dp, border), MlxShape.Chip)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Segmented tab strip — Figma `HubScreen` (App.tsx:1574-1580).
 *
 * Container: `background:#F1F5F9`, `borderRadius:12`, `padding:4`, `gap:2`,
 * `overflowX:auto`. Item: `padding:"7px 12px"`, `borderRadius:10`, `fontSize:11`,
 * `fontWeight:600`; **active is white with a `0 1px 4px rgba(0,0,0,0.08)` shadow**,
 * inactive is transparent #475569. Note this is the opposite of the old port, which
 * drew the active segment as slate-900 with white text.
 *
 * Deliberate deviation: Figma's tabs are ~26dp tall; the 48dp minimum touch target is
 * kept because the anti-truncation contract requires every control to stay reachable.
 * The strip therefore renders taller than the mock while keeping identical colours,
 * radii, spacing and type.
 */
@Composable
fun MlxSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Mlx.Brand100, MlxShape.Medium)
            .padding(4.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = MlxTouchTarget)
                    .then(
                        if (selected) Modifier.shadow(2.dp, MlxShape.Tab) else Modifier
                    )
                    .background(if (selected) Color.White else Color.Transparent, MlxShape.Tab)
                    .clickable { onSelect(index) }
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    style = MlxType.MicroPill.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                    color = if (selected) Mlx.Brand900 else Mlx.Text600,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Filter chip — active `bg-slate-900 text-white`, inactive white + slate border. */
@Composable
fun MlxFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier = modifier
            .defaultMinSize(minHeight = MlxTouchTarget)
            .background(if (selected) Mlx.Brand900 else Mlx.Surface, shape)
            .border(
                BorderStroke(1.dp, if (selected) Mlx.Brand900 else Mlx.Brand200),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MlxType.MicroPill.copy(fontSize = 11.sp),
            color = if (selected) Color.White else Mlx.Text600,
        )
    }
}

/** 48dp, from MlxD.TouchTarget. Duplicated locally to keep imports flat. */
private val MlxTouchTarget = com.medlenx.lab.ui.theme.MlxD.TouchTarget
