package com.medlenx.lab.ui.screens.scan

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Contrast
import androidx.compose.material.icons.filled.Expand
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MedicalInformation
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.medlenx.lab.ui.components.ButtonTone
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxErrorLine
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.ProgressTrack
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType
import java.io.File

/**
 * Prescription Workspace.
 *
 * Web equivalent: `#tab-workspace` — the dashed upload hero in its empty state, then
 * the split-screen verification workspace. On a phone the split becomes a vertical
 * stack: viewer on top, verification beneath, with 24dp bottom clearance.
 */
@Composable
fun ScanScreen(
    vm: ScanViewModel,
    onOpenAudit: () -> Unit,
    modifier: Modifier = Modifier,
    recentRows: List<com.medlenx.lab.ui.screens.analytics.RecentRxRow> = emptyList(),
) {
    val context = LocalContext.current
    val state = vm.state
    val transform = remember(state.imageUri) { ViewerTransform() }

    // The shell lets this screen start at y=0 so it scrolls *under* the blurred app
    // bar. The offset is therefore carried by the screen itself: inside the scroll
    // container for the scrolling phases (so content disappears behind the bar), and
    // as a plain offset for the non-scrolling empty state.
    val topInset = MlxD.AppBarHeight + MlxD.SectionGap

    // Camera capture writes into the FileProvider path declared in the manifest.
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) pendingCameraUri?.let(vm::onImagePicked)
    }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(vm::onImagePicked) }

    when (state.phase) {
        ScanPhase.Empty -> UploadZone(
            onChooseFile = {
                galleryLauncher.launch(
                    // Builder, not the constructor: the PickVisualMediaRequest(mediaType)
                    // ctor is deprecated and is gone from the current androidx API surface.
                    // Builder() + setMediaType() exists in both 1.9.3 and current.
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        .build(),
                )
            },
            onOpenCamera = {
                val dir = File(context.cacheDir, "rx").apply { mkdirs() }
                val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                pendingCameraUri = uri
                cameraLauncher.launch(uri)
            },
            modifier = modifier.padding(top = topInset),
        )

        ScanPhase.VerifyDoctor -> VerifyDoctorSection(
            doctor = state.doctor,
            specialties = vm.specialties(),
            districts = vm.districtNames(),
            upazilas = vm.upazilasFor(state.doctor.district),
            territories = vm.territoriesFor(state.doctor.district),
            zoomLabel = "${transform.zoomPercent}%",
            onDoctorChange = vm::onDoctorChange,
            onDistrictChange = vm::onDistrictChange,
            onBack = vm::clear,
            onNext = vm::gotoMedicines,
            onClose = vm::clear,
            onThumbnailAction = { action ->
                when (action) {
                    ViewerAction.ZoomIn -> transform.zoomIn()
                    ViewerAction.ZoomOut -> transform.zoomOut()
                    ViewerAction.RotateLeft -> transform.rotateLeft()
                    ViewerAction.RotateRight -> transform.rotateRight()
                    ViewerAction.Contrast -> transform.cycleContrast()
                    ViewerAction.Fit -> transform.fit()
                }
            },
            modifier = modifier.padding(top = topInset),
        )

        ScanPhase.VerifyMedicines -> VerifyMedicinesSection(
            cards = state.cards,
            onBrandChange = vm::onBrandChange,
            onDosageChange = vm::onDosageChange,
            onVerifyAgainstMedex = { /* Medex re-check - Step 7 drug index */ },
            onReportMisId = { /* escalation queue - Step 9 */ },
            onBack = vm::backToDoctor,
            onSave = vm::gotoGps,
            modifier = modifier.padding(top = topInset),
        )

        ScanPhase.VerifyGps -> Column {
            // Surfaces a failed save in place, so the officer can retry instead of
            // losing the verified prescription.
            state.error?.let {
                MlxErrorLine(it, Modifier.padding(bottom = MlxD.Space3))
            }
            VerifyGpsSection(
                gps = GpsVerification(
                    upazila = state.geo.upazila,
                    district = state.geo.district,
                    territory = state.geo.territory,
                    hasFix = state.geo.lat != null,
                    latitude = state.geo.lat,
                    longitude = state.geo.lng,
                    offTerritory = state.geo.offTerritory,
                    reason = state.geo.verdictReason,
                ),
                onChange = { next ->
                    vm.onGeoChange(
                        upazila = next.upazila,
                        district = next.district,
                        territory = next.territory,
                    )
                },
                onCaptureGps = vm::pinGps,
                onBack = vm::backToMedicines,
                onSave = vm::save,
                saving = vm.saving,
                modifier = modifier.padding(top = topInset),
            )
        }

        ScanPhase.Saved -> ScanSavedSection(
            receipt = state.receipt ?: SavedReceipt("", 0, "", ""),
            toastMessage = "Prescription saved and synced — Rx #" +
                (state.receipt?.rxNumber.orEmpty()),
            onScanAnother = vm::scanAnother,
            onOpenAudit = onOpenAudit,
            recentRows = recentRows,
            modifier = modifier.padding(top = topInset),
        )

        else -> Column(
            modifier = modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                // After verticalScroll, so this padding scrolls with the content.
                .padding(top = topInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OfflineQueueBanner(
                queued = vm.queuedScans,
                online = vm.online,
                parked = state.parked,
            )
            ViewerCard(
                state = state,
                transform = transform,
                onClear = vm::clear,
            )
            GeoStrip(
                state = state.geo,
                onUpazilaChange = { vm.onGeoChange(upazila = it) },
                onDistrictChange = { vm.onGeoChange(district = it) },
                onTerritoryChange = { vm.onGeoChange(territory = it) },
                onPinGps = vm::pinGps,
            )
            ActionRow(state = state, onAnalyze = vm::analyze)
            VerificationArea(state = state)
        }
    }
}

/**
 * Empty state hero.
 *
 * Web: `#workspaceEmpty` — white card, 2px dashed #BFDBFE border, 80dp gradient tile
 * with the file-medical glyph, "Drop prescription image here" and the two buttons.
 */
@Composable
fun UploadZone(
    onChooseFile: () -> Unit,
    onOpenCamera: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Mlx.Surface, MlxShape.Large)
            .padding(vertical = 48.dp, horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Mlx.Brand900, Mlx.Brand700),
                    ),
                    RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MedicalInformation,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(36.dp),
            )
        }
        Text(
            text = "Drop prescription image here",
            style = MlxType.HeroTitle,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            text = "Drag & drop or click to upload. Supports camera capture, rotation, " +
                "contrast adjustment for cursive handwriting.",
            style = MlxType.Body,
            color = Mlx.Text500,
            modifier = Modifier.padding(top = 8.dp),
        )
        Row(
            modifier = Modifier.padding(top = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            MlxButton(
                text = "Choose File",
                onClick = onChooseFile,
                icon = Icons.Filled.UploadFile,
            )
            MlxButton(
                text = "Open Camera",
                onClick = onOpenCamera,
                tone = ButtonTone.Outline,
                icon = Icons.Filled.AddAPhoto,
            )
        }
        Text(
            text = "Offline-first — scans cache on-device when the rural network drops, " +
                "then sync on reconnect.",
            style = MlxType.Meta,
            color = Mlx.Text400,
            modifier = Modifier.padding(top = 20.dp),
        )
    }
}

/**
 * The web app's offline banner (App.tsx:2147) - "Offline - 3 scans queued. They will sync
 * when you reconnect."
 *
 * Rendered only when something is genuinely parked, so the promise is never shown without a
 * queue behind it. Colours are the web banner's own: #FFFBEB / #FDE68A / #B45309.
 */
@Composable
private fun OfflineQueueBanner(queued: Int, online: Boolean, parked: Boolean) {
    if (queued <= 0 && !parked) return
    val plural = if (queued == 1) "" else "s"
    MlxCard(
        padding = 12.dp,
        background = Mlx.Warn50,
        borderColor = Mlx.Warn200,
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.CloudUpload,
                contentDescription = null,
                tint = Mlx.Warn600,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = when {
                    !online && queued > 0 ->
                        "Offline - $queued scan$plural queued. " +
                            "They will sync when you reconnect."
                    queued > 0 -> "$queued scan$plural queued for replay."
                    else -> "Scan cached on-device. It will sync when you reconnect."
                },
                style = MlxType.Meta,
                color = Mlx.Warn600,
            )
        }
    }
}

/** Viewer card: header controls, hint strip, canvas, laser while scanning. */
@Composable
private fun ViewerCard(
    state: ScanUiState,
    transform: ViewerTransform,
    onClear: () -> Unit,
) {
    MlxCard(padding = 0.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.weight(1f)) {
                SectionHeader(title = "Prescription Image Viewer")
            }
            MlxIconButton(Icons.Filled.ZoomIn, "Zoom in", transform::zoomIn)
            MlxIconButton(Icons.Filled.ZoomOut, "Zoom out", transform::zoomOut)
            MlxIconButton(Icons.AutoMirrored.Filled.RotateLeft, "Rotate left", transform::rotateLeft)
            MlxIconButton(Icons.AutoMirrored.Filled.RotateRight, "Rotate right", transform::rotateRight)
            MlxIconButton(Icons.Filled.Contrast, "Contrast", transform::cycleContrast)
            MlxIconButton(Icons.Filled.Expand, "Fit", transform::fit)
            MlxIconButton(
                icon = Icons.Filled.Close,
                contentDescription = "Clear workspace",
                onClick = onClear,
                background = Mlx.Brand100,
                border = Mlx.Brand100,
            )
        }

        ViewerHintStrip(zoomPercent = transform.zoomPercent)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .background(Mlx.Brand100),
        ) {
            state.imageUri?.let { uri ->
                PrescriptionImageViewer(imageUri = uri, transform = transform)
                if (state.phase == ScanPhase.Scanning) {
                    ScanLaserOverlay()
                    StatusPill(
                        text = "Scanning…",
                        tone = PillTone.Dark,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                    )
                }
                if (state.phase == ScanPhase.Done && state.result != null) {
                    MedicineBoundingBox(
                        lineIndex = 0,
                        totalLines = state.result.medicines.size,
                    )
                }
            }
            Text(
                text = "Swipe up for data verification",
                style = MlxType.Footnote,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(12.dp)
                    .background(Mlx.Brand900.copy(alpha = 0.8f), MlxShape.Small)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun ActionRow(state: ScanUiState, onAnalyze: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MlxButton(
            text = when (state.phase) {
                ScanPhase.Scanning -> "Scanning…"
                ScanPhase.Done -> "Re-scan with MedLenX VL"
                else -> "Analyze with MedLenX VL"
            },
            onClick = onAnalyze,
            enabled = state.phase != ScanPhase.Scanning,
            modifier = Modifier.weight(1f),
        )
    }
    if (state.phase == ScanPhase.Scanning) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(state.progressText.ifBlank { "Extracting with MedLenX VL..." }, style = MlxType.Meta)
            ProgressTrack(progress = state.progress)
        }
    }
}

/**
 * Result area. Step 4 replaces this with the doctor verification form and the
 * medicine card list; for now it reports what the read produced so Step 3 is
 * independently testable.
 */
@Composable
private fun VerificationArea(state: ScanUiState) {
    when (state.phase) {
        ScanPhase.Scanning -> ScanSkeleton()

        ScanPhase.Failed -> MlxCard {
            SectionHeader(title = "Scan failed")
            Text(state.error ?: "Unknown error", style = MlxType.BodySmall, color = Mlx.Danger)
        }

        ScanPhase.NeedsKey -> MlxEmptyState(
            message = "No OpenRouter key configured. Add OPENROUTER_API_KEY to " +
                "android/local.properties and rebuild to enable MedLenX VL.",
            icon = Icons.Filled.FileUpload,
        )

        ScanPhase.Done -> state.result?.let { result ->
            MlxCard {
                SectionHeader(
                    title = "Data Verification - Side-by-side",
                    subtitle = "Doctor and medicine editing lands in Step 4.",
                )
                StatusPill(text = "Pending Verification", tone = PillTone.Amber)
                Text(
                    text = "Doctor: ${result.doctor.name.ifBlank { "not read" }} · " +
                        "BMDC ${result.doctor.bmdcNo.ifBlank { "—" }} · " +
                        "${result.medicines.size} medicines detected",
                    style = MlxType.BodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }

        else -> Unit
    }
}
