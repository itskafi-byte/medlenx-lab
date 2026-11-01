package com.medlenx.lab.ui.screens.team

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.sync.TeamFeedState
import com.medlenx.lab.data.sync.TeamScan
import com.medlenx.lab.data.repo.RxAudit
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxType
import kotlinx.coroutines.flow.StateFlow
import android.text.format.DateUtils

@Composable
fun TeamFeed(feed: StateFlow<TeamFeedState>) {
    val state by feed.collectAsState()
    var selected by remember { mutableStateOf<TeamScan?>(null) }
    selected?.let { scan ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Prescription audit") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${scan.doctor} · ${scan.specialty}")
                    Text("${scan.officer} · ${scan.territory}")
                    scan.medicines.forEach { med ->
                        Text("${med.brand} · ${med.generic} · ${med.company} · ${RxAudit.confidencePercentOf(med.confidence)}%")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("Close") } },
        )
    }
    MlxCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Live Team Activity", style = MlxType.CardTitle)
            if (state.message.isNotBlank()) Text(state.message, style = MlxType.BodySmall, color = Mlx.Text500)
            state.scans.forEach { scan ->
                Column(Modifier.fillMaxWidth().clickable { selected = scan }.padding(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val time = scan.timestamp?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() } ?: "Pending"
                    Text("Scanned by ${scan.officer.ifBlank { "Officer" }} · $time", style = MlxType.CardTitle)
                    Text("${scan.doctor.ifBlank { "Unknown doctor" }} · ${scan.specialty}", style = MlxType.BodySmall)
                    Text("${scan.medicines.size} items: ${scan.medicines.take(3).joinToString { it.brand }}", style = MlxType.BodySmall, color = Mlx.Text500)
                }
            }
        }
    }
}
