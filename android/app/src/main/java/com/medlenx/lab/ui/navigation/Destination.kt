package com.medlenx.lab.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoGraph
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Navigation graph.
 *
 * The web sidebar has SIX destinations (workspace, dashboard, database, rsm,
 * settings, help). The mobile bottom bar in templates/index.html only carries FIVE —
 * `help` has no mobile slot. To avoid dropping a feature, Help & Guide keeps its own
 * route and is surfaced from Settings.
 */
sealed class Destination(val route: String, val label: String, val icon: ImageVector) {

    data object Scan : Destination("scan", "Scan", Icons.Filled.ViewColumn)
    data object Analytics : Destination("analytics", "Analytics", Icons.Filled.AutoGraph)
    data object Hub : Destination("hub", "Hub", Icons.Filled.Science)
    data object Team : Destination("team", "Team", Icons.Filled.AccountTree)
    data object Settings : Destination("settings", "Settings", Icons.Filled.Settings)

    /** Not on the bottom bar; opened from Settings (web sidebar item #6). */
    data object Help : Destination("help", "Help & Guide", Icons.Filled.HelpOutline)

    /**
     * Not on the bottom bar. Opened from the scan workspace over a completed read -
     * the audit is about *that* prescription, so it has no standalone home.
     */
    data object RxAudit : Destination("rx-audit", "Prescription Audit", Icons.Filled.Description)

    companion object {
        /** The five destinations rendered in the bottom navigation bar, in order. */
        val bottomBar: List<Destination> = listOf(Scan, Analytics, Hub, Team, Settings)

        fun fromRoute(route: String?): Destination? =
            bottomBar.firstOrNull { it.route == route }
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
}
