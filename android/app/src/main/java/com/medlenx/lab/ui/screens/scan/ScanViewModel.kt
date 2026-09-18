package com.medlenx.lab.ui.screens.scan

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.PrescriptionHashRow
import com.medlenx.lab.data.local.QueuedScanEntity
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.GpsFix
import com.medlenx.lab.data.model.GpsSource
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.repo.MedicineEnricher
import com.medlenx.lab.data.repo.PHash
import com.medlenx.lab.data.repo.RxAudit
import com.medlenx.lab.data.repo.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
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
    val selectedMedicine: Int = 0,
    val brandSuggestions: List<MedexProduct> = emptyList(),
    val receipt: SavedReceipt? = null,

    /** True when the current capture is parked in the offline queue awaiting a replay. */
    val parked: Boolean = false,
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
    private val deviceState = app.graph.deviceState

    /** Parked captures are replayed from disk, so the mime travels with the row. */
    private val mimeType = "image/jpeg"

    /** Header-chip state, surfaced so the scan tab can render the offline queue banner. */
    val online: Boolean get() = deviceState.online
    val queuedScans: Int get() = deviceState.queuedScans

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
        viewModelScope.launch {
            // Also separate, and also never completes. This is what makes the header's
            // "N queued ... they will sync when you reconnect" an actual promise rather
            // than a label: every time connectivity comes back, replay a parked capture.
            snapshotFlow { deviceState.online }
                .distinctUntilChanged()
                .collect { isOnline -> if (isOnline) syncQueuedScans() }
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
            // No route to the API, so do not burn the capture. The web contract is
            // explicit - "scans cache on-device when the rural network drops, then sync
            // on reconnect" - and ScanProgress.QueuedOffline is the variant that carries
            // that outcome.
            val outcome: ScanProgress = if (!deviceState.online) {
                ScanProgress.QueuedOffline
            } else {
                state = state.copy(
                    phase = ScanPhase.Scanning,
                    progress = 0.15f,
                    progressText = "Extracting with MedLenX VL...",
                    error = null,
                    parked = false,
                )
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { file.readBytes() }.getOrNull()
                }
                if (bytes == null) {
                    // Not a connectivity problem - parking would just retry a file that
                    // cannot be read until the attempt cap drops it.
                    state = state.copy(phase = ScanPhase.Failed, error = "Image bytes unavailable")
                    return@launch
                }
                state = state.copy(progress = 0.45f)
                scanRepository.scan(bytes)
            }
            when (outcome) {
                is ScanProgress.Done -> enterVerification(outcome.result)

                is ScanProgress.Failed ->
                    // The network can drop after the request has gone out. Park rather
                    // than strand the officer on a panel with no way back.
                    if (!deviceState.online) {
                        parkForReplay(file)
                    } else {
                        state = state.copy(
                            phase = ScanPhase.Failed,
                            error = outcome.message,
                        )
                    }

                ScanProgress.QueuedOffline -> parkForReplay(file)

                ScanProgress.NeedsApiKey -> state = state.copy(phase = ScanPhase.NeedsKey)
                else -> Unit
            }
        }
    }

    /**
     * Parks the current capture for replay instead of losing it.
     *
     * Deliberately not [ScanPhase.Failed]: that panel has no way back, and the capture is
     * still perfectly good - only the network is missing. Staying on [ScanPhase.Ready]
     * keeps the viewer, the GPS strip and the Analyze button, so the officer can simply
     * retry by hand. [syncQueuedScans] retries automatically.
     */
    private suspend fun parkForReplay(file: File) {
        val parked = runCatching {
            scanRepository.queueForLater(file.absolutePath, mimeType)
        }.isSuccess
        state = state.copy(
            phase = ScanPhase.Ready,
            parked = parked,
            progress = 0f,
            progressText = "",
            error = if (parked) null else "Offline, and the capture could not be queued",
        )
    }

    /**
     * Replays the oldest parked capture, honouring the header's "they will sync when you
     * reconnect".
     *
     * Only ever runs from [ScanPhase.Empty] - genuinely idle, with no capture in hand.
     * `Ready` is deliberately excluded even when nothing is parked: at that point the
     * officer has picked an image and is about to analyse it, and a connectivity blip
     * must not swap the viewer to a different prescription underneath them. Rows that
     * cannot be read stay queued and are retried on the next connectivity change, up to
     * the repository's attempt cap.
     */
    fun syncQueuedScans() {
        if (!deviceState.online) return
        if (state.phase != ScanPhase.Empty) return
        viewModelScope.launch {
            scanRepository.drainQueue { row -> replayQueued(row) }
        }
    }

    /** Re-runs MedLenX VL over a parked capture. True once the row can be dropped. */
    private suspend fun replayQueued(row: QueuedScanEntity): Boolean {
        val file = File(row.imagePath)
        val bytes = withContext(Dispatchers.IO) {
            runCatching { file.takeIf { it.exists() }?.readBytes() }.getOrNull()
        } ?: return false
        return when (val progress = scanRepository.scan(bytes)) {
            is ScanProgress.Done -> {
                state = state.copy(
                    phase = ScanPhase.Ready,
                    imageUri = Uri.fromFile(file).toString(),
                    imageFile = file,
                    parked = false,
                    error = null,
                    result = null,
                    progress = 0f,
                    progressText = "",
                )
                enterVerification(progress.result)
                true
            }
            else -> false
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
        // Enrich first, then build the cards from the enriched list.
        //
        // The cards used to be built from `result.medicines` -- the raw vision read --
        // while the catalogue-resolved output went only to `enriched`, which feeds the
        // Rx Audit. So the panel the officer actually edits never saw the catalogue:
        // company stayed whatever the model guessed (usually blank) and there was no
        // pack photo, because only the enriched record carries one. That is why a
        // detected medicine showed a name with no image, and why the image appeared
        // only after picking a suggestion by hand.
        val enrichedMedicines = MedicineEnricher.enrich(
            medicines = result.medicines,
            index = scanRepository.medexIndex(),
            regulatory = app.graph.regulatoryRepository.data(),
            // Own-company basis for the substitution engine. Blank when no
            // officer profile is saved, which makes genericSubstitution
            // return null rather than guessing a manufacturer.
            ownCompany = officerProfile?.company.orEmpty(),
        )
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
            cards = enrichedMedicines.map { it.toCardData() },
            enriched = enrichedMedicines,
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

    /**
     * Folds the officer's card edits back into the VL read before it is persisted.
     *
     * A field is only overwritten when it differs from what [toCardData] originally showed,
     * so an untouched row round-trips unchanged. `dosageNormalized` is the field that wins
     * downstream (`ScannedMedicineEntity.dosage` prefers it), so a dosage edit has to land
     * there rather than on the raw `dosage`.
     */
    private fun mergeEdits(result: VlScanResult, cards: List<MedicineCardData>): VlScanResult {
        if (cards.isEmpty() || result.medicines.isEmpty()) return result
        val medicines = result.medicines.mapIndexed { index, med ->
            val card = cards.getOrNull(index) ?: return@mapIndexed med
            val shown = med.toCardData()
            med.copy(
                brandName = if (card.brand != shown.brand) card.brand else med.brandName,
                dosageNormalized = if (card.dosage != shown.dosage) {
                    card.dosage
                } else {
                    med.dosageNormalized
                },
            )
        }
        return result.copy(medicines = medicines)
    }

    fun onBrandChange(index: Int, brand: String) {
        updateCard(index) { it.copy(brand = brand) }
        state = state.copy(selectedMedicine = index)
        refreshSuggestions(index, brand)
    }

    fun selectMedicine(index: Int) {
        suggestionJob?.cancel()
        state = state.copy(selectedMedicine = index, brandSuggestions = emptyList())
    }

    /**
     * Applies a chosen catalogue product to the card.
     *
     * It fills every field the catalogue knows, not just the brand: choosing a
     * suggestion used to change nothing visible, because the tile the officer is
     * looking at is the pack photo, and the photo was not part of the update.
     */
    fun pickSuggestion(index: Int, product: MedexProduct) {
        updateCard(index) {
            it.copy(
                brand = product.brandName,
                ingredient = product.generic.ifBlank { product.ingredient },
                company = product.company,
                strength = product.strength.ifBlank { it.strength },
                type = product.form.ifBlank { product.type }.ifBlank { it.type },
                packImage = product.packImage?.takeIf { url -> url.isNotBlank() }
                    ?: product.imageUrl,
            )
        }
        suggestionJob?.cancel()
        state = state.copy(brandSuggestions = emptyList())
    }

    /**
     * Guards against overlapping lookups.
     *
     * Every keystroke used to launch its own unguarded coroutine. The first lookup
     * blocks on the catalogue import, so later keystrokes finished first and were then
     * overwritten by stale earlier results -- suggestions that appeared and vanished
     * depending on typing speed. One job at a time, and a result is only published if
     * it still matches what is in the field.
     */
    private var suggestionJob: Job? = null

    private fun refreshSuggestions(index: Int, brand: String) {
        suggestionJob?.cancel()
        val q = brand.trim()
        if (q.length < 2) {
            state = state.copy(brandSuggestions = emptyList())
            return
        }
        suggestionJob = viewModelScope.launch {
            // runCatching, not a bare call: this coroutine has no parent to report
            // to, so an exception here would kill it silently and the officer would
            // see a brand field that simply never suggests anything, with no error
            // anywhere. A failed lookup degrades to "no suggestions", not to a
            // dead collector.
            val matches = runCatching {
                scanRepository.medexIndex().all
                    .asSequence()
                    .filter { it.brandName.contains(q, ignoreCase = true) }
                    // Rank, don't just take the first four. The catalogue is in
                    // insertion order, so an unranked take(4) surfaces whatever
                    // happens to sit earliest in the file — for a common prefix
                    // that is a short generic token, not the brand being typed.
                    // Prefix matches first, then the tightest name wins.
                    .sortedWith(
                        compareBy<MedexProduct> {
                            if (it.brandName.startsWith(q, ignoreCase = true)) 0 else 1
                        }.thenBy { it.brandName.length },
                    )
                    .take(4)
                    .toList()
            }.getOrDefault(emptyList())

            // Drop stale results: if the officer has typed since this lookup began,
            // a newer one is already in flight and this list is out of date.
            if (!isActive) return@launch
            if (state.cards.getOrNull(index)?.brand?.trim() == q) {
                state = state.copy(brandSuggestions = matches)
            }
        }
    }

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
        val cards = state.cards
        saving = true
        viewModelScope.launch {
            runCatching {
                // The verification panels let the officer correct a brand or a dosage, and
                // those edits land in `cards` only - `result` keeps the raw VL read. Folding
                // them back first means the database stores what was actually signed off.
                val corrected = mergeEdits(result, cards)
                // Re-enrich the corrected read so the audit columns and the Rx Audit screen
                // describe the same medicines the officer approved.
                // Non-fatal on purpose: enrichment already succeeded once for this read in
                // enterVerification, so a throw here must not cost the officer the save.
                val audit = runCatching {
                    MedicineEnricher.enrich(
                        medicines = corrected.medicines,
                        index = scanRepository.medexIndex(),
                        regulatory = app.graph.regulatoryRepository.data(),
                        ownCompany = profile?.company.orEmpty(),
                    )
                }.getOrDefault(state.enriched)
                state = state.copy(enriched = audit)
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
                        totalMedicines = corrected.medicines.size,
                        mrId = repId,
                        duplicateOf = match?.id,
                        offTerritory = geo.offTerritory,
                        territoryNote = geo.verdictReason.takeIf { it.isNotBlank() },
                        lat = geo.lat,
                        lng = geo.lng,
                        createdAt = System.currentTimeMillis(),
                    ),
                    result = corrected,
                    mrId = repId,
                    ownCompany = profile?.company,
                    enriched = audit,
                )
                duplicateOfRxIds = match?.let { listOf(it.rxNo) } ?: emptyList()
                state = state.copy(
                    phase = ScanPhase.Saved,
                    receipt = SavedReceipt(
                        rxNumber = rxNo,
                        medicineCount = corrected.medicines.size,
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
        // Nothing re-emits on `snapshotFlow { online }` unless the network actually
        // changes, so finishing a scan is the other moment to offer up the next
        // parked capture. One at a time: it still has to pass verification.
        syncQueuedScans()
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
