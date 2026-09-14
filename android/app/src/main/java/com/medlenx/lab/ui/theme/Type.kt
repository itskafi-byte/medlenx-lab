package com.medlenx.lab.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/**
 * Type scale ported from the web app, which uses Inter 400/500/600/700 only.
 *
 * Web class -> sp:
 *   text-[9px]  -> 9sp   regulatory micro pills
 *   text-[10px] -> 10sp  field labels, micro pills, footnotes
 *   text-[11px] -> 11sp  meta lines, helper text
 *   text-xs 12  -> 12sp  body, section labels
 *   text-sm 14  -> 14sp  card titles, inputs
 *   text-lg 18  -> 18sp  panel headings
 *   text-xl 20  -> 20sp  hero headings, mini KPI values
 *   text-2xl 24 -> 24sp  KPI values
 *
 * Tailwind letter-spacing: `tracking-wider` = 0.05em, `tracking-widest` = 0.1em.
 */
object MlxType {

    /** Inter when the bundled font is present; system sans otherwise. */
    val Font: FontFamily = FontFamily.SansSerif

    val HeroTitle = TextStyle(
        fontFamily = Font, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        color = Mlx.Text900, lineHeight = 26.sp,
    )
    val PanelTitle = TextStyle(
        fontFamily = Font, fontSize = 18.sp, fontWeight = FontWeight.Bold,
        color = Mlx.Text900, lineHeight = 24.sp,
    )
    val CardTitle = TextStyle(
        fontFamily = Font, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        color = Mlx.Text900, lineHeight = 20.sp,
    )
    val SectionLabel = TextStyle(
        fontFamily = Font, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        color = Mlx.Text500, lineHeight = 16.sp, letterSpacing = 1.44.sp, // 0.12em
    )
    val FieldLabel = TextStyle(
        fontFamily = Font, fontSize = 10.sp, fontWeight = FontWeight.Bold,
        color = Mlx.Text500, lineHeight = 14.sp, letterSpacing = 0.5.sp, // 0.05em
    )
    val Body = TextStyle(
        fontFamily = Font, fontSize = 13.sp, fontWeight = FontWeight.Normal,
        color = Mlx.Text900, lineHeight = 19.sp,
    )
    val BodySmall = TextStyle(
        fontFamily = Font, fontSize = 12.sp, fontWeight = FontWeight.Normal,
        color = Mlx.Text700, lineHeight = 17.sp,
    )
    val Meta = TextStyle(
        fontFamily = Font, fontSize = 11.sp, fontWeight = FontWeight.Normal,
        color = Mlx.Text500, lineHeight = 15.sp,
    )
    val Footnote = TextStyle(
        fontFamily = Font, fontSize = 10.sp, fontWeight = FontWeight.Normal,
        color = Mlx.Text400, lineHeight = 14.sp,
    )
    val MicroPill = TextStyle(
        fontFamily = Font, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 13.sp,
    )
    val RegulatoryPill = TextStyle(
        fontFamily = Font, fontSize = 9.sp, fontWeight = FontWeight.Bold,
        lineHeight = 12.sp,
    )
    val KpiValue = TextStyle(
        fontFamily = Font, fontSize = 24.sp, fontWeight = FontWeight.Bold,
        color = Mlx.Text900, lineHeight = 30.sp,
    )
    val MiniKpiValue = TextStyle(
        fontFamily = Font, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        color = Mlx.Text900, lineHeight = 26.sp,
    )
    val Delta = TextStyle(
        fontFamily = Font, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 13.sp,
    )
    val BrandWordmark = TextStyle(
        fontFamily = Font, fontSize = 14.sp, fontWeight = FontWeight.Bold,
        color = Mlx.Text900, lineHeight = 18.sp,
        // Figma AppBar: `letterSpacing:"-0.02em"` on the MedLenX wordmark.
        letterSpacing = (-0.02).em,
    )
    val BrandName = TextStyle(
        fontFamily = Font, fontSize = 15.sp, fontWeight = FontWeight.Bold,
        color = Mlx.Text900, lineHeight = 20.sp,
    )
}

/** Material 3 typography wired to the MedLenX scale. */
val MlxTypography = Typography(
    displaySmall = MlxType.HeroTitle,
    headlineMedium = MlxType.PanelTitle,
    headlineSmall = MlxType.PanelTitle,
    titleLarge = MlxType.CardTitle,
    titleMedium = MlxType.CardTitle,
    titleSmall = MlxType.BodySmall,
    bodyLarge = MlxType.Body,
    bodyMedium = MlxType.BodySmall,
    bodySmall = MlxType.Meta,
    labelLarge = MlxType.CardTitle,
    labelMedium = MlxType.MicroPill,
    labelSmall = MlxType.RegulatoryPill,
)
