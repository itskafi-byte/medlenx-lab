package com.medlenx.lab.ui.screens.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.medlenx.lab.data.model.ConfidenceBand
import com.medlenx.lab.data.model.confidenceBand
import com.medlenx.lab.data.repo.PyMath
import com.medlenx.lab.ui.components.ButtonTone
import com.medlenx.lab.ui.components.CompanyVerification
import com.medlenx.lab.ui.components.MlxButton
import com.medlenx.lab.ui.components.MlxCard
import androidx.compose.ui.draw.clip
import com.medlenx.lab.ui.components.MlxIconButton
import com.medlenx.lab.ui.components.MlxEmptyState
import com.medlenx.lab.ui.components.PillTone
import com.medlenx.lab.ui.components.SectionHeader
import com.medlenx.lab.ui.components.StatusPill
import com.medlenx.lab.ui.theme.Mlx
import com.medlenx.lab.ui.theme.MlxShape
import com.medlenx.lab.ui.theme.MlxType

/** Where the prescription was written — drives the analytics source filter. */
enum class PrescriptionSource(val label: String) {
    Hospital("Hospital"),
    PrivateChamber("Private Chamber"),
}

/**
 * The nine editable controls on the doctor panel: four text fields, specialty, the
 * district/upazila/territory cascade, and prescription source.
 *
 * Each text field carries the confidence the model reported for it, which decides
 * whether it renders clean or in the amber "please check this" state.
 */
data class DoctorVerification(
    val prescriptionDate: String = "",
    val name: String = "",
    val nameConfidence: Int = 0,
    val bmdcNo: String = "",
    val bmdcConfidence: Int = 0,
    val qualifications: String = "",
    val qualificationsConfidence: Int = 0,
    val hospitalChamber: String = "",
    val hospitalConfidence: Int = 0,
    val specialty: String = "",
    val district: String = "",
    val upazila: String = "",
    val territory: String = "",
    val source: PrescriptionSource = PrescriptionSource.Hospital,
)

/** GPS pin plus the territory verdict produced by the geofence rules. */
data class GpsVerification(
    val upazila: String = "",
    val district: String = "",
    val territory: String = "",
    val hasFix: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val offTerritory: Boolean = false,
    val reason: String = "",
)

/** The saved-receipt line shown after a successful write. */
data class SavedReceipt(
    val rxNumber: String,
    val medicineCount: Int,
    val territory: String,
    val repCode: String,
)

/** Uppercase 10sp/700 label with 0.08em tracking — Figma's field caption. */
@Composable
private fun FieldLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MlxType.MicroPill.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.08.em),
        color = Mlx.Text500,
        modifier = modifier.padding(bottom = 4.dp),
    )
}

/** 9sp slate helper line under a select. */
@Composable
private fun FieldHint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MlxType.RegulatoryPill,
        color = Mlx.Brand400,
        modifier = modifier.padding(top = 2.dp),
    )
}

/**
 * Text field with a confidence-driven skin — Figma `VerifyDoctor` (App.tsx:600-614).
 *
 * Height 40, radius 12, 13sp. At >=85% it is white with a slate border and a green
 * tick; below that it turns amber (`#FFFBEB` on `#FDE68A`) with a warning glyph, which
 * is how the app tells a rep which lines the model was unsure about.
 */
@Composable
private fun ConfidenceField(
    label: String,
    value: String,
    confidence: Int,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val confident = confidenceBand(confidence) == ConfidenceBand.High
    Column(modifier = modifier.fillMaxWidth()) {
        FieldLabel(label)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .background(if (confident) Mlx.Surface else Mlx.Warn50, MlxShape.Medium)
                .border(
                    1.dp,
                    if (confident) Mlx.Brand200 else Mlx.Warn200,
                    MlxShape.Medium,
                ),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MlxType.Body.copy(color = Mlx.Text900),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 36.dp)
                    .align(Alignment.CenterStart),
            )
            Icon(
                imageVector = if (confident) Icons.Filled.Check else Icons.Filled.Warning,
                contentDescription = if (confident) "$label confident" else "$label needs review",
                tint = if (confident) Mlx.Ok600 else Mlx.Warn500,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .size(14.dp),
            )
        }
    }
}

/**
 * Minimal select. Compose has no native `<select>`; this is a field plus a
 * [DropdownMenu], which keeps the dependency surface smaller than the experimental
 * `ExposedDropdownMenuBox`.
 */
@Composable
private fun MlxSelect(
    label: String,
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Select",
    height: androidx.compose.ui.unit.Dp = 40.dp,
    hint: String? = null,
    textStyle: TextStyle = MlxType.BodySmall,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier.fillMaxWidth()) {
        FieldLabel(label)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .background(Mlx.Surface, MlxShape.Medium)
                .border(1.dp, Mlx.Brand200, MlxShape.Medium)
                .clickable(enabled = options.isNotEmpty()) { expanded = true },
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = selected.ifBlank { placeholder },
                    style = textStyle,
                    color = if (selected.isBlank()) Mlx.Brand400 else Mlx.Text900,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    Icons.Filled.ExpandLess,
                    contentDescription = null,
                    tint = Mlx.Brand400,
                    modifier = Modifier.size(14.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option, style = MlxType.BodySmall) },
                        onClick = {
                            onSelect(option)
                            expanded = false
                        },
                    )
                }
            }
        }
        hint?.let { FieldHint(it) }
    }
}

/** Compact prescription thumbnail with shared touch gestures and fullscreen access. */
@Composable
private fun VerifyThumbnail(
    onFullscreen: () -> Unit,
    imageUri: String?,
    transform: ViewerTransform,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxWidth().height(180.dp).clip(MlxShape.Sheet).background(Mlx.Brand100)) {
        if (imageUri != null) {
            PrescriptionImageViewer(imageUri = imageUri, transform = transform)
            Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) {
                ViewerTools(transform, onFullscreen)
            }
        } else {
            Icon(Icons.Filled.CropFree, contentDescription = null, tint = Mlx.Brand400.copy(alpha = 0.4f),
                modifier = Modifier.align(Alignment.Center).size(48.dp))
        }
    }
}

/**
 * Doctor verification panel — Figma `VerifyDoctor` (App.tsx:566-653).
 *
 * Radius-16 card, 180dp thumbnail, then the header, the four confidence fields,
 * specialty, the three-column district/upazila/territory cascade and the prescription
 * source. Changing district clears upazila and territory, exactly as the web does.
 */
@Composable
fun VerifyDoctorSection(
    doctor: DoctorVerification,
    specialties: List<String>,
    districts: List<String>,
    upazilas: List<String>,
    territories: List<String>,
    onDoctorChange: (DoctorVerification) -> Unit,
    onDistrictChange: (String) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    medicineCount: Int,
    imageUri: String? = null,
    transform: ViewerTransform? = null,
    modifier: Modifier = Modifier,
) {
    var fullscreen by remember { mutableStateOf(false) }
    val fallbackTransform = remember { ViewerTransform() }
    val viewerTransform = transform ?: fallbackTransform
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .background(Mlx.Surface, MlxShape.Large),
    ) {
        if (fullscreen && imageUri != null) {
            FullscreenPrescriptionViewer(
                imageUri = imageUri,
                transform = viewerTransform,
                onDismiss = { fullscreen = false },
            )
        }
        VerifyThumbnail(
            onFullscreen = { fullscreen = true },
            imageUri = imageUri,
            transform = viewerTransform,
        )

        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Mlx.Ok500, modifier = Modifier.size(13.dp))
                Text(
                    text = "Data Verification",
                    style = MlxType.CardTitle.copy(fontWeight = FontWeight.SemiBold),
                    color = Mlx.Text900,
                )
                StatusPill(text = "Pending Verification", tone = PillTone.Amber, icon = Icons.Filled.Warning)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(Mlx.Surface, CircleShape)
                        .border(1.dp, Mlx.Brand200, CircleShape)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Mlx.Text600, modifier = Modifier.size(14.dp))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .background(Mlx.Brand50, MlxShape.Small)
                    .padding(12.dp)
                    .clickable(onClick = onNext),
            ) {
                Text(
                    text = if (medicineCount > 0) {
                        "$medicineCount medicine" +
                            (if (medicineCount == 1) "" else "s") +
                            " detected — tap here to review them"
                    } else {
                        "No medicines detected — retry the scan or add manually"
                    },
                    style = MlxType.BodySmall,
                    color = if (medicineCount > 0) Mlx.Ok500 else Mlx.Text600,
                )
            }

            SectionHeader(title = "Doctor Information — BMDC Verification")

            ConfidenceField(
                label = "Prescription date (as written)",
                value = doctor.prescriptionDate,
                confidence = 0,
                onValueChange = { onDoctorChange(doctor.copy(prescriptionDate = it)) },
                modifier = Modifier.padding(bottom = 10.dp),
            )
            ConfidenceField(
                label = "Doctor Name",
                value = doctor.name,
                confidence = doctor.nameConfidence,
                onValueChange = { onDoctorChange(doctor.copy(name = it)) },
                modifier = Modifier.padding(bottom = 10.dp),
            )
            ConfidenceField(
                label = "BMDC Reg No",
                value = doctor.bmdcNo,
                confidence = doctor.bmdcConfidence,
                onValueChange = { onDoctorChange(doctor.copy(bmdcNo = it)) },
                modifier = Modifier.padding(bottom = 10.dp),
            )
            ConfidenceField(
                label = "Qualifications",
                value = doctor.qualifications,
                confidence = doctor.qualificationsConfidence,
                onValueChange = { onDoctorChange(doctor.copy(qualifications = it)) },
                modifier = Modifier.padding(bottom = 10.dp),
            )
            ConfidenceField(
                label = "Hospital/Chamber",
                value = doctor.hospitalChamber,
                confidence = doctor.hospitalConfidence,
                onValueChange = { onDoctorChange(doctor.copy(hospitalChamber = it)) },
                modifier = Modifier.padding(bottom = 10.dp),
            )

            MlxSelect(
                label = "Specialty",
                options = specialties,
                selected = doctor.specialty,
                onSelect = { onDoctorChange(doctor.copy(specialty = it)) },
                placeholder = "Select specialty",
                hint = "Drives the specialty analytics",
                textStyle = MlxType.BodySmall,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            // District -> Upazila -> Territory. Three across, 36dp tall.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MlxSelect(
                    label = "District",
                    options = districts,
                    selected = doctor.district,
                    onSelect = onDistrictChange,
                    placeholder = "Select",
                    height = 36.dp,
                    textStyle = MlxType.Meta,
                    modifier = Modifier.weight(1f),
                )
                MlxSelect(
                    label = "Upazila",
                    options = upazilas,
                    selected = doctor.upazila,
                    onSelect = { onDoctorChange(doctor.copy(upazila = it)) },
                    placeholder = if (doctor.district.isBlank()) "Select district first" else "Select",
                    height = 36.dp,
                    textStyle = MlxType.Meta,
                    modifier = Modifier.weight(1f),
                )
                MlxSelect(
                    label = "Territory",
                    options = territories,
                    selected = doctor.territory,
                    onSelect = { onDoctorChange(doctor.copy(territory = it)) },
                    placeholder = "Select",
                    height = 36.dp,
                    textStyle = MlxType.Meta,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "Changing district clears Upazila and Territory",
                style = MlxType.RegulatoryPill,
                color = Mlx.Brand400,
                modifier = Modifier.padding(bottom = 10.dp),
            )

            MlxSelect(
                label = "Prescription Source",
                options = PrescriptionSource.entries.map { it.label },
                selected = doctor.source.label,
                onSelect = { label ->
                    PrescriptionSource.entries.firstOrNull { it.label == label }
                        ?.let { onDoctorChange(doctor.copy(source = it)) }
                },
                hint = "Drives the source filter tag",
                textStyle = MlxType.BodySmall,
                modifier = Modifier.padding(bottom = 16.dp),
            )

            // The medicines live on the next panel; naming the detected count here means
            // a successful read never looks like "nothing was scanned".
            Text(
                text = if (medicineCount == 1) {
                    "1 medicine detected - tap Next to review it"
                } else {
                    "$medicineCount medicines detected - tap Next to review them"
                },
                style = MlxType.BodySmall,
                color = Mlx.Text600,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MlxButton(
                    text = "Back",
                    tone = ButtonTone.Outline,
                    onClick = onBack,
                    icon = Icons.AutoMirrored.Filled.ArrowBack,
                )
                MlxButton(
                    text = "Next: Medicines",
                    tone = ButtonTone.Primary,
                    onClick = onNext,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Full-screen review surface for a prescription.
 *
 * The inline viewers are clipped to their panels so a zoomed scan cannot paint over
 * the form and the medicine cards around them. This is the escape hatch that gives
 * the zoom the whole display. It reuses the caller's [ViewerTransform], so the
 * officer's zoom, pan and rotation survive expanding *and* collapsing — and the
 * same transform is used in both views.
 */
@Composable
fun FullscreenPrescriptionViewer(
    imageUri: String,
    transform: ViewerTransform,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            PrescriptionImageViewer(
                imageUri = imageUri,
                transform = transform,
                modifier = Modifier.fillMaxSize(),
            )
            Row(
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ViewerTools(transform)
                MlxIconButton(Icons.Filled.Close, "Exit fullscreen", onDismiss)
            }
        }
    }
}

/**
 * Medicine review — Figma `VerifyMedicines` (App.tsx:654-678).
 *
 * The legend text is reproduced verbatim from the export, including its "<80%", even
 * though `ConfBadge` itself flips at 85. The wording is user-facing copy; the band
 * logic follows the code.
 */
@Composable
fun VerifyMedicinesSection(
    cards: List<MedicineCardData>,
    onBrandChange: (Int, String) -> Unit,
    onDosageChange: (Int, String) -> Unit,
    onVerifyAgainstMedex: (Int) -> Unit,
    onReportMisId: (Int) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    imageUri: String? = null,
    transform: ViewerTransform? = null,
    selectedMedicine: Int = 0,
    onSelectMedicine: (Int) -> Unit = {},
    suggestions: List<com.medlenx.lab.data.model.MedexProduct> = emptyList(),
    onPickSuggestion: (Int, com.medlenx.lab.data.model.MedexProduct) -> Unit = { _, _ -> },
    onPickAlternative: (Int, com.medlenx.lab.data.model.MedexProduct) -> Unit = { _, _ -> },
    /** (medicine index, pitch script) — the substitution card's "Copy pitch". */
    onCopyPitch: (Int, String) -> Unit = { _, _ -> },
    /** The substitution card's "Mark as won", for the medicine at that index. */
    onMarkWon: (Int) -> Unit = {},
    /**
     * True when the own company is the fallback rather than the rep's own choice.
     *
     * The substitution picks and their pitch buttons are computed against it, so when
     * the rep has not set a company in Settings the card says which company it assumed
     * instead of presenting an assumption as the rep's own portfolio.
     */
    ownCompanyAssumed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var fullscreen by remember { mutableStateOf(false) }
    val fallbackTransform = remember { ViewerTransform() }
    val viewerTransform = transform ?: fallbackTransform
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val viewerHeight = maxHeight * 0.28f
    Column(modifier = Modifier.fillMaxSize()) {
        if (fullscreen && imageUri != null) {
            FullscreenPrescriptionViewer(
                imageUri = imageUri,
                transform = viewerTransform,
                onDismiss = { fullscreen = false },
            )
        }
        // Keep the clean prescription visible while reviewing medicines.
        imageUri?.let { uri ->
            val t = viewerTransform
            Column(modifier = Modifier.padding(bottom = 12.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(viewerHeight)
                        .background(Mlx.Brand100, MlxShape.Medium),
                ) {
                    PrescriptionImageViewer(imageUri = uri, transform = t)
                    Box(Modifier.align(Alignment.TopEnd).padding(8.dp)) {
                        ViewerTools(t) { fullscreen = true }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
        MlxCard(modifier = Modifier.padding(bottom = 12.dp)) {
            SectionHeader(title = "Medicines Order & Confidence Review")
            Text(
                text = "Green \u2713 high confidence · Orange \uD83E\uDD16 AI Guess <80% · Tap a medicine to edit it",
                style = MlxType.MicroPill.copy(fontWeight = FontWeight.Normal),
                color = Mlx.Text600,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            // The substitution picks below are computed for one company; when that is
            // the fallback rather than the rep's, say so here - before the picks are
            // read and saved, not only afterwards in the audit drawer's footer.
            if (ownCompanyAssumed) {
                Text(
                    text = "Substitution picks use the default own company — set yours " +
                        "in Settings to see your own portfolio instead.",
                    style = MlxType.MicroPill.copy(fontWeight = FontWeight.Normal),
                    color = Mlx.Warn500,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            if (cards.isEmpty()) {
                MlxEmptyState(
                    message = "No medicines were detected on this scan. Rescan with better " +
                        "lighting, or go back and check the image.",
                    ctaLabel = "Back to doctor",
                    onCta = onBack,
                )
            } else {
                cards.forEachIndexed { index, card ->
                    MedicineCard(
                        data = card,
                        onBrandChange = { onBrandChange(index, it) },
                        onDosageChange = { onDosageChange(index, it) },
                        onVerifyAgainstMedex = { onVerifyAgainstMedex(index) },
                        onReportMisId = { onReportMisId(index) },
                        onSelected = { onSelectMedicine(index) },
                        suggestions = if (index == selectedMedicine) suggestions else emptyList(),
                        onPickSuggestion = { onPickSuggestion(index, it) },
                        onPickAlternative = { onPickAlternative(index, it) },
                        onCopyPitch = { pitch -> onCopyPitch(index, pitch) },
                        onMarkWon = { onMarkWon(index) },
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }

        // Sticky footer strip: slate wash with a hairline top border.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Mlx.Brand50),
        ) {
            // Figma uses borderTop only, so a hairline Box rather than a full border.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Mlx.Brand200),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Mlx.Brand50)
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MlxButton(
                text = "Back",
                tone = ButtonTone.Outline,
                onClick = onBack,
                icon = Icons.AutoMirrored.Filled.ArrowBack,
            )
            MlxButton(
                text = "Verify & Save to DB",
                tone = ButtonTone.Success,
                onClick = onSave,
                icon = Icons.Filled.Check,
                modifier = Modifier.weight(1f),
            )
        }
        }
    }
    }
}

/**
 * GPS and territory — Figma `VerifyGPS` (App.tsx:679-716).
 *
 * Three 32dp fields plus a GPS button, then a single status line that is green when
 * the pin resolves inside the assigned territory and red when it does not. The export
 * also has a "Toggle territory state (demo)" button; that is a mock affordance and is
 * deliberately not ported — the real state comes from the geofence rules.
 */
@Composable
fun VerifyGpsSection(
    gps: GpsVerification,
    onChange: (GpsVerification) -> Unit,
    onCaptureGps: () -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
    /** True while the save round-trip is in flight, so the button cannot double-fire. */
    saving: Boolean = false,
) {
    MlxCard(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = "GPS & Territory")

        BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            if (maxWidth < 480.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GpsField("Upazila", gps.upazila, { onChange(gps.copy(upazila = it)) }, Modifier.weight(1f))
                        GpsField("District", gps.district, { onChange(gps.copy(district = it)) }, Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        GpsField("Territory", gps.territory, { onChange(gps.copy(territory = it)) }, Modifier.weight(1f))
                        GpsIconButton(onCaptureGps)
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                    GpsField("Upazila", gps.upazila, { onChange(gps.copy(upazila = it)) }, Modifier.weight(1f))
                    GpsField("District", gps.district, { onChange(gps.copy(district = it)) }, Modifier.weight(1f))
                    GpsField("Territory", gps.territory, { onChange(gps.copy(territory = it)) }, Modifier.weight(1f))
                    GpsIconButton(onCaptureGps)
                }
            }
        }

        val coords = gps.latitude?.let { lat ->
            gps.longitude?.let { lng -> PyMath.fixedCoords(lat, lng) }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (gps.offTerritory) {
                Icon(Icons.Filled.LocationOn, contentDescription = null, tint = Mlx.Danger, modifier = Modifier.size(12.dp))
                Text(
                    text = gps.reason.ifBlank { "Off-Territory Audit" },
                    style = MlxType.MicroPill.copy(fontWeight = FontWeight.Normal),
                    color = Mlx.Danger,
                )
            } else {
                Icon(Icons.Filled.Check, contentDescription = null, tint = Mlx.Ok600, modifier = Modifier.size(12.dp))
                Text(
                    text = buildString {
                        append("GPS pinned")
                        if (coords != null) append(" · ").append(coords)
                        if (gps.territory.isNotBlank()) append(" · ").append(gps.territory)
                        append(" (in territory)")
                    },
                    style = MlxType.MicroPill.copy(fontWeight = FontWeight.Normal),
                    color = Mlx.Ok600,
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MlxButton(text = "Back", tone = ButtonTone.Outline, onClick = onBack, icon = Icons.AutoMirrored.Filled.ArrowBack)
            MlxButton(
                text = if (saving) "Saving…" else "Verify & Save to DB",
                tone = ButtonTone.Success,
                onClick = onSave,
                icon = Icons.Filled.Check,
                enabled = !saving,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun GpsField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MlxType.MicroPill.copy(fontWeight = FontWeight.Bold),
            color = Mlx.Text500,
            modifier = Modifier.padding(bottom = 3.dp),
        )
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MlxType.Meta.copy(color = Mlx.Text900),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(Mlx.Surface, MlxShape.Chip)
                .border(1.dp, Mlx.Brand200, MlxShape.Chip)
                .padding(horizontal = 8.dp),
        )
    }
}

/**
 * Saved confirmation — Figma `ScanSaved` (App.tsx:717-747).
 *
 * Success toast, then a centred card with a 56dp emerald tick, the headline, the
 * receipt line and two ghost actions, then the shared recent-prescriptions list.
 */
@Composable
fun ScanSavedSection(
    receipt: SavedReceipt,
    toastMessage: String,
    onScanAnother: () -> Unit,
    onOpenAudit: () -> Unit,
    modifier: Modifier = Modifier,
    recentRows: List<com.medlenx.lab.ui.screens.analytics.RecentRxRow> = emptyList(),
    onSelectPrescription: (com.medlenx.lab.ui.screens.analytics.RecentRxRow) -> Unit = {},
) {
    Column(modifier = modifier.fillMaxWidth()) {
        StatusPill(
            text = toastMessage,
            tone = PillTone.EmeraldSolid,
            icon = Icons.Filled.Check,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        MlxCard(modifier = Modifier.padding(bottom = 16.dp)) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .background(Mlx.Ok50, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = Mlx.Ok600, modifier = Modifier.size(24.dp))
                }
                Text(
                    text = "Prescription saved",
                    style = MlxType.PanelTitle,
                    color = Mlx.Text900,
                    modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
                )
                Text(
                    text = "Rx #${receipt.rxNumber} · ${receipt.medicineCount} medicines · " +
                        "${receipt.territory} · ${receipt.repCode}",
                    style = MlxType.BodySmall,
                    color = Mlx.Text500,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MlxButton(text = "Open audit summary", tone = ButtonTone.Outline, onClick = onOpenAudit)
                    MlxButton(text = "Scan another", tone = ButtonTone.Outline, onClick = onScanAnother)
                }
            }
        }

        // App.tsx:744 — the export renders the shared list under the success card.
        com.medlenx.lab.ui.screens.analytics.RecentPrescriptions(
            rows = recentRows,
            onSelect = onSelectPrescription,
        )
    }
}

/** Maps an enriched medicine onto the card the review list renders. */
fun com.medlenx.lab.data.model.EnrichedMedicine.toCardData(): MedicineCardData {
    val verification = when {
        company.isNullOrBlank() -> CompanyVerification.None
        companyVerified -> CompanyVerification.Verified
        else -> CompanyVerification.Unverified
    }
    return MedicineCardData(
        brand = brandName,
        confidencePct = confidencePercent,
        type = type.ifBlank { form },
        ingredient = genericName,
        strength = strength,
        dosage = dosageNormalized.ifBlank { dosage },
        company = company.orEmpty(),
        companyVerification = verification,
        // The catalogue-override note applies when the catalogue resolved the brand to
        // something other than what the scan literally read. MatchType has no
        // "Catalogue" member, so this is derived from the match plus a text compare.
        catalogueNote = if (
            matchType != com.medlenx.lab.data.model.MatchType.None &&
            rawText.isNotBlank() &&
            !rawText.equals(brandName, ignoreCase = true)
        ) {
            "Scan read \"$rawText\" - corrected from MedEx catalogue."
        } else {
            null
        },
        rawText = rawText,
        matchType = matchType.label,
        lineRef = "#L$lineNumber",
        // The enricher already resolved the MedEx pack photo; without this line it
        // was dropped at the card boundary and never reached the UI.
        packImage = imageUrl,
        medexProductId = medexProductId,
        // Same trap, same fix: `MedicineEnricher` computes every variant of the
        // matched brand so a wrong strength or manufacturer can be corrected by hand,
        // and the card boundary dropped the list before any picker could show it.
        alternatives = alternatives,
        // And again: the enricher builds the competitor → own-brand card here too.
        substitution = substitution,
    )
}

/**
 * Maps a raw VL read onto the review card.
 *
 * This is no longer the mapper the review list is built from — the flow enriches
 * first and builds every card from `EnrichedMedicine` (`ScanViewModel.enterVerification`),
 * so the mapper above is the live one. This one's remaining job is to be the
 * "before" side of the comparison in `ScanViewModel.mergeEdits`: folding an edit
 * back into the VL read means knowing which fields the card still shows unedited,
 * and that is exactly what this function describes. It also carries no
 * `alternatives`, because a raw read has not been near the catalogue yet.
 *
 * The prompt forbids the model from guessing a manufacturer, so `company` is empty
 * unless it was literally printed — that renders the "Company not identified" state
 * rather than an invented name.
 */
fun com.medlenx.lab.data.model.VlMedicine.toCardData(): MedicineCardData {
    val pct = (confidence * 100).toInt()
    return MedicineCardData(
        brand = brandName,
        confidencePct = pct,
        type = type.ifBlank { form },
        ingredient = genericName,
        strength = strength,
        dosage = dosageNormalized.ifBlank { dosage },
        company = company,
        companyVerification = if (company.isBlank()) {
            CompanyVerification.None
        } else {
            CompanyVerification.Unverified
        },
        rawText = rawText,
        matchType = if (company.isBlank()) "No catalogue match" else "MedEx exact match",
        lineRef = null,
        bbox = bbox,
    )
}
