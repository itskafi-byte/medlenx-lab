package com.medlenx.lab.ui.screens.scan

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import com.medlenx.lab.data.remote.DocumentLocator
import android.net.Uri
import androidx.room.withTransaction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.google.firebase.auth.FirebaseAuth
import com.medlenx.lab.data.local.UserCaptureFiles
import com.medlenx.lab.data.sync.TrustedMembership
import com.medlenx.lab.data.sync.UploadScheduling
import com.medlenx.lab.data.local.DailyTargetSettings
import com.medlenx.lab.data.local.DrillCountRow
import com.medlenx.lab.data.local.ErrorReportEntity
import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.PrescriptionHashRow
import com.medlenx.lab.data.local.QueuedScanEntity
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.GpsFix
import com.medlenx.lab.data.model.GpsSource
import com.medlenx.lab.data.model.MatchType
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.repo.MedicineEnricher
import com.medlenx.lab.data.repo.MedicineMatcher
import com.medlenx.lab.data.repo.Intelligence
import com.medlenx.lab.data.repo.PHash
import com.medlenx.lab.data.repo.RxAudit
import com.medlenx.lab.ui.components.CompanyVerification
import com.medlenx.lab.data.repo.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

data class DashboardBrandKpi(val brand: String, val target: Int, val scans: Int)

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
    val progressIndeterminate: Boolean = true,
    val revealedMedicines: Int = 0,
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
    val savedPrescriptionId: Long? = null,
    val capturedBitmap: Bitmap? = null,
    val detectedLines: List<Rect> = emptyList(),
    val selectedLines: Set<Int> = emptySet(),
    val queuedId: Long? = null,
    val locating: Boolean = false,
    val locatorError: String? = null,

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

    private val locator = DocumentLocator()
    private var locateJob: Job? = null
    private val app = application as MedLenXApp
    private val scanRepository = app.graph.scanRepository
    private val locationRepository = app.graph.locationRepository
    private val prescriptionDao = app.graph.database.prescriptionDao()
    private val deviceState = app.graph.deviceState
    val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()

    private val targetPrefs = application.getSharedPreferences(DailyTargetSettings.PREFERENCES + "_" + UserCaptureFiles.key(currentUid), Context.MODE_PRIVATE)
    var dailyTarget by mutableStateOf(targetPrefs.getInt(DailyTargetSettings.KEY, DailyTargetSettings.DEFAULT))
        private set
    var claimableAudits by mutableStateOf(0)
        private set
    var claimCompany by mutableStateOf<String?>(null)
        private set
    var claimCount by mutableStateOf(0)
        private set
    private var claimIds: Set<Long> = emptySet()
    var claimBusy by mutableStateOf(false)
        private set
    var claimMessage by mutableStateOf<String?>(null)
        private set
    var pendingUploads by mutableStateOf(0)
        private set
    var unroutableUploads by mutableStateOf(0)
        private set
    var todayCount by mutableStateOf(0)
        private set
    // Null means the Room flows have not emitted yet; empty means no data.
    var monthlyBrandKpis by mutableStateOf<List<DashboardBrandKpi>?>(null)
        private set
    var weeklyTopDoctors by mutableStateOf<List<DrillCountRow>?>(null)
        private set
    var recentCaptures by mutableStateOf<List<PrescriptionEntity>>(emptyList())
        private set
    private val targetListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == DailyTargetSettings.KEY) dailyTarget = prefs.getInt(key, DailyTargetSettings.DEFAULT)
    }

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
    private var scanJob: Job? = null
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
        targetPrefs.registerOnSharedPreferenceChangeListener(targetListener)
        viewModelScope.launch { prescriptionDao.observeRecent(currentUid, 4).collect { recentCaptures = it } }
        viewModelScope.launch { prescriptionDao.observePendingUploads(currentUid).collect { pendingUploads = it } }
        viewModelScope.launch { prescriptionDao.observeClaimableAudits(currentUid).collect { claimableAudits = it } }
        viewModelScope.launch { prescriptionDao.observeUnroutableUploads(currentUid).collect { unroutableUploads = it } }
        viewModelScope.launch {
            var day: java.time.LocalDate? = null
            var counter: Job? = null
            while (isActive) {
                val now = java.time.LocalDate.now()
                if (day != now) {
                    day = now
                    counter?.cancel()
                    val zone = java.time.ZoneId.systemDefault()
                    val start = now.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = now.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    counter = launch { prescriptionDao.observeCountBetween(currentUid, start, end).collect { todayCount = it } }
                }
                delay(30_000)
            }
        }
        viewModelScope.launch {
            var monthKey: Pair<YearMonth, ZoneId>? = null
            var weekKey: Pair<LocalDate, ZoneId>? = null
            var monthJob: Job? = null
            var weekJob: Job? = null
            while (isActive) {
                val zone = ZoneId.systemDefault()
                val now = LocalDate.now(zone)
                val month = YearMonth.from(now) to zone
                val weekStart = now.minusDays((now.dayOfWeek.value - 1).toLong())
                val week = weekStart to zone
                if (month != monthKey) {
                    monthKey = month
                    monthJob?.cancel()
                    monthlyBrandKpis = null
                    val start = month.first.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = month.first.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    monthJob = launch {
                        combine(app.graph.profileDao.observeTargets(currentUid),
                            prescriptionDao.observeDashboardBrandScans(currentUid, start, end)) { targets, counts ->
                            val byBrand = counts.associate { it.brand.trim().lowercase(Locale.ROOT) to it.captured }
                            targets.filter { it.brand.isNotBlank() }.map { target ->
                                DashboardBrandKpi(target.brand, target.target,
                                    byBrand[target.brand.trim().lowercase(Locale.ROOT)] ?: 0)
                            }.sortedWith(compareByDescending<DashboardBrandKpi> { it.scans }
                                .thenBy { it.brand.lowercase(Locale.ROOT) }).take(3)
                        }.collect { monthlyBrandKpis = it }
                    }
                }
                if (week != weekKey) {
                    weekKey = week
                    weekJob?.cancel()
                    weeklyTopDoctors = null
                    val start = weekStart.atStartOfDay(zone).toInstant().toEpochMilli()
                    val end = weekStart.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
                    weekJob = launch {
                        prescriptionDao.observeDashboardTopDoctors(currentUid, start, end)
                            .collect { weeklyTopDoctors = it }
                    }
                }
                // Rebind at local week/month or time-zone boundaries, even without DB writes.
                delay(30_000)
            }
        }
        viewModelScope.launch {
            // Loads the district/upazila/territory cascade and the geo centroids.
            locationRepository.locations()
            locationRepository.geo()
        }
        viewModelScope.launch {
            // Separate launch: `observe()` never completes, so collecting it in the
            // same coroutine would stop the location cascade from ever being reached.
            app.graph.profileDao.observe(currentUid).collect { officerProfile = it }
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

    /** Fetch a fresh server-approved company before offering explicit assignment. */
    fun prepareClaim() {
        if (claimBusy) return
        claimBusy = true
        claimMessage = null
        viewModelScope.launch {
            try {
                check(FirebaseAuth.getInstance().currentUser?.uid == currentUid)
                val company = TrustedMembership.refresh(getApplication<Application>(), currentUid)
                if (company == null) {
                    claimMessage = "Your company membership must be approved by an administrator."
                } else {
                    claimIds = prescriptionDao.claimableAudits(currentUid).map { it.id }.toSet()
                    claimCount = claimIds.size
                    if (claimIds.isEmpty()) claimMessage = "No unassigned scans remain."
                    else claimCompany = company
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                claimMessage = "Connect to the internet to verify your company before assigning scans."
            } finally {
                claimBusy = false
            }
        }
    }

    fun cancelClaim() { claimCompany = null; claimIds = emptySet(); claimCount = 0 }

    fun confirmClaim() {
        val chosen = claimCompany ?: return
        if (claimBusy) return
        claimBusy = true
        claimMessage = null
        viewModelScope.launch {
            try {
                check(FirebaseAuth.getInstance().currentUser?.uid == currentUid)
                val verified = TrustedMembership.refresh(getApplication<Application>(), currentUid)
                check(verified == chosen) { "Your company membership changed. Verify again before assigning scans." }
                val assigned = app.graph.database.withTransaction {
                    check(FirebaseAuth.getInstance().currentUser?.uid == currentUid)
                    prescriptionDao.claimableAudits(currentUid).filter { it.id in claimIds }.sumOf { rx ->
                        prescriptionDao.assignAudit(currentUid, rx.id, chosen, java.util.UUID.randomUUID().toString())
                    }
                }
                cancelClaim()
                if (assigned > 0) {
                    val scheduled = runCatching { UploadScheduling.start(getApplication<Application>(), currentUid) }.isSuccess
                    claimMessage = if (scheduled) "$assigned scans assigned to $chosen and queued for sync."
                        else "$assigned scans assigned locally. Reopen the app to retry scheduling sync."
                } else claimMessage = "No unassigned scans remain."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                cancelClaim()
                claimMessage = e.message ?: "Could not assign scans. Check your connection and try again."
            } finally {
                claimBusy = false
            }
        }
    }

    fun districtNames() = locationRepository.districtNames()
    fun upazilasFor(district: String) = locationRepository.upazilasFor(district)
    fun territoriesFor(district: String) = locationRepository.territoriesFor(district)
    fun specialties() = locationRepository.specialties()

    /** Copies the picked image into app storage so the path stays valid after the picker closes. */
    fun onImagePicked(uri: Uri) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = UserCaptureFiles.stored(getApplication(), uid).apply { check(mkdirs() || isDirectory) }
                    val target = File(dir, "rx_${java.util.UUID.randomUUID()}.jpg")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    target.takeIf { it.exists() && it.length() > 0 }
                }.getOrNull()
            }
            if (FirebaseAuth.getInstance().currentUser?.uid != uid) {
                return@launch
            }
            if (file == null) {
                state = state.copy(phase = ScanPhase.Failed, error = "Could not read that image")
                return@launch
            }
            val bitmap = withContext(Dispatchers.IO) { decodeOriented(file) }
            if (FirebaseAuth.getInstance().currentUser?.uid != uid || uid != currentUid) return@launch
            if (bitmap == null) {
                state = state.copy(phase = ScanPhase.Failed, error = "Could not decode that image")
                return@launch
            }
            state = state.copy(
                phase = ScanPhase.Ready,
                capturedBitmap = bitmap,
                imageUri = Uri.fromFile(file).toString(),
                imageFile = file,
                error = null,
                result = null,
                progress = 0f,
                progressText = "",
            )
            locateLines(bitmap)
        }
    }

    /** Persist the oriented, full-resolution capture for review and offline replay. */
    fun onCameraCaptured(bitmap: Bitmap, expectedUid: String) {
        if (expectedUid != currentUid || FirebaseAuth.getInstance().currentUser?.uid != expectedUid || state.phase != ScanPhase.Empty) return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = UserCaptureFiles.stored(getApplication(), expectedUid).apply { check(mkdirs() || isDirectory) }
                    File(dir, "rx_${java.util.UUID.randomUUID()}.jpg").apply {
                        outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it)) }
                    }
                }.getOrNull()
            }
            if (FirebaseAuth.getInstance().currentUser?.uid != expectedUid || state.phase != ScanPhase.Empty) return@launch
            if (file == null) {
                state = state.copy(phase = ScanPhase.Failed, error = "Could not save the camera image")
            } else {
                state = state.copy(phase = ScanPhase.Ready, imageUri = Uri.fromFile(file).toString(), imageFile = file,
                    error = null, result = null, capturedBitmap = bitmap, progress = 0f, progressText = "")
                locateLines(bitmap)
            }
        }
    }

    private fun decodeOriented(file: File): Bitmap? = runCatching {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val exif = ExifInterface(file.absolutePath)
        val matrix = Matrix().apply {
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> postScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { postRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { postRotate(270f); postScale(-1f, 1f) }
            }
        }
        if (matrix.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            .also { if (it !== bitmap) bitmap.recycle() }
    }.getOrNull()

    private fun locateLines(bitmap: Bitmap) {
        locateJob?.cancel()
        state = state.copy(locating = true, locatorError = null, detectedLines = emptyList(), selectedLines = emptySet())
        locateJob = viewModelScope.launch {
            try {
                val boxes = locator.locate(bitmap)
                if (state.capturedBitmap !== bitmap || FirebaseAuth.getInstance().currentUser?.uid != currentUid) return@launch
                state = state.copy(locating = false, detectedLines = boxes, selectedLines = boxes.indices.toSet(),
                    locatorError = if (boxes.isEmpty()) "No lines detected. Retake the photo or try another image." else null)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (state.capturedBitmap === bitmap) state = state.copy(locating = false, locatorError = "Could not detect lines. Try again.")
            }
        }
    }

    fun toggleDetectedLine(index: Int) {
        if (index !in state.detectedLines.indices) return
        state = state.copy(selectedLines = if (index in state.selectedLines) state.selectedLines - index else state.selectedLines + index)
    }

    fun retryLocator() { state.capturedBitmap?.let(::locateLines) }

    /** Send the full image and confirmed medicine-line crops to Gemini. */
    fun analyze() {
        val file = state.imageFile ?: return
        val bitmap = state.capturedBitmap ?: return
        val boxes = state.detectedLines.filterIndexed { index, _ -> index in state.selectedLines }.map { Rect(it) }
        if (state.phase != ScanPhase.Ready || scanJob?.isActive == true || boxes.isEmpty() || state.locating || FirebaseAuth.getInstance().currentUser?.uid != currentUid) return

        scanJob = viewModelScope.launch {
            val outcome: ScanProgress = if (!deviceState.online) {
                ScanProgress.QueuedOffline
            } else {
                state = state.copy(
                    phase = ScanPhase.Scanning,
                    progress = 0f,
                    progressIndeterminate = true,
                    progressText = "Preparing image…",
                    revealedMedicines = 0,
                    result = null,
                    cards = emptyList(),
                    error = null,
                    parked = false,
                )
                state = state.copy(progressText = "Waiting for AI response…")
                scanRepository.scan(bitmap, boxes)
            }
            if (FirebaseAuth.getInstance().currentUser?.uid != currentUid || state.phase != ScanPhase.Scanning && outcome !is ScanProgress.QueuedOffline) return@launch
            when (outcome) {
                is ScanProgress.Done -> try {
                    enterVerification(outcome.result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    state = state.copy(phase = ScanPhase.Failed, error = e.message ?: "Could not prepare the scan for review")
                }
                is ScanProgress.Failed ->
                    if (!deviceState.online) parkForReplay(file)
                    else state = state.copy(phase = ScanPhase.Ready, error = outcome.message)
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
     * keeps the viewer, the GPS strip and the confirmation button for retry.
     * Reconnecting reopens queued scans for review, never sending them automatically.
     */
    private suspend fun parkForReplay(file: File) {
        val parked = try {
            val boxes = state.detectedLines.filterIndexed { index, _ -> index in state.selectedLines }
            val payload = "lines-v1:" + org.json.JSONArray().apply { boxes.forEach { box ->
                put(org.json.JSONArray().put(box.left).put(box.top).put(box.right).put(box.bottom))
            } }.toString()
            val queuedId = state.queuedId
            if (queuedId == null) {
                val id = scanRepository.queueForLater(currentUid, file.absolutePath, payload)
                state = state.copy(queuedId = id)
            } else {
                scanRepository.updateQueuedSelection(currentUid, queuedId, payload)
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        state = state.copy(
            phase = ScanPhase.Ready,
            parked = parked,
            progress = 0f,
            progressText = "",
            error = if (parked) null else "Offline, and the capture could not be queued",
        )
    }

    /**
     * Opens the oldest parked capture for line confirmation after reconnecting.
     *
     * Only ever runs from [ScanPhase.Empty] - genuinely idle, with no capture in hand.
     * `Ready` is deliberately excluded even when nothing is parked: at that point the
     * officer has picked an image and is about to analyse it, and a connectivity blip
     * must not swap the viewer to a different prescription underneath them. Rows that
     * cannot be read remain queued rather than being discarded.
     */
    fun syncQueuedScans() {
        if (!deviceState.online) return
        if (state.phase != ScanPhase.Empty) return
        viewModelScope.launch {
            scanRepository.drainQueue(currentUid) { row -> replayQueued(row) }
        }
    }

    /** Reopen parked images for explicit confirmation, including pre-locator legacy rows. */
    private suspend fun replayQueued(row: QueuedScanEntity): Boolean {
        val file = File(row.imagePath)
        val bitmap = withContext(Dispatchers.IO) { decodeOriented(file) } ?: return false
        if (state.phase != ScanPhase.Empty || FirebaseAuth.getInstance().currentUser?.uid != currentUid) return false
        state = state.copy(phase = ScanPhase.Ready, imageUri = Uri.fromFile(file).toString(), imageFile = file,
            capturedBitmap = bitmap, queuedId = row.id, parked = true, error = null)
        val restored = runCatching {
            if (!row.payload.startsWith("lines-v1:")) return@runCatching emptyList<Rect>()
            val array = org.json.JSONArray(row.payload.removePrefix("lines-v1:"))
            (0 until array.length()).map { i -> array.getJSONArray(i).let { Rect(it.getInt(0), it.getInt(1), it.getInt(2), it.getInt(3)) } }
                .filter { it.left >= 0 && it.top >= 0 && it.right <= bitmap.width && it.bottom <= bitmap.height && !it.isEmpty }
        }.getOrDefault(emptyList())
        if (restored.isEmpty()) locateLines(bitmap) else state = state.copy(detectedLines = restored, selectedLines = restored.indices.toSet())
        return false // keep queue row until a verified save succeeds
    }

    /**
     * Seeds the verification panels from a completed read.
     *
     * The VL result is kept verbatim in [ScanUiState.result] for provenance; the doctor
     * and medicine copies here are the editable ones the panels bind to.
     */
    private suspend fun enterVerification(result: VlScanResult) {
        val d = result.doctor
        val animated = state.phase == ScanPhase.Scanning
        if (animated) {
            // The provider returns one complete JSON response, not a stream of
            // detections. Only now can real doctor data appear in the skeleton.
            state = state.copy(result = result, progressIndeterminate = false,
                progress = 0.5f, progressText = "Cross-referencing drug database…")
        }
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
            // Resolved, not raw: with a blank own company the enrichment computes no
            // substitution pick at all, and the web's resolver never returns blank.
            ownCompany = MedicineMatcher.resolveOwnCompany(officerProfile?.company).name,
        )
        val medicineCards = enrichedMedicines.map { it.toCardData() }
        if (animated) {
            state = state.copy(cards = medicineCards, progress = 0.85f,
                progressText = "Preparing results for review…")
            // Brief reveal of real rows only; bound the delay for long lists.
            val count = medicineCards.size
            val interval = if (count == 0) 0L else (400L / count).coerceIn(5L, 65L)
            for (i in 1..count) {
                delay(interval)
                state = state.copy(revealedMedicines = i, progress = 0.85f + 0.14f * i / count)
            }
            state = state.copy(progress = 1f, progressText = "Read complete")
            delay(120)
        }
        state = state.copy(
            phase = ScanPhase.VerifyDoctor,
            progress = 1f,
            progressText = "Read complete",
            result = result,
            doctor = DoctorVerification(
                prescriptionDate = result.prescriptionDate,
                name = d.name,
                nameConfidence = 0,
                bmdcNo = d.bmdcNo,
                bmdcConfidence = 0,
                qualifications = d.qualifications,
                qualificationsConfidence = 0,
                hospitalChamber = d.hospital.ifBlank { d.chamber },
                hospitalConfidence = 0,
                specialty = d.specialty,
                district = d.district.ifBlank { state.geo.district },
                upazila = d.upazila.ifBlank { state.geo.upazila },
                territory = d.territory.ifBlank { state.geo.territory },
            ),
            cards = medicineCards,
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
                // The same rule for the fields a catalogue pick rewrites. Without
                // these four lines every correction made through "N other matches for
                // this brand" - and, before it, through the name autocomplete and
                // "verify against MedEx" - stopped at the card and was thrown away on
                // save. Folding them back is what makes the officer's pick survive the
                // re-enrichment below, because `pickVariant` re-resolves on
                // brand + strength + dosage form and so lands on the variant that was
                // chosen; the pack photo follows from that same re-resolution and
                // needs no line of its own.
                strength = if (card.strength != shown.strength) card.strength else med.strength,
                type = if (card.type != shown.type) card.type else med.type,
                company = if (card.company != shown.company) card.company else med.company,
                genericName = if (card.ingredient != shown.ingredient) {
                    card.ingredient
                } else {
                    med.genericName
                },
            )
        }
        return result.copy(medicines = medicines)
    }

    fun onBrandChange(index: Int, brand: String) {
        updateCard(index) {
            if (it.brand == brand) it else it.copy(
                brand = brand, ingredient = "", company = "", strength = "", type = "",
                companyVerification = CompanyVerification.None, packImage = null, medexProductId = null,
                alternatives = emptyList(), catalogueNote = null, matchType = null, substitution = null,
            )
        }
        refreshSubstitution(index)
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
                medexProductId = product.id.takeIf { id -> id.isNotBlank() },
                brand = product.brandName,
                ingredient = product.generic.ifBlank { product.ingredient },
                company = product.company,
                strength = product.strength.ifBlank { it.strength },
                type = product.form.ifBlank { product.type }.ifBlank { it.type },
                packImage = product.packImage?.takeIf { url -> url.isNotBlank() }
                    ?: product.imageUrl,
            )
        }
        updateCard(index) { it.copy(substitution = null) }
        refreshSubstitution(index)
        suggestionJob?.cancel()
        state = state.copy(brandSuggestions = emptyList())
    }

    /**
     * Applies one of the other catalogue variants of the same brand.
     *
     * The brand was never in question; the strength, the dosage form and sometimes the
     * manufacturer were. The web calls this "N other matches for this brand"
     * (`index.html:1909`) and installs it on any brand with more than one catalogue
     * entry, matching what `MedicineEnricher` puts in
     * [MedicineCardData.alternatives].
     *
     * The selected catalogue ID is carried through the review card and re-enrichment,
     * so two variants with identical strength and form cannot override the user's pick.
     *
     * The `alternatives` list itself is deliberately left alone. The web's array is
     * whatever the last enrichment computed, so the variant just chosen stays listed
     * and can be swapped back; recomputing here would be a divergence, not a fix.
     */
    fun applyAlternative(index: Int, product: MedexProduct) {
        updateCard(index) {
            it.copy(
                medexProductId = product.id.takeIf { id -> id.isNotBlank() },
                // The brand is not touched, because the web's handler does not touch it
                // either (`index.html:3312`): every entry in this list is a variant *of
                // the brand already on the card*, so rewriting the name could only
                // replace the officer's own text with a catalogue spelling of the same
                // thing.
                //
                // `company` is replaced rather than merged, again as the web does
                // (`med.company = btn.dataset.company || ''`). A variant the officer
                // picked is the authority on its own manufacturer, and a blank one means
                // this row has none - not that the previous row's name should stand in.
                // The badge follows the new value, so a blank company reads as
                // "Company not identified" rather than as a verified empty one.
                company = product.company,
                companyVerification = if (product.company.isNotBlank()) {
                    CompanyVerification.Verified
                } else {
                    CompanyVerification.None
                },
                ingredient = product.generic.ifBlank { product.ingredient },
                strength = product.strength.ifBlank { it.strength },
                // The web reads the catalogue's `type` first (`data-type` on the button),
                // so that field leads here too rather than `form`.
                type = product.type.ifBlank { product.form }.ifBlank { it.type },
                // Only overwritten when the chosen variant actually has a photo, which
                // is the web's `if(btn.dataset.image)`. Assigning unconditionally would
                // blank the pack shot for every variant the catalogue has no image for.
                packImage = (product.packImage?.takeIf { url -> url.isNotBlank() }
                    ?: product.imageUrl?.takeIf { url -> url.isNotBlank() })
                    ?: it.packImage,
                // A hand-picked variant is no longer a guess, and the catalogue-note
                // strip under it would now be describing a product the officer just
                // rejected by hand. The web clears `company_conflict` for the same
                // reason.
                catalogueNote = null,
            )
        }
        updateCard(index) { it.copy(substitution = null) }
        refreshSubstitution(index)
        suggestionJob?.cancel()
        state = state.copy(brandSuggestions = emptyList())
    }

    /**
     * Re-runs the MedEx lookup for one card and applies whatever the catalogue says.
     *
     * A card's company comes from the catalogue resolution done at read time, but the
     * officer retypes the brand afterwards and a retyped brand was never re-resolved.
     * Correct "Napa" into "Napa Extra" and the manufacturer stayed whatever the first
     * pass had decided, because nothing looked at the field again. This makes the
     * re-check explicit instead of something that happens exactly once, at scan time.
     */
    fun verifyAgainstMedex(index: Int) {
        val card = state.cards.getOrNull(index) ?: return
        val brand = card.brand.trim()
        if (brand.isBlank()) {
            showToast("Enter a brand name before checking MedEx.")
            return
        }
        viewModelScope.launch {
            val catalogue = runCatching { scanRepository.medexIndex() }.getOrNull()
            if (catalogue == null) {
                showToast("Catalogue is still loading - try again in a moment.")
                return@launch
            }
            val outcome = runCatching {
                val m = catalogue.match(brand, card.strength, card.type)
                m to catalogue.pickVariant(m.variants, card.strength, card.type)
            }.getOrNull()
            // Bound to locals rather than relying on a smart cast that has to hold
            // across the lambda below: a lookup that throws and one that finds
            // nothing are the same thing to the officer, so they report the same way.
            val match = outcome?.first
            val pick = outcome?.second
            if (match == null || pick == null) {
                showToast("No MedEx match for \"$brand\".")
                return@launch
            }
            if (state.cards.getOrNull(index)?.brand?.trim() != brand) return@launch
            updateCard(index) {
                it.copy(
                    medexProductId = pick.id.takeIf { id -> id.isNotBlank() },
                    brand = pick.brandName,
                    ingredient = pick.generic.ifBlank { pick.ingredient },
                    company = pick.company,
                    strength = pick.strength.ifBlank { it.strength },
                    type = pick.form.ifBlank { pick.type }.ifBlank { it.type },
                    packImage = pick.packImage?.takeIf { url -> url.isNotBlank() }
                        ?: pick.imageUrl,
                    companyVerification = if (pick.company.isBlank()) {
                        CompanyVerification.None
                    } else {
                        CompanyVerification.Verified
                    },
                    matchType = if (match.exact) {
                        MatchType.Exact.label
                    } else {
                        MatchType.Fuzzy.label
                    },
                    // Only worth a note when the catalogue resolved the typed brand to
                    // a different product; otherwise it is noise on every card.
                    catalogueNote = if (!pick.brandName.equals(brand, ignoreCase = true)) {
                        "Re-checked against MedEx - \"$brand\" resolved to ${pick.brandName}."
                    } else {
                        null
                    },
                )
            }
            updateCard(index) { it.copy(substitution = null) }
            refreshSubstitution(index)
            showToast(
                if (pick.company.isBlank()) "Matched ${pick.brandName}."
                else "Matched ${pick.brandName} - ${pick.company}.",
            )
        }
    }

    /**
     * Queues a misread for the retraining set.
     *
     * `detectedBrand` is what the model originally read and `correction` is what sits
     * in the brand field now, so the row records the difference rather than just
     * registering a complaint. It is still worth capturing when the officer has
     * changed nothing: a confidently wrong read is precisely what the pipeline needs
     * to see, and "the model was sure and still wrong" is unrecoverable from a row
     * that only stores the correction.
     *
     * The cropped image slice that `main.py` stores alongside it is not captured yet —
     * see agent/findings/2026-09-19-full-scan.md.
     */
    fun reportMisId(index: Int) {
        val card = state.cards.getOrNull(index) ?: return
        val asRead = state.result?.medicines
            ?.getOrNull(index)?.brandName
            ?.trim()
            .orEmpty()
        viewModelScope.launch {
            val row = ErrorReportEntity(
                userId = currentUid,
                detectedBrand = asRead.ifBlank { card.brand.trim() },
                correction = card.brand.trim(),
                notes = buildList {
                    card.lineRef?.takeIf { it.isNotBlank() }?.let { add(it) }
                    add("confidence ${card.confidencePct}%")
                    card.company.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString(" - "),
                raw = card.rawText.orEmpty(),
                createdAt = System.currentTimeMillis(),
            )
            val queued = runCatching { app.graph.database.rsmDao().reportError(row) }.isSuccess
            showToast(
                if (queued) "Queued for training."
                else "Could not queue the report - try again.",
            )
        }
    }

    /**
     * Records a conversion win — the "Mark as won" button on a substitution card.
     *
     * The web sends this down the *same* training-queue endpoint as a mis-read, with
     * the brand and company unchanged and only `notes` marking it out as a conversion
     * (`index.html:1786`, `queueTrainingItem(...)` with `'Marked as won conversion on
     * substitution pitch'`). So this writes the same row shape as [reportMisId] for the
     * same reason: one queue, distinguished by its note, rather than a second table
     * that would drift from the first.
     *
     * The row is therefore not a correction, and must not be read as one. `detectedBrand`
     * and `correction` are deliberately equal here — nothing was mis-read — and the note
     * is what makes the row mean "this pitch landed". That is also why the brief's
     * "flags the conversion in the MPO's weekly performance records" is not what happens:
     * the web keeps no such record, and inventing one would put a number in a report
     * that nothing else agrees with.
     */
    fun markConversionWon(index: Int) {
        val card = state.cards.getOrNull(index) ?: return
        val substitution = card.substitution ?: return
        val asRead = state.result?.medicines
            ?.getOrNull(index)?.brandName
            ?.trim()
            .orEmpty()
        viewModelScope.launch {
            val row = ErrorReportEntity(
                userId = currentUid,
                detectedBrand = asRead.ifBlank { card.brand.trim() },
                correction = card.brand.trim(),
                notes = buildList {
                    // Lead with the marker: it is the only thing separating this row
                    // from a mis-read in the same table.
                    add(CONVERSION_WON_NOTE)
                    card.lineRef?.takeIf { it.isNotBlank() }?.let { add(it) }
                    add("confidence ${card.confidencePct}%")
                    add("pitched ${substitution.ownBrand.brandName}")
                }.joinToString(" - "),
                raw = card.rawText.orEmpty(),
                createdAt = System.currentTimeMillis(),
            )
            val queued = runCatching { app.graph.database.rsmDao().reportError(row) }.isSuccess
            showToast(
                if (queued) "Logged as a conversion win - queued for learning."
                else "Could not log the conversion - try again.",
            )
        }
    }

    /**
     * Transient confirmation, kept in the ViewModel so both handlers can report
     * without threading a callback back through the screen.
     *
     * Uses the Application context rather than an Activity one, so a toast fired
     * from a coroutine cannot outlive and leak the screen that started it. Safe from
     * [viewModelScope], which dispatches on the main thread.
     */
    private fun showToast(message: String) {
        android.widget.Toast.makeText(app, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        /**
         * The `notes` prefix that marks a row in `error_reports` as a conversion win
         * rather than a mis-read. Same string the web sends (`index.html:1788`).
         */
        const val CONVERSION_WON_NOTE = "Marked as won conversion on substitution pitch"
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

    private val substitutionJobs = mutableMapOf<Int, Job>()

    /** Recompute only for an exact catalogue brand; never reuse the old generic. */
    private fun refreshSubstitution(index: Int) {
        substitutionJobs.remove(index)?.cancel()
        val card = state.cards.getOrNull(index) ?: return
        val scan = state.result ?: return
        val company = MedicineMatcher.resolveOwnCompany(officerProfile?.company).name
        substitutionJobs[index] = viewModelScope.launch {
            val resolved = try { withContext(Dispatchers.Default) {
                val catalogue = scanRepository.medexIndex()
                val variants = catalogue.exact(card.brand.trim())
                val product = catalogue.pickVariant(variants, card.strength, card.type)
                if (product == null) null else product to Intelligence.genericSubstitution(
                    data = app.graph.regulatoryRepository.data(),
                    detectedBrand = product.brandName,
                    detectedCompany = card.company.ifBlank { product.company },
                    detectedGeneric = card.ingredient.ifBlank { product.generic.ifBlank { product.ingredient } },
                    detectedStrength = card.strength.ifBlank { product.strength },
                    detectedType = card.type.ifBlank { product.type.ifBlank { product.form } },
                    detectedImageUrl = card.packImage ?: product.packImage ?: product.imageUrl,
                    ownCompany = company,
                    medex = catalogue,
                )
            } } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            val current = state.cards.getOrNull(index)
            if (state.result === scan && current != null && current.brand == card.brand &&
                current.ingredient == card.ingredient && current.company == card.company &&
                current.strength == card.strength && current.type == card.type
            ) updateCard(index) {
                if (resolved == null) it.copy(substitution = null)
                else if (it.ingredient.isNotBlank() || it.company.isNotBlank()) {
                    it.copy(substitution = resolved.second)
                } else {
                    val product = resolved.first
                    it.copy(
                        medexProductId = product.id.takeIf { id -> id.isNotBlank() },
                        ingredient = product.generic.ifBlank { product.ingredient },
                        company = product.company,
                        companyVerification = if (product.company.isBlank()) CompanyVerification.None else CompanyVerification.Verified,
                        strength = product.strength, type = product.type.ifBlank { product.form },
                        packImage = product.packImage ?: product.imageUrl,
                        substitution = resolved.second,
                    )
                }
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
                // Do not persist a selected ID alongside stale enrichment if re-matching fails.
                val audit = MedicineEnricher.enrich(
                        medicines = corrected.medicines,
                        index = scanRepository.medexIndex(),
                        regulatory = app.graph.regulatoryRepository.data(),
                        ownCompany = MedicineMatcher.resolveOwnCompany(profile?.company).name,
                        preferredIds = cards.map { it.medexProductId },
                    )
                state = state.copy(enriched = audit)
                // Null when the image cannot be decoded. The web skips the guard for
                // a missing hash rather than guessing, and so does this.
                val hash = imageFile?.let { file ->
                    withContext(Dispatchers.IO) { PHash.compute(file) }
                        .takeIf { it.isNotBlank() }
                }
                val match = hash?.let { findDuplicate(it) }
                val repId = profile?.employeeId?.takeIf { it.isNotBlank() } ?: DEFAULT_MR_ID
                val savedPrescriptionId = scanRepository.saveVerified(
                    prescription = PrescriptionEntity(
                        rxNo = "", // assigned from the auto-incremented ID in the save transaction
                        imagePath = imageFile?.absolutePath.orEmpty(),
                        imageHash = hash,
                        prescriptionDate = doctor.prescriptionDate.trim().takeIf { it.isNotEmpty() },
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
                    ownCompany = MedicineMatcher.resolveOwnCompany(profile?.company).name,
                    expectedUid = currentUid,
                    enriched = audit,
                )
                // Keep the queue until the verified transaction succeeds. If removal
                // fails, retain the row rather than misreporting the committed save.
                val queuedId = state.queuedId
                val removedQueue = if (queuedId == null) true else if (FirebaseAuth.getInstance().currentUser?.uid == currentUid) {
                    try { scanRepository.removeQueued(currentUid, queuedId); true }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { false }
                } else false
                val rxNo = "RX-$savedPrescriptionId"
                duplicateOfRxIds = match?.let { listOf(it.rxNo) } ?: emptyList()
                state = state.copy(
                    phase = ScanPhase.Saved,
                    queuedId = if (removedQueue) null else queuedId,
                    parked = !removedQueue,
                    savedPrescriptionId = savedPrescriptionId,
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
        for (row in prescriptionDao.hashRows(currentUid)) {
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
    fun clearDuplicateAlert() { duplicateOfRxIds = emptyList() }

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

    override fun onCleared() {
        locateJob?.cancel()
        locator.close()
        targetPrefs.unregisterOnSharedPreferenceChangeListener(targetListener)
        super.onCleared()
    }

    fun clear() {
        scanJob?.cancel()
        locateJob?.cancel()
        scanJob = null
        substitutionJobs.values.forEach { it.cancel() }
        substitutionJobs.clear()
        val wasReviewingQueue = state.queuedId != null
        state = ScanUiState()
        duplicateOfRxIds = emptyList()
        // Nothing re-emits on `snapshotFlow { online }` unless the network actually
        // changes, so finishing a scan is the other moment to offer up the next
        // parked capture. One at a time: it still has to pass verification.
        if (!wasReviewingQueue) syncQueuedScans()
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
