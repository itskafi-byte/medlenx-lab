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
import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.PrescriptionHashRow
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.GpsFix
import com.medlenx.lab.data.model.GpsSource
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.repo.MedicineEnricher
import com.medlenx.lab.data.repo.PHash
import com.medlenx.lab.data.repo.RxAudit
import com.medlenx.lab.data.repo.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
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

    /**
     * Catalogue-resolved medicines. Populated by [MedicineEnricher] when the read
     * completes; the raw VL result stays untouched above.
     */
    val enriched: List<EnrichedMedicine> = emptyList(),

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
    private val prescriptionDao = app.graph.database.prescriptionDao()

    var state by mutableStateOf(ScanUiState())
        private set

    /**
     * The signed-in officer. Drives the Rx Audit header (rep code) and decides
     * which catalogue brands count as "own pharma" rather than competitors.
     *
     * This is the authoritative source for the officer's company. `deviceState
     * .companyName` only reflects whatever the last scan header happened to show
     * and stays null until then.
     */
    var officerProfile by mutableStateOf<OfficerProfileEntity?>(null)
        private set

    /** True while a save round-trip is in flight, so a second tap cannot double-insert. */
    var saving by mutableStateOf(false)
        private set

    /**
     * Rx numbers this scan duplicates, for the audit drawer's fraud note.
     *
     * Empty for a first-time capture. Populated from the perceptual-hash guard, so
     * the note only appears when the same physical Rx really has been scanned before.
     */
    var duplicateOfRxIds by mutableStateOf<List<String>>(emptyList())
        private set

    init {
        viewModelScope.launch {
            // Loads the district/upazila/territory cascade and the geo centroids.
            locationRepository.locations()
            locationRepository.geo()
        }
        viewModelScope.launch {
            // Separate launch: `observe()` never completes, so collecting it in the
            // same coroutine would stop the location cascade from ever being reached.
            app.graph.profileDao.observe().collect { officerProfile = it }
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

                ScanProgress.NeedsApiKey -> state = state.copy(phase = ScanPhase.NeedsKey)
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
    private suspend fun enterVerification(result: VlScanResult) {
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
            enriched = MedicineEnricher.enrich(
                medicines = result.medicines,
                index = scanRepository.medexIndex(),
                regulatory = app.graph.regulatoryRepository.data(),
                // Own-company basis for the substitution engine. Blank when no
                // officer profile is saved, which makes genericSubstitution
                // return null rather than guessing a manufacturer.
                ownCompany = officerProfile?.company.orEmpty(),
            ),
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
     * Persists the verified prescription, then shows the receipt.
     *
     * Port of the backend's `save_prescription`. Two things happen in order:
     *
     *  1. The captured image is perceptually hashed and compared against every
     *     stored hash, so re-uploading the same physical Rx is recorded as a
     *     duplicate rather than counted twice as target credit
     *     (`find_duplicate_prescription`).
     *  2. The header row and its itemised medicines are written by
     *     [com.medlenx.lab.data.repo.ScanRepository.saveVerified].
     *
     * The Rx number comes from the inserted primary key. The Figma export hardcodes
     * "A-128"; the web app has no Rx-number column at all, so any stable identifier
     * is a divergence - a real one beats a fake constant that repeats on every scan.
     */
    fun save() {
        val result = state.result
        if (result == null) {
            state = state.copy(error = "Nothing to save - the read did not complete")
            return
        }
        if (saving) return
        state = state.copy(error = null)
        // Captured up front: the coroutine below must not read mutable Compose state
        // after `state` has been reassigned.
        val doctor = state.doctor
        val geo = state.geo
        val imageFile = state.imageFile
        val profile = officerProfile
        saving = true
        viewModelScope.launch {
            runCatching {
                // Null when the image cannot be decoded. The web skips the guard for
                // a missing hash rather than guessing, and so does this.
                val hash = imageFile?.let { file ->
                    withContext(Dispatchers.IO) { PHash.compute(file) }
                        .takeIf { it.isNotBlank() }
                }
                val match = hash?.let { findDuplicate(it) }
                val repId = profile?.employeeId?.takeIf { it.isNotBlank() } ?: DEFAULT_MR_ID
                // Prescriptions are never deleted in this app, so the row count is a
                // safe monotonic sequence and the number can be known before insert -
                // no read-modify-write round trip to fill it in afterwards.
                val rxNo = "RX-${prescriptionDao.prescriptionCount() + 1}"

                scanRepository.saveVerified(
                    prescription = PrescriptionEntity(
                        rxNo = rxNo,
                        imagePath = imageFile?.absolutePath.orEmpty(),
                        imageHash = hash,
                        doctorName = doctor.name,
                        doctorBmdcNo = doctor.bmdcNo,
                        doctorSpecialty = doctor.specialty,
                        chamber = doctor.hospitalChamber,
                        district = doctor.district.ifBlank { geo.district },
                        upazila = doctor.upazila.ifBlank { geo.upazila },
                        territory = doctor.territory.ifBlank { geo.territory },
                        prescriptionSource = doctor.source.label,
                        // saveVerified re-derives these two from its own arguments;
                        // passing the real values keeps the entity self-consistent.
                        totalMedicines = result.medicines.size,
                        mrId = repId,
                        duplicateOf = match?.id,
                        offTerritory = geo.offTerritory,
                        territoryNote = geo.verdictReason.takeIf { it.isNotBlank() },
                        lat = geo.lat,
                        lng = geo.lng,
                        createdAt = System.currentTimeMillis(),
                    ),
                    result = result,
                    mrId = repId,
                    ownCompany = profile?.company,
                )
                duplicateOfRxIds = match?.let { listOf(it.rxNo) } ?: emptyList()
                state = state.copy(
                    phase = ScanPhase.Saved,
                    receipt = SavedReceipt(
                        rxNumber = rxNo,
                        medicineCount = result.medicines.size,
                        territory = doctor.territory.ifBlank { geo.territory },
                        repCode = repId,
                    ),
                )
            }.onFailure { e ->
                // Deliberately NOT ScanPhase.Failed. That screen has no way back, and
                // the officer's whole verified prescription is still on this panel -
                // a failed insert should be retryable, not a dead end that discards it.
                state = state.copy(
                    error = e.message ?: "Could not save the prescription",
                )
            }
            saving = false
        }
    }

    /**
     * Port of `find_duplicate_prescription`: the *closest* stored hash within
     * [RxAudit.DUPLICATE_THRESHOLD] bits, earliest row winning a tie.
     */
    private suspend fun findDuplicate(hash: String): PrescriptionHashRow? {
        var best: PrescriptionHashRow? = null
        var bestDistance = Int.MAX_VALUE
        for (row in prescriptionDao.hashRows()) {
            val other = row.imageHash ?: continue
            val distance = PHash.hammingDistance(hash, other) ?: continue
            if (distance <= RxAudit.DUPLICATE_THRESHOLD && distance < bestDistance) {
                best = row
                bestDistance = distance
            }
        }
        return best
    }

    /** Resets to the empty state so the rep can capture the next prescription. */
    fun scanAnother() { clear() }

    /** Captures a GPS fix and immediately geofences it against the assigned territory. */
    fun pinGps() {
        viewModelScope.launch {
            val live = locationRepository.lastKnownFix()
            // Indoors the last known fix is often stale or the provider is off. The
            // photo carries its own coordinates, so fall back to those rather than
            // dropping the GPS evidence from the audit altogether.
            val fix = live ?: state.imageFile?.let { locationRepository.exifFix(it) }
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
                pinSource = fix.source,
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
            // Rebuilding the fix from the stored coordinates must carry the original
            // provenance, or an EXIF pin would be relabelled as a live device fix.
            fix = fix ?: geo.lat?.let { lat ->
                geo.lng?.let { GpsFix(lat, it, geo.pinSource ?: GpsSource.Device) }
            },
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
        duplicateOfRxIds = emptyList()
    }

}

/** `save_prescription`'s own default when no officer profile has been saved. */
private const val DEFAULT_MR_ID = "MR001"

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
