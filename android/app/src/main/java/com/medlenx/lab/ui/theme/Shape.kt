package com.medlenx.lab.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Radii from the Figma export (inline `borderRadius` histogram over App.tsx):
 *   12  (54x) - buttons, inputs, cards
 *   9999(40x) - pills, avatars, logo chips, active nav item
 *   8   (25x) - small chips, icon buttons
 *   10  (20x) - Hub sub-tabs, segmented controls, filter pills
 *   16  ( 8x) - panels, heroes, sheets, modals
 *   6   ( 7x) - tight badges
 *   4   ( 1x) - bracket corners
 */
object MlxShape {
    val ExtraSmall = RoundedCornerShape(4.dp)
    val Small = RoundedCornerShape(6.dp)
    val Chip = RoundedCornerShape(8.dp)
    /** Hub sub-tabs and segmented controls (Figma `borderRadius:10`, App.tsx:1576). */
    val Tab = RoundedCornerShape(10.dp)
    val Medium = RoundedCornerShape(12.dp)   // cards, buttons, inputs
    val Large = RoundedCornerShape(16.dp)    // panels, heroes
    val Sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val Pill = RoundedCornerShape(percent = 50)
}
