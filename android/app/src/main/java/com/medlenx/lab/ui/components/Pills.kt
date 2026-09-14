package com.medlenx.lab.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.theme.Mlx

/** Pill tone. Each maps to one exact colour triple from the web app. */
enum class PillTone(val bg: Color, val fg: Color, val border: Color) {
    Emerald(Mlx.Ok50, Mlx.Ok600, Mlx.Ok200),
    EmeraldSolid(Mlx.Ok100, Color(0xFF047857), Color(0xFF6EE7B7)),
    Amber(Mlx.Warn50, Mlx.Warn600, Mlx.Warn200),
    AmberSolid(Mlx.Warn100, Mlx.WarnDeep, Color(0xFFFCD34D)),
    Orange(Mlx.GuessBg, Mlx.GuessText, Mlx.GuessBorder),
    Red(Mlx.DangerBg, Mlx.DangerText, Mlx.DangerBorder),
    RedSoft(Mlx.DangerSoftBg, Mlx.DangerText, Mlx.DangerSoftBorder),
    Blue(Mlx.BlueBg, Mlx.BlueText, Mlx.Accent100),
    BlueSolid(Mlx.Blue100, Color(0xFF1D4ED8), Mlx.Blue300),
    Violet(Mlx.VioletBg, Mlx.VioletText, Mlx.VioletBorder),
    Slate(Mlx.Brand100, Mlx.Text600, Mlx.Brand200),
    Dark(Mlx.Brand900, Color.White, Mlx.Brand900),
    Neutral(Mlx.Surface, Mlx.Text600, Mlx.Brand200),
}

/**
 * Status pill — `text-[10px] font-semibold`, radius full, 1px border.
 *
 * Web classes: `px-2 py-0.5 rounded-full border text-[10px] font-semibold`.
 */
@Composable
fun StatusPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    /**
     * Optional 6dp status dot drawn before the content — Figma's "Online" pill is
     * `<StatusPill variant="emerald"><span width:6 height:6 borderRadius:50%
     * background:#047857 marginRight:3/>Online</StatusPill>`.
     */
    dotColor: Color? = null,
    style: TextStyle = com.medlenx.lab.ui.theme.MlxType.MicroPill,
) {
    Row(
        modifier = modifier
            .background(tone.bg, RoundedCornerShape(percent = 50))
            .border(BorderStroke(1.dp, tone.border), RoundedCornerShape(percent = 50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, RoundedCornerShape(percent = 50)),
            )
        }
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tone.fg,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(text = text, style = style, color = tone.fg)
    }
}

/**
 * Regulatory micro-pill — 9px/700. Used for NEML Listed, DGDA Price Alert, ABX,
 * ABX ★, TRIPS Watch, Duplicate Rx Detected, Off-Territory Audit, therapeutic class.
 *
 * These are independent signals and frequently coexist on one row, so they are
 * rendered by [RegulatoryPillRow] which wraps rather than truncates.
 */
@Composable
fun RegulatoryPill(
    text: String,
    tone: PillTone,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    StatusPill(
        text = text,
        tone = tone,
        icon = icon,
        modifier = modifier,
        style = com.medlenx.lab.ui.theme.MlxType.RegulatoryPill,
    )
}

/** Wrapping row of regulatory pills. Never drops a pill; wraps to the next line. */
@Composable
fun RegulatoryPillRow(
    pills: List<Pair<String, PillTone>>,
    modifier: Modifier = Modifier,
    icons: List<ImageVector?> = emptyList(),
) {
    if (pills.isEmpty()) return
    FlowRowCompat(
        modifier = modifier,
        horizontalSpacing = 4.dp,
        verticalSpacing = 4.dp,
    ) {
        pills.forEachIndexed { index, (label, tone) ->
            RegulatoryPill(
                text = label,
                tone = tone,
                icon = icons.getOrNull(index),
            )
        }
    }
}

/**
 * Confidence badge with the web app's three distinct bands — these must never be
 * collapsed into one neutral style:
 *   >= 85%  emerald          high confidence
 *   <  80%  orange + robot   "NN% AI Guess"
 *   <  70%  amber  + flag    "NN% manual flag"
 *   else    amber            "NN%"
 */
/**
 * Three bands, exactly as Figma's `ConfBadge` (App.tsx:70-75):
 *   >= 85        emerald, plain "NN%"
 *   70 .. 84     orange,  "NN% AI Guess"
 *   <  70        amber,   "NN% manual flag"
 *
 * Note the web app's legend (templates/index.html:320) describes the AI-guess band as
 * "<80%", while Figma's ConfBadge puts the boundary at 85. Figma is followed here
 * because it is total - every value maps to exactly one band - whereas the previous
 * port left 80..84 falling into a fourth, unlabelled amber state that matches neither
 * design.
 */
enum class ConfidenceBand { High, AiGuess, ManualFlag }

fun confidenceBand(percent: Int): ConfidenceBand = when {
    percent >= 85 -> ConfidenceBand.High
    percent < 70 -> ConfidenceBand.ManualFlag
    else -> ConfidenceBand.AiGuess
}

@Composable
fun ConfidenceBadge(percent: Int, modifier: Modifier = Modifier) {
    val band = confidenceBand(percent)
    val tone = when (band) {
        ConfidenceBand.High -> PillTone.Emerald
        ConfidenceBand.AiGuess -> PillTone.Orange
        ConfidenceBand.ManualFlag -> PillTone.AmberSolid
    }
    val suffix = when (band) {
        ConfidenceBand.High -> ""
        ConfidenceBand.AiGuess -> " AI Guess"
        ConfidenceBand.ManualFlag -> " manual flag"
    }
    // Figma prefixes the two warning bands with 🤖 and 🏴; vector icons per decision.
    val icon = when (band) {
        ConfidenceBand.High -> null
        ConfidenceBand.AiGuess -> Icons.Filled.SmartToy
        ConfidenceBand.ManualFlag -> Icons.Filled.Flag
    }
    StatusPill(text = "$percent%$suffix", tone = tone, icon = icon, modifier = modifier)
}
