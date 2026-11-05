package com.medlenx.lab.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DocumentScanner
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Description
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation graph.
 *
 * Core destinations live in the bottom bar. Settings is opened from the profile
 * menu, and Help & Guide remains accessible from Settings.
 */
sealed class Destination(val route: String, val label: String, val icon: ImageVector) {

    data object Scan : Destination("scan", "Scan", Icons.Rounded.DocumentScanner)
    data object Analytics : Destination("analytics", "Analytics", Icons.Rounded.Analytics)
    data object Hub : Destination("hub", "Hub", Icons.Rounded.Hub)
    data object Team : Destination("team", "Team", Icons.Rounded.Groups)
    data object Settings : Destination("settings", "Settings", Icons.Filled.Settings)

    /** Not on the bottom bar; opened from Settings (web sidebar item #6). */
    data object Help : Destination("help", "Help & Guide", Icons.AutoMirrored.Filled.HelpOutline)

    /**
     * Not on the bottom bar. Opened from the scan workspace over a completed read -
     * the audit is about *that* prescription, so it has no standalone home.
     */
    data object RxAudit : Destination("rx-audit", "Prescription Audit", Icons.Filled.Description)

    companion object {
        /** The core destinations rendered in the bottom navigation bar, in order. */
        val bottomBar: List<Destination> = listOf(Scan, Analytics, Hub, Team)
        val primaryRoutes: List<Destination> = bottomBar + Settings

        fun fromRoute(route: String?): Destination? =
            primaryRoutes.firstOrNull { it.route == route }
                ?: Help.takeIf { it.route == route }
                ?: RxAudit.takeIf { it.route == route }
    }
}

/** Sub-tabs of the Pharma Intelligence Hub (`data-hub-tab` in the web app). */
enum class HubTab(val label: String) {
    Index("💊 25K+ Drug Index"),
    Trips("🌐 TRIPS Waiver Tracker"),
    News("📰 Industry News"),
    Jobs("💼 Health & Pharma Jobs"),
    HealthDays("🗓️ Health Days"),

    /**
     * Android-only. The web hub has no DGDA tab: `/api/dgda/monitor` exists in
     * the Python backend but no UI calls it, so this panel was built from the
     * bundled dataset rather than ported from a screen.
     */
    Dgda("🛡️ DGDA Monitor"),
}
