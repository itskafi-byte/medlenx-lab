package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.MedexDao
import com.medlenx.lab.data.local.PrescriptionDao
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.QueueDao
import com.medlenx.lab.data.local.QueuedScanEntity
import com.medlenx.lab.data.local.ScannedMedicineEntity
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.remote.MedLenXVlClient
import com.medlenx.lab.data.remote.VlOutcome
import com.medlenx.lab.util.Bengali
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Progress reported to the scan screen while a capture is in flight. */
sealed class ScanProgress {
    data object Uploading : ScanProgress()
    data object Reading : ScanProgress()
    data class Done(val result: VlScanResult, val rawContent: String) : ScanProgress()
    data class Failed(val message: String) : ScanProgress()
    data object QueuedOffline : ScanProgress()

    /** No OpenRouter key configured — the app runs in demo mode like the web build. */
    data object NeedsApiKey : ScanProgress()
}

/**
 * Owns the capture -> read -> persist path.
 *
 * Step 2 wires transport and persistence. Catalogue resolution (medicine_matcher.py),
 * compliance signals (compliance.py) and duplicate-Rx fingerprinting (rx_audit.py)
 * are layered on in Steps 4 and 6 — the enrichment hooks below are deliberately left
 * as pass-throughs rather than stubbed with fake logic.
 */
class ScanRepository(
    private val vlClient: MedLenXVlClient,
    private val prescriptionDao: PrescriptionDao,
    private val queueDao: QueueDao,
    private val medexDao: MedexDao,
) {

    val queued: Flow<List<QueuedScanEntity>> get() = queueDao.observeAll()

    suspend fun scan(imageBytes: ByteArray, mimeType: String = "image/jpeg"): ScanProgress {
        if (!vlClient.hasKey) return ScanProgress.NeedsApiKey
        return when (val outcome = vlClient.scanPrescription(imageBytes, mimeType)) {
            is VlOutcome.Success -> ScanProgress.Done(outcome.result, outcome.rawContent)
            is VlOutcome.Failure -> ScanProgress.Failed(outcome.message)
            VlOutcome.NoKey -> ScanProgress.NeedsApiKey
        }
    }

    /** Parks a capture for replay when connectivity returns. */
    suspend fun queueForLater(imagePath: String, payload: String): Long =
        queueDao.enqueue(
            QueuedScanEntity(
                imagePath = imagePath,
                payload = payload,
                createdAt = System.currentTimeMillis(),
            ),
        )

    /**
     * Persists a verified prescription with its itemised medicines.
     *
     * Re-verifying replaces the medicine rows instead of appending, matching the web
     * backend's append-only-but-replace-per-prescription behaviour.
     */
    suspend fun saveVerified(
        prescription: PrescriptionEntity,
        result: VlScanResult,
        mrId: String,
        ownCompany: String?,
    ): Long {
        val id = prescriptionDao.insert(
            prescription.copy(
                totalMedicines = result.medicines.size,
                mrId = mrId,
                createdAt = prescription.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            ),
        )
        val rows = result.medicines.mapIndexed { index, med ->
            val dosage = med.dosageNormalized.ifBlank { Bengali.normalizeDosage(med.dosage) }
            ScannedMedicineEntity(
                prescriptionId = id,
                lineNumber = index + 1,
                brandName = med.brandName,
                generic = med.genericName,
                strength = med.strength,
                dosageForm = med.type.ifBlank { med.form },
                dosage = dosage,
                raw = med.rawText,
                companyName = med.company.takeIf { it.isNotBlank() },
                companyVerified = false,
                isOwn = ownCompany != null && med.company.equals(ownCompany, ignoreCase = true),
                confidenceScore = med.confidence,
                imageUrl = null,
                matchType = "pending",
                isAntibiotic = false,
                broadSpectrum = false,
                therapeuticClass = null,
                nemlListed = false,
                dgdaFlagged = false,
                tripsWatch = false,
            )
        }
        if (rows.isNotEmpty()) prescriptionDao.insertMedicines(rows)
        return id
    }

    /** Replays queued captures. Returns how many were drained. */
    suspend fun drainQueue(handler: suspend (QueuedScanEntity) -> Boolean): Int {
        var drained = 0
        // Snapshot the queue once, then process: observeAll() is a Flow and must not
        // be collected while entries are being removed from it.
        val pending = queueDao.observeAll().first()
        pending.forEach { row ->
            queueDao.markAttempt(row.id)
            if (handler(row)) {
                queueDao.remove(row)
                drained++
            }
        }
        return drained
    }

    suspend fun recentMedicines(limit: Int, offset: Int) =
        prescriptionDao.recentMedicines(limit, offset)

    suspend fun medicinesFor(prescriptionId: Long) =
        prescriptionDao.medicinesFor(prescriptionId)

    fun recentPrescriptions(limit: Int = 10) = prescriptionDao.observeRecent(limit)

    suspend fun searchCatalogue(query: String, limit: Int = 24) = medexDao.search(query, limit)

    /**
     * The MedEx matcher index, built once and cached for the process lifetime.
     *
     * This is the hook the class KDoc referred to as "layered on later" - the matcher
     * now exists, so the pass-through is replaced with a real index.
     */
    @Volatile
    private var cachedIndex: MedexIndex? = null

    suspend fun medexIndex(): MedexIndex =
        cachedIndex ?: withContext(Dispatchers.IO) {
            MedexIndex(medexDao.all().map { it.toProduct() }).also { cachedIndex = it }
        }
}
