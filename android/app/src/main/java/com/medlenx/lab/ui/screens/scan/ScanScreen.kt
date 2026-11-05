package com.medlenx.lab.ui.screens.scan

import android.Manifest
import android.content.pm.PackageManager
import com.google.firebase.auth.FirebaseAuth
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.core.content.ContextCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.medlenx.lab.data.repo.MedicineMatcher
import com.medlenx.lab.ui.screens.hub.HubViewModel
import com.medlenx.lab.ui.components.ButtonTone
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxCard
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.MlxErrorLine
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.copyToClipboard
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxD
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType
import kotlinx.coroutines.delay

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
    hubVm: HubViewModel,
    onOpenAudit: () -> Unit,
    onOpenHub: () -> Unit,
    onOpenUrl: (String) -> Unit,
    modifier: Modifier = Modifier,
    recentRows: List<com.medlenx.lab.ui.screens.analytics.RecentRxRow> = emptyList(),
    onSelectPrescription: (com.medlenx.lab.ui.screens.analytics.RecentRxRow) -> Unit = {},
) {
    val context = LocalContext.current
    val state = vm.state
    val transform = remember(state.imageUri) { ViewerTransform() }
    // The shell already reserves the status and app-bar height.
    val topInset = 0.dp

    var cameraOpen by remember { mutableStateOf(false) }
    var cameraUid by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingGalleryUid by rememberSaveable { mutableStateOf<String?>(null) }
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (pendingGalleryUid != null && pendingGalleryUid == FirebaseAuth.getInstance().currentUser?.uid) {
            uri?.let(vm::onImagePicked)
        }
        pendingGalleryUid = null
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted && cameraUid != null && FirebaseAuth.getInstance().currentUser?.uid == cameraUid) {
            cameraOpen = true
        } else {
            cameraUid = null
            android.widget.Toast.makeText(context, "Camera permission is needed to capture a prescription.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    if (cameraOpen) {
        CameraViewfinder(
            onClose = { cameraOpen = false; cameraUid = null },
            onCaptured = { bytes ->
                val uid = cameraUid
                cameraOpen = false
                cameraUid = null
                if (uid != null && FirebaseAuth.getInstance().currentUser?.uid == uid) vm.onCameraCaptured(bytes, uid)
            },
        )
    }

    // GPS pin needs the runtime location permission; ask on demand, then pin.
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { vm.pinGps() }
    val pinGpsWithPermission = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            vm.pinGps()
        } else {
            locationLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    when (state.phase) {
        ScanPhase.Empty -> ScanHome(
            vm = vm,
            hubVm = hubVm,
            onOpenAudit = onSelectPrescription,
            onOpenHub = onOpenHub,
            onOpenUrl = onOpenUrl,
            onGallery = {
                pendingGalleryUid = FirebaseAuth.getInstance().currentUser?.uid
                galleryLauncher.launch(
                    // Builder, not the constructor: the PickVisualMediaRequest(mediaType)
                    // ctor is deprecated and is gone from the current androidx API surface.
                    // Builder() + setMediaType() exists in both 1.9.3 and current.
                    PickVisualMediaRequest.Builder()
                        .setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        .build(),
                )
            },
            onCamera = {
                cameraUid = FirebaseAuth.getInstance().currentUser?.uid
                if (cameraUid == null || cameraUid != vm.currentUid) {
                    cameraUid = null
                    android.widget.Toast.makeText(context, "Sign in before capturing a prescription.", android.widget.Toast.LENGTH_LONG).show()
                } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                    cameraOpen = true
                } else {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                }
            },
            modifier = modifier.padding(top = topInset),
        )

        ScanPhase.VerifyDoctor -> VerifyDoctorSection(
            doctor = state.doctor,
            specialties = vm.specialties(),
            districts = vm.districtNames(),
            upazilas = vm.upazilasFor(state.doctor.district),
            territories = vm.territoriesFor(state.doctor.district),
            onDoctorChange = vm::onDoctorChange,
            onDistrictChange = vm::onDistrictChange,
            onBack = vm::clear,
            onNext = vm::gotoMedicines,
            onClose = vm::clear,
            medicineCount = state.cards.size,
            imageUri = state.imageUri,
            transform = transform,
            modifier = modifier.padding(top = topInset),
        )

        ScanPhase.VerifyMedicines -> VerifyMedicinesSection(
            cards = state.cards,
            onBrandChange = vm::onBrandChange,
            onDosageChange = vm::onDosageChange,
            onVerifyAgainstMedex = vm::verifyAgainstMedex,
            onReportMisId = vm::reportMisId,
            onBack = vm::backToDoctor,
            onSave = vm::gotoGps,
            imageUri = state.imageUri,
            transform = transform,
            selectedMedicine = state.selectedMedicine,
            onSelectMedicine = vm::selectMedicine,
            suggestions = state.brandSuggestions,
            onPickSuggestion = vm::pickSuggestion,
            onPickAlternative = vm::applyAlternative,
            // The web's toast is "Pitch note copied"; the shared helper appends where
            // it went, so the label is the pitch note and not the mechanism.
            onCopyPitch = { _, pitch -> copyToClipboard(context, pitch, "Pitch note") },
            onMarkWon = vm::markConversionWon,
            // Same resolution the view model used for the picks, so the notice and the
            // picks cannot disagree about whether the company was configured.
            ownCompanyAssumed = !MedicineMatcher.resolveOwnCompany(vm.officerProfile?.company).confirmed,
            modifier = modifier.padding(top = topInset),
        )

        ScanPhase.VerifyGps -> Column(
            modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
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
                onCaptureGps = { pinGpsWithPermission() },
                onBack = vm::backToMedicines,
                onSave = vm::save,
                saving = vm.saving,
            )
        }

        ScanPhase.Saved -> ScanSavedSection(
            receipt = state.receipt ?: SavedReceipt("", 0, "", ""),
            toastMessage = "Prescription saved on this device — Rx #" +
                (state.receipt?.rxNumber.orEmpty()),
            onScanAnother = vm::scanAnother,
            onOpenAudit = onOpenAudit,
            recentRows = recentRows,
            // Without this the row tap was a no-op: ScanSavedSection already forwards
            // onSelect, but nothing supplied it.
            onSelectPrescription = onSelectPrescription,
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
                onPinGps = { pinGpsWithPermission() },
            )
            ActionRow(state = state, onAnalyze = vm::analyze)
            VerificationArea(state = state)
        }
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
                            "They can be scanned when you reconnect."
                    queued > 0 -> "$queued scan$plural queued for replay."
                    else -> "Scan cached on-device for scanning when connected."
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
    var fullscreen by remember { mutableStateOf(false) }
    if (fullscreen && state.imageUri != null) {
        FullscreenPrescriptionViewer(state.imageUri, transform, onDismiss = { fullscreen = false })
    }
    MlxCard(padding = 0.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    SectionHeader(title = "Prescription Image Viewer")
                }
                if (state.imageUri != null) ViewerTools(transform) { fullscreen = true }
                MlxIconButton(
                    icon = Icons.Filled.Close,
                    contentDescription = "Clear workspace",
                    onClick = onClear,
                    background = Mlx.Brand100,
                    border = Mlx.Brand100,
                )
            }
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
                        text = "MedLenX VL Scanning",
                        tone = PillTone.Dark,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
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
            val waiting = state.progressIndeterminate && state.progressText == "Waiting for AI response…"
            var stage by remember(waiting) { mutableStateOf(0) }
            LaunchedEffect(waiting) {
                if (waiting) {
                    while (true) {
                        delay(2000)
                        stage = (stage + 1) % 3
                    }
                }
            }
            val illustrativeStages = listOf("Uploading image...", "Detecting text regions...", "Decoding handwriting...")
            Text(if (waiting) illustrativeStages[stage] else state.progressText, style = MlxType.Meta)
            if (waiting) Text("Illustrative stages and highlights; waiting for the AI response.", style = MlxType.Footnote)
            if (state.progressIndeterminate) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp), color = Mlx.Brand500, trackColor = Mlx.Brand100)
            } else {
                LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth().height(5.dp), color = Mlx.Brand500, trackColor = Mlx.Brand100)
            }
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
        ScanPhase.Scanning -> ScanSkeleton(
            result = state.result,
            cards = state.cards,
            revealedMedicines = state.revealedMedicines,
            indeterminate = state.progressIndeterminate,
            preparing = !state.progressIndeterminate && state.progress < 0.85f,
        )

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
