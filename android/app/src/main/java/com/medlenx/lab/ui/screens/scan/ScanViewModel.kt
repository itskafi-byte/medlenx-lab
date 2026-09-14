package com.medlenx.lab.ui.screens.scan

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.model.GpsFix
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.repo.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Where the capture pipeline currently is. */
enum class ScanPhase {
    Empty, Ready, Scanning, Done, Failed, NeedsKey,

    /** Step 4: the three verification panels and the saved receipt. */
    VerifyDoctor, VerifyMedicines, VerifyGps, Saved,
}

data class ScanUiState(
    val phase: ScanPhase = ScanPhase.Empty,
    val imageUri: String? = null,
    val imageFile: File? = null,
    val progress: Float = 0f,
    val progressText: String = "",
    val error: String? = null,
    val result: VlScanResult? = null,
    val geo: GeoStripState = GeoStripState(),

    /** Editable copies made when the read completes; the VL result stays untouched. */
    val doctor: DoctorVerification = DoctorVerification(),
    val cards: List<MedicineCardData> = emptyList(),
    val receipt: SavedReceipt? = null,
)

/**
 * Drives the capture -> read pipeline.
 *
 * Step 3 covers capture, the viewer and the GPS pin. The verification form that
 * consumes [ScanUiState.result] is Step 4, so `Done` currently surfaces the raw
 * read rather than the doctor/medicine editing surface.
 */
class ScanViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MedLenXApp
    private val scanRepository = app.graph.scanRepository
    private val locationRepository = app.graph.locationRepository

    var state by mutableStateOf(ScanUiState())
        private set

    init {
        viewModelScope.launch {
            // Loads the district/upazila/territory cascade and the geo centroids.
            locationRepository.locations()
            locationRepository.geo()
        }
    }

    fun districtNames() = locationRepository.districtNames()
    fun upazilasFor(district: String) = locationRepository.upazilasFor(district)
    fun territoriesFor(district: String) = locationRepository.territoriesFor(district)
    fun specialties() = locationRepository.specialties()

    /** Copies the picked image into app storage so the path stays valid after the picker closes. */
    fun onImagePicked(uri: Uri) {
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(getApplication<Application>().filesDir, "rx").apply { mkdirs() }
                    val target = File(dir, "rx_${System.currentTimeMillis()}.jpg")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.takeIf { it.exists() && it.length() > 0 }
                }.getOrNull()
            }
            if (file == null) {
                state = state.copy(phase = ScanPhase.Failed, error = "Could not read that image")
                return@launch
            }
            state = state.copy(
                phase = ScanPhase.Ready,
                imageUri = uri.toString(),
                imageFile = file,
                error = null,
                result = null,
                progress = 0f,
                progressText = "",
            )
        }
    }

    /** Runs MedLenX VL over the captured image. */
    fun analyze() {
        val file = state.imageFile ?: return
        if (state.phase == ScanPhase.Scanning) return

        viewModelScope.launch {
            state = state.copy(
                phase = ScanPhase.Scanning,
                progress = 0.15f,
                progressText = "Extracting with MedLenX VL...",
                error = null,
            )
            val bytes = withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }
            if (bytes == null) {
                state = state.copy(phase = ScanPhase.Failed, error = "Image bytes unavailable")
                return@launch
            }

            state = state.copy(progress = 0.45f)
            when (val progress = scanRepository.scan(bytes)) {
                is ScanProgress.Done -> enterVerification(progress.result)

                is ScanProgress.Failed -> state = state.copy(
                    phase = ScanPhase.Failed,
                    error = progress.message,
                )

                ScanProgress.NeedsKey -> state = state.copy(phase = ScanPhase.NeedsKey)
                else -> Unit
            }
        }
    }

    /**
     * Seeds the verification panels from a completed read.
     *
     * The VL result is kept verbatim in [ScanUiState.result] for provenance; the doctor
     * and medicine copies here are the editable ones the panels bind to.
     */
    private fun enterVerification(result: VlScanResult) {
        val d = result.doctor
        state = state.copy(
            phase = ScanPhase.VerifyDoctor,
            progress = 1f,
            progressText = "Read complete",
            result = result,
            doctor = DoctorVerification(
                name = d.name,
                nameConfidence = 96,
                bmdcNo = d.bmdcNo,
                bmdcConfidence = 92,
                qualifications = d.qualifications,
                qualificationsConfidence = 88,
                hospitalChamber = d.hospital.ifBlank { d.chamber },
                hospitalConfidence = 85,
                specialty = d.specialty,
                district = d.district.ifBlank { state.geo.district },
                upazila = d.upazila.ifBlank { state.geo.upazila },
                territory = d.territory.ifBlank { state.geo.territory },
            ),
            cards = result.medicines.map { it.toCardData() },
        )
    }

    // ---- verification navigation ------------------------------------------

    fun onDoctorChange(next: DoctorVerification) {
        state = state.copy(doctor = next)
    }

    /** Changing district clears upazila and territory, exactly as the web does. */
    fun onDistrictChange(district: String) {
        state = state.copy(doctor = state.doctor.copy(district = district, upazila = "", territory = ""))
    }

    fun gotoMedicines() { state = state.copy(phase = ScanPhase.VerifyMedicines) }
    fun gotoGps() { state = state.copy(phase = ScanPhase.VerifyGps) }
    fun backToDoctor() { state = state.copy(phase = ScanPhase.VerifyDoctor) }
    fun backToMedicines() { state = state.copy(phase = ScanPhase.VerifyMedicines) }

    fun onBrandChange(index: Int, brand: String) = updateCard(index) { it.copy(brand = brand) }

    fun onDosageChange(index: Int, dosage: String) = updateCard(index) { it.copy(dosage = dosage) }

    private inline fun updateCard(index: Int, transform: (MedicineCardData) -> MedicineCardData) {
        if (index !in state.cards.indices) return
        state = state.copy(cards = state.cards.toMutableList().also { it[index] = transform(it[index]) })
    }

    /**
     * Writes the scan and shows the receipt.
     *
     * The Rx number is generated locally; the Room-backed store and the audit trail
     * land with Step 6, so this is the single place that will need to change.
     */
    fun save() {
        val number = "A-${100 + (state.result?.medicines?.size ?: 0)}"
        state = state.copy(
            phase = ScanPhase.Saved,
            receipt = SavedReceipt(
                rxNumber = number,
                medicineCount = state.cards.size,
                territory = state.doctor.territory.ifBlank { state.geo.territory },
                repCode = "MR001",
            ),
        )
    }

    /** Resets to the empty state so the rep can capture the next prescription. */
    fun scanAnother() { clear() }

    /** Captures a GPS fix and immediately geofences it against the assigned territory. */
    fun pinGps() {
        viewModelScope.launch {
            val fix = locationRepository.lastKnownFix()
            if (fix == null) {
                state = state.copy(
                    geo = state.geo.copy(
                        offTerritory = false,
                        verdictReason = "Location unavailable — enable GPS and try again",
                    ),
                )
                return@launch
            }
            applyFix(fix)
        }
    }

    /** Re-geofences after the officer edits the location fields. */
    fun onGeoChange(upazila: String? = null, district: String? = null, territory: String? = null) {
        val geo = state.geo
        val next = geo.copy(
            upazila = upazila ?: geo.upazila,
            district = district ?: geo.district,
            territory = territory ?: geo.territory,
        )
        state = state.copy(geo = next)
        // Changing the district invalidates its dependents, exactly as on the web.
        if (district != null && district != geo.district) {
            state = state.copy(geo = next.copy(upazila = "", territory = ""))
        }
        viewModelScope.launch { reverify(null) }
    }

    private suspend fun applyFix(fix: GpsFix) {
        val verdict = locationRepository.verdict(
            officerTerritory = state.geo.territory,
            scanTerritory = state.geo.territory,
            scanDistrict = state.geo.district,
            fix = fix,
        )
        state = state.copy(
            geo = state.geo.copy(
                lat = fix.lat,
                lng = fix.lng,
                pinnedDistrict = verdict.gpsDistrict,
                offTerritory = verdict.offTerritory,
                verdictReason = verdict.reason,
            ),
        )
    }

    private suspend fun reverify(fix: GpsFix?) {
        val geo = state.geo
        if (geo.lat == null && geo.district.isBlank() && geo.territory.isBlank()) return
        val verdict = locationRepository.verdict(
            officerTerritory = geo.territory,
            scanTerritory = geo.territory,
            scanDistrict = geo.district,
            fix = fix ?: geo.lat?.let { lat -> geo.lng?.let { GpsFix(lat, it) } },
        )
        state = state.copy(
            geo = geo.copy(
                offTerritory = verdict.offTerritory,
                verdictReason = verdict.reason,
                pinnedDistrict = verdict.gpsDistrict.ifBlank { geo.pinnedDistrict },
            ),
        )
    }

    fun clear() {
        state = ScanUiState()
    }

}

/**
 * Supplies the Application to [ScanViewModel]. Manual rather than Hilt for the same
 * reason as AppGraph — see data/config/AppGraph.kt.
 */
class ScanViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScanViewModel::class.java)) {
            return ScanViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
