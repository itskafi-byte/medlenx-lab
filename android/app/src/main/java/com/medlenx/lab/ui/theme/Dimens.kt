package com.medlenx.lab.ui.theme

import androidx.compose.ui.unit.dp

/**
 * Spacing and sizing on the web app's 4px Tailwind grid.
 *
 * Anti-truncation contract: every interactive control is at least 48dp tall even
 * where the web used 10px text, and no semantic value is ever ellipsised —
 * brand names, generics, strengths, companies, doctor names, BMDC numbers and
 * territories wrap instead.
 */
object MlxD {
    val Space1 = 4.dp
    val Space2 = 8.dp
    val Space3 = 12.dp
    val Space4 = 16.dp
    val Space5 = 20.dp
    val Space6 = 24.dp
    val Space8 = 32.dp
    val Space10 = 40.dp
    val Space16 = 64.dp

    /** Horizontal screen margin (web: px-4). */
    val ScreenMargin = 16.dp

    /** Vertical rhythm between sections (web: space-y-6). */
    val SectionGap = 24.dp

    /** Gap between cards (web: gap-3 / space-y-3). */
    val CardGap = 12.dp

    val CardPadding = 16.dp
    val PanelPadding = 20.dp

    /**
     * Bottom clearance so content never sits under the bottom nav.
     * Figma `BottomNav` (App.tsx:361) uses `height:60`, not the Material default 56.
     */
    val BottomNavHeight = 60.dp

    /** Search field height — Figma `AppBar` uses `height:36` on the input. */
    val SearchFieldHeight = 36.dp
    val ContentBottomClearance = 24.dp
    val AppBarHeight = 64.dp

    val TouchTarget = 48.dp
    val IconSmall = 12.dp
    val IconMedium = 14.dp
    val IconNav = 16.dp
    val IconLarge = 24.dp

    val AvatarSmall = 20.dp
    val AvatarMedium = 28.dp
    val AvatarLarge = 36.dp
    val LogoTile = 32.dp
    val LogoTileLarge = 40.dp

    val IconButtonText = 32.dp
    val ProgressBarThin = 6.dp
    val ProgressBarThick = 8.dp
    val SparklineWidth = 72.dp
    val SparklineHeight = 22.dp
    val MapHeight = 300.dp
    val ChartHeight = 240.dp
}
