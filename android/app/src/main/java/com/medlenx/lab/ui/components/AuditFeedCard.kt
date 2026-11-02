package com.medlenx.lab.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/** One saved prescription event; "Saved" does not assert clinical verification. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditFeedCard(doctor: String, time: String, medicines: List<String>, modifier: Modifier = Modifier,
    attribution: String? = null, officerAvatarId: String? = null, fallbackName: String? = null) {
    Column(modifier.fillMaxWidth().background(Mlx.Surface, MlxShape.Medium)
        .border(1.dp, Mlx.Brand200, MlxShape.Medium).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(doctor.ifBlank { "Unknown doctor" }, style = MlxType.CardTitle)
                Text(time, style = MlxType.Meta, color = Mlx.Text500)
            }
            Surface(shape = CircleShape, color = Mlx.Ok100) {
                Text("Saved", modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall, color = Mlx.Ok600)
            }
        }
        if (medicines.isEmpty()) Text("No medicines recorded", style = MlxType.Meta, color = Mlx.Text500)
        else FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            medicines.forEach { medicine ->
                Surface(shape = CircleShape, color = Mlx.Brand100) {
                    Text(medicine, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MlxType.MicroPill)
                }
            }
        }
        if (!attribution.isNullOrBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OfficerAvatar(imageUri = null, avatarId = officerAvatarId, fallbackName = fallbackName, size = 28.dp)
                Text(attribution, style = MaterialTheme.typography.bodySmall, color = Mlx.Text500)
            }
        }
    }
}
