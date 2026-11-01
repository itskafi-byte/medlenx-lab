package com.medlenx.lab.ui.screens.scan

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Work
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.medlenx.lab.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.UserCaptureFiles
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
    val context = LocalContext.current
    var selected by remember { mutableStateOf<PrescriptionEntity?>(null) }
    vm.claimCompany?.let { company ->
        AlertDialog(
            onDismissRequest = { if (!vm.claimBusy) vm.cancelClaim() },
            title = { Text("Assign unassigned audits?") },
            text = { Text("Assign ${vm.claimCount} of your unassigned audits to $company and queue their results for team sync. Prescription photos stay on this device. This cannot automatically be rerouted later.") },
            confirmButton = {
                TextButton(onClick = vm::confirmClaim, enabled = !vm.claimBusy) { Text("Assign to $company & Sync") }
            },
            dismissButton = { TextButton(onClick = vm::cancelClaim, enabled = !vm.claimBusy) { Text("Cancel") } },
        )
    }
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
                if (rx.imagePath.isNotBlank() && image.isFile && UserCaptureFiles.ownsStored(context, vm.currentUid, image)) {
                    Box(Modifier.fillMaxWidth().height(300.dp)) {
                        PrescriptionImageViewer(imageUri = Uri.fromFile(image).toString(), transform = transform)
                    }
                    Text("Pinch to zoom · Drag to pan", style = MlxType.Footnote)
                } else Text("This image is unavailable or requires legacy review.", style = MlxType.BodySmall)
                OutlinedButton(onClick = { selected = null; onOpenAudit(listOf(rx).toRecentRxRows().first()) },
                    modifier = Modifier.fillMaxWidth()) { Text("View saved audit") }
            }
        }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val twoColumns = maxWidth >= 560.dp
        if (twoColumns) {
            Row(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    CaptureHero(vm, onCamera, onGallery)
                    DailyTargetCard(vm)
                    ClaimAuditsCard(vm)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    RecentCaptures(vm.recentCaptures, vm.currentUid) { selected = it }
                    HubFeed(hubVm, onOpenUrl, onOpenHub)
                }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CaptureHero(vm, onCamera, onGallery)
                DailyTargetCard(vm)
                ClaimAuditsCard(vm)
                RecentCaptures(vm.recentCaptures, vm.currentUid) { selected = it }
                HubFeed(hubVm, onOpenUrl, onOpenHub)
            }
        }
    }
}

@Composable
private fun ClaimAuditsCard(vm: ScanViewModel) {
    if (vm.claimableAudits == 0 && vm.claimMessage == null) return
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vm.claimableAudits > 0) {
                Text("You have ${vm.claimableAudits} unassigned offline scans.", style = MlxType.CardTitle)
                Text("Only scans assigned to this account can be claimed. Connect to verify your approved company.",
                    style = MlxType.BodySmall, color = Mlx.Text500)
                Button(onClick = vm::prepareClaim, enabled = !vm.claimBusy, modifier = Modifier.fillMaxWidth()) {
                    Text(if (vm.claimBusy) "Verifying…" else "Assign to company & Sync")
                }
            }
            vm.claimMessage?.let { Text(it, style = MlxType.BodySmall, color = Mlx.Text700) }
        }
    }
}

@Composable
private fun CaptureHero(vm: ScanViewModel, onCamera: () -> Unit, onGallery: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().clip(MlxShape.Large)
            .background(Brush.linearGradient(listOf(Mlx.Brand900, Mlx.Brand800, Mlx.Brand700))),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            tint = Mlx.Cyan200.copy(alpha = 0.18f),
            modifier = Modifier.align(Alignment.TopEnd).size(120.dp),
        )
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("PRESCRIPTION WORKSPACE", style = MlxType.FieldLabel, color = Mlx.Cyan200)
            Text("New Prescription Audit", style = MlxType.HeroTitle, color = Color.White)
            Text("Capture a prescription to review its medicines.", style = MlxType.BodySmall, color = Color.White)
            Button(onClick = onCamera, modifier = Modifier.fillMaxWidth().height(60.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Mlx.Cyan200, contentColor = Mlx.Brand900)) {
                Icon(Icons.Rounded.CameraAlt, contentDescription = null)
                Text("  Open Camera", color = Mlx.Brand900, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onGallery, modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.8f)),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Text("  Choose File", color = Color.White)
            }
            val pending = !vm.online || vm.queuedScans > 0
            Row(
                modifier = Modifier.align(Alignment.CenterHorizontally)
                    .clip(CircleShape).background(if (pending) Mlx.Warn100 else Mlx.Ok100)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(9.dp).background(if (pending) Mlx.GuessAccent else Mlx.Ok500, CircleShape))
                Text(
                    text = when {
                        !vm.online -> "Offline · ${vm.queuedScans} queued locally"
                        vm.queuedScans > 0 -> "Online · ${vm.queuedScans} queued locally"
                        else -> "Online · saved on this device"
                    },
                    style = MlxType.BodySmall.copy(fontWeight = FontWeight.Bold),
                    color = if (pending) Mlx.WarnDeep else Mlx.OkDeep,
                )
            }
            Text(
                if (vm.unroutableUploads > 0) "${vm.unroutableUploads} scans have no approved company snapshot and need review; ${vm.pendingUploads} total awaiting upload."
                else if (vm.pendingUploads > 0) "${vm.pendingUploads} saved audits awaiting cloud upload."
                else "Saved audits can sync after company approval.",
                style = MlxType.Footnote, color = Color.White,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun DailyTargetCard(vm: ScanViewModel) {
    val progress = (vm.todayCount.toFloat() / vm.dailyTarget.coerceAtLeast(1)).coerceIn(0f, 1f)
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(40.dp).background(Mlx.Ok100, MlxShape.Medium), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.TrackChanges, contentDescription = null, tint = Mlx.Ok500)
                }
                Column {
                    Text("Daily Target", style = MlxType.CardTitle)
                    Text("Today's scans", style = MlxType.Footnote, color = Mlx.Text500)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(vm.todayCount.toString(), style = MlxType.KpiValue.copy(fontSize = 38.sp, lineHeight = 44.sp))
                Text("/ ${vm.dailyTarget}", style = MlxType.CardTitle, color = Mlx.Text500, modifier = Modifier.padding(bottom = 5.dp))
            }
            Box(
                Modifier.fillMaxWidth().height(12.dp).clip(CircleShape).background(Mlx.Brand200)
                    .progressSemantics(progress),
            ) {
                Box(Modifier.fillMaxWidth(progress).height(12.dp).clip(CircleShape)
                    .background(Brush.horizontalGradient(listOf(Mlx.Brand500, Mlx.Ok400))))
            }
            Text("Your scans · change your target in Settings", style = MlxType.Footnote, color = Mlx.Text500)
        }
    }
}

@Composable
private fun RecentCaptures(captures: List<PrescriptionEntity>, currentUid: String, onSelect: (PrescriptionEntity) -> Unit) {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Recent Captures", style = MlxType.CardTitle)
        if (captures.isEmpty()) {
            Surface(shape = MlxShape.Medium, color = Color.White, shadowElevation = 2.dp) {
                Text("Saved prescriptions will appear here.", modifier = Modifier.fillMaxWidth().padding(16.dp),
                    style = MlxType.BodySmall, color = Mlx.Text500)
            }
        } else captures.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { rx ->
                    Column(Modifier.weight(1f).clip(MlxShape.Medium).background(Color.White).clickable { onSelect(rx) }) {
                        Box(Modifier.fillMaxWidth().height(90.dp).background(Mlx.Brand100), contentAlignment = Alignment.Center) {
                            if (rx.imagePath.isNotBlank() && File(rx.imagePath).isFile && UserCaptureFiles.ownsStored(context, currentUid, File(rx.imagePath))) {
                                AsyncImage(model = File(rx.imagePath), contentDescription = "Prescription ${rx.rxNo}",
                                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else Icon(Icons.Filled.Image, contentDescription = "Image unavailable", tint = Mlx.Text400, modifier = Modifier.size(28.dp))
                        }
                        Text(rx.doctorName.ifBlank { "Unknown doctor" }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MlxType.BodySmall, modifier = Modifier.padding(8.dp))
                    }
                }
                if (row.size == 1) Box(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HubFeed(hubVm: HubViewModel, onOpenUrl: (String) -> Unit, onOpenHub: () -> Unit) {
    val news = hubVm.news?.items?.maxByOrNull { it.publishedAt }
    val dgda = hubVm.dgda()
    val alert = dgda.banned.firstOrNull()
    val job = hubVm.jobs?.jobs?.minByOrNull { it.postedDaysAgo }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Pharma Intelligence Hub", style = MlxType.CardTitle)
        if (news == null && alert == null && job == null) {
            Text("Hub information is loading.", style = MlxType.BodySmall, color = Mlx.Text500)
        }
        if (news != null) HubPreviewCard(
            "Industry News", if (news.live) "Live" else "Curated", news.title,
            if (news.url.isNotBlank()) "Read article" else "Open Hub", Mlx.Brand600, Icons.Filled.Public,
        ) { if (news.url.isNotBlank()) onOpenUrl(news.url) else onOpenHub() }
        if (alert != null) HubPreviewCard(
            "DGDA", "Demo snapshot ${dgda.updatedAt}", "${alert.brand}: ${alert.reason}",
            "DGDA website", Mlx.Danger, Icons.Filled.Shield,
        ) { onOpenUrl("https://dgda.gov.bd/") }
        if (job != null) HubPreviewCard(
            "Jobs", "Sample listing", "${job.title} · ${job.company}",
            "Career page", Mlx.Violet, Icons.Filled.Work,
        ) { onOpenUrl(PharmaHub.jobApplyUrl(job)) }
    }
}

@Composable
private fun HubPreviewCard(
    category: String, detail: String, title: String, action: String,
    accent: Color, icon: ImageVector, onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MlxShape.Medium, color = Color.White, shadowElevation = 2.dp,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Text(category, style = MlxType.Footnote.copy(fontWeight = FontWeight.Bold), color = Color.White,
                    modifier = Modifier.clip(CircleShape).background(accent).padding(horizontal = 10.dp, vertical = 4.dp))
            }
            Text(detail, style = MlxType.Footnote, color = Mlx.Text500, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = MlxType.BodySmall.copy(fontWeight = FontWeight.SemiBold), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(action, style = MlxType.Footnote, color = Mlx.Brand600)
        }
    }
}
