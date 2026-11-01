package com.medlenx.lab.ui.screens.scan

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.repo.PharmaHub
import com.medlenx.lab.ui.screens.analytics.RecentRxRow
import com.medlenx.lab.ui.screens.analytics.toRecentRxRows
import com.medlenx.lab.ui.screens.hub.HubViewModel
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType
import java.io.File

@Composable
fun ScanHome(
    vm: ScanViewModel,
    hubVm: HubViewModel,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onOpenAudit: (RecentRxRow) -> Unit,
    onOpenUrl: (String) -> Unit,
    onOpenHub: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<PrescriptionEntity?>(null) }
    selected?.let { rx ->
        val image = remember(rx.imagePath) { File(rx.imagePath) }
        val transform = remember(rx.id) { ViewerTransform() }
        Dialog(onDismissRequest = { selected = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(Modifier.fillMaxWidth().padding(16.dp).background(Mlx.Surface, MlxShape.Large).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Prescription ${rx.rxNo}", modifier = Modifier.weight(1f), style = MlxType.CardTitle)
                    IconButton(onClick = { selected = null }) { Icon(Icons.Filled.Close, "Close") }
                }
                if (rx.imagePath.isNotBlank() && image.isFile) {
                    Box(Modifier.fillMaxWidth().height(300.dp)) {
                        PrescriptionImageViewer(imageUri = Uri.fromFile(image).toString(), transform = transform)
                    }
                    Text("Pinch to zoom · Drag to pan", style = MlxType.Footnote)
                } else Text("The local prescription image is no longer available.", style = MlxType.BodySmall)
                OutlinedButton(onClick = { selected = null; onOpenAudit(listOf(rx).toRecentRxRows().first()) },
                    modifier = Modifier.fillMaxWidth()) { Text("View saved audit") }
            }
        }
    }
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("New Prescription Audit", style = MlxType.HeroTitle, color = Mlx.Text900)
                Text("Capture a prescription to review its medicines.", style = MlxType.BodySmall, color = Mlx.Text500)
                Button(onClick = onCamera, modifier = Modifier.fillMaxWidth().height(60.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Mlx.Brand700, contentColor = Color.White)) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                    Text("  Open Camera", color = Color.White)
                }
                OutlinedButton(onClick = onGallery, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text("Choose File")
                }
                AssistChip(
                    onClick = {},
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                    label = {
                        Text(when {
                            !vm.online -> "Offline · ${vm.queuedScans} queued locally"
                            vm.queuedScans > 0 -> "Online · ${vm.queuedScans} queued locally"
                            else -> "Online · saved on this device"
                        })
                    },
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = if (!vm.online || vm.queuedScans > 0) Mlx.Warn50 else Mlx.Ok50,
                        labelColor = if (!vm.online || vm.queuedScans > 0) Mlx.Warn600 else Mlx.Ok600,
                    ),
                )
                Text("Cloud sync is not available yet.", style = MlxType.Footnote, color = Mlx.Text500,
                    modifier = Modifier.align(Alignment.CenterHorizontally))
            }
        }
        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Daily Target", style = MlxType.CardTitle)
                Text("Today's Scans: ${vm.todayCount} / Target: ${vm.dailyTarget}", style = MlxType.BodySmall)
                LinearProgressIndicator(progress = { (vm.todayCount.toFloat() / vm.dailyTarget.coerceAtLeast(1)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(), color = Mlx.Brand600)
                Text("Device total · change your target in Settings", style = MlxType.Footnote, color = Mlx.Text500)
            }
        }
        Text("Recent Captures", style = MlxType.CardTitle)
        if (vm.recentCaptures.isEmpty()) {
            Text("Saved prescriptions will appear here.", style = MlxType.BodySmall, color = Mlx.Text500)
        } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(vm.recentCaptures, key = { it.id }) { rx ->
                Column(Modifier.width(112.dp).clip(MlxShape.Medium).clickable { selected = rx }) {
                    Box(Modifier.fillMaxWidth().height(88.dp).background(Mlx.Brand100), contentAlignment = Alignment.Center) {
                        if (rx.imagePath.isNotBlank() && File(rx.imagePath).isFile) {
                            AsyncImage(model = File(rx.imagePath), contentDescription = "Prescription ${rx.rxNo}",
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else Icon(Icons.Filled.Image, contentDescription = "Image unavailable", tint = Mlx.Text400, modifier = Modifier.size(28.dp))
                    }
                    Text(rx.doctorName.ifBlank { "Unknown doctor" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MlxType.Footnote, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Text("Latest from the Hub", style = MlxType.CardTitle)
        val news = hubVm.news?.items?.maxByOrNull { it.publishedAt }
        val dgda = hubVm.dgda()
        val alert = dgda.banned.firstOrNull()
        val job = hubVm.jobs?.jobs?.minByOrNull { it.postedDaysAgo }
        if (news == null && alert == null && job == null) {
            Text("Hub information is loading.", style = MlxType.BodySmall, color = Mlx.Text500)
        } else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (news != null) item {
                HubPreviewCard("Industry News · ${if (news.live) "Live" else "Curated"}", news.title,
                    if (news.url.isNotBlank()) "Read article" else "Open Hub") {
                    if (news.url.isNotBlank()) onOpenUrl(news.url) else onOpenHub()
                }
            }
            if (alert != null) item {
                HubPreviewCard("DGDA · demo snapshot ${dgda.updatedAt}", "${alert.brand}: ${alert.reason}", "DGDA website") {
                    onOpenUrl("https://dgda.gov.bd/")
                }
            }
            if (job != null) item {
                HubPreviewCard("Jobs · sample listing", "${job.title} · ${job.company}", "Career page") {
                    onOpenUrl(PharmaHub.jobApplyUrl(job))
                }
            }
        }
    }
}

@Composable
private fun HubPreviewCard(label: String, title: String, action: String, onClick: () -> Unit) {
    ElevatedCard(modifier = Modifier.width(280.dp).clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MlxType.Footnote, color = Mlx.Text500, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(title, style = MlxType.BodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(action, style = MlxType.Footnote, color = Mlx.Brand600)
        }
    }
}
