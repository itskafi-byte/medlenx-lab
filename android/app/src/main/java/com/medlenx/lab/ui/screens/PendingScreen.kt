package com.medlenx.lab.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.navigation.Destination
import com.medlenx.lab.ui.theme.MlxType
import androidx.compose.material3.Text

/**
 * Temporary placeholder for a destination whose screens arrive in a later step.
 *
 * Kept deliberately visible so a partially-built APK is obviously incomplete rather
 * than silently missing features.
 */
@Composable
fun PendingScreen(destination: Destination, modifier: Modifier = Modifier) {
    MlxCard(modifier = modifier) {
        SectionHeader(title = destination.label)
        Text(
            text = when (destination) {
                Destination.Scan -> "Scan flow — Step 3 (capture, viewer, laser scan) and Step 4 (verification)."
                Destination.Analytics -> "Analytics — Step 5 (hero, filters, KPIs, charts, live scans) and Step 6 (Rx audit)."
                Destination.Hub -> "Pharma Intelligence Hub — Step 7 (drug index, TRIPS, news, jobs, health days)."
                Destination.Team -> "RSM Command — Step 8 (map, tiers, leaderboard, targets, off-territory, stewardship)."
                Destination.Settings -> "Enterprise settings & officer profile — Step 9. Help & Guide is reached from here."
                Destination.Help -> "Interactive scan guide, error escalation, BMDC & DGDA reference — Step 9."
                Destination.RxAudit -> "Prescription Audit Summary for the last scanned prescription."
            },
            style = MlxType.Meta,
        )
    }
}
