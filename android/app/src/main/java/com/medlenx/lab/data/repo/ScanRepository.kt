package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.local.MedexDao
import com.medlenx.lab.data.local.PrescriptionDao
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.QueueDao
import com.medlenx.lab.data.local.QueuedScanEntity
import com.medlenx.lab.data.local.ScannedMedicineEntity
import com.medlenx.lab.data.model.EnrichedMedicine
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
    private val catalogue: AssetCatalogue,
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
     *
     * @param enriched index-aligned with [VlScanResult.medicines] - the output of
     *   [com.medlenx.lab.data.repo.MedicineEnricher.enrich] for this read. It carries the
     *   catalogue match and the compliance verdicts; without it every audit column would
     *   be written as a blank/false, which silently empties the market-share widget
     *   (`company_name != ''`) and mislabels the Live Scans feed. Empty is tolerated so
     *   the row still lands rather than the save failing.
     */
    suspend fun saveVerified(
        prescription: PrescriptionEntity,
        result: VlScanResult,
        mrId: String,
        ownCompany: String?,
        enriched: List<EnrichedMedicine> = emptyList(),
    ): Long {
        val id = prescriptionDao.insert(
            prescription.copy(
                totalMedicines = result.medicines.size,
                mrId = mrId,
                createdAt = prescription.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            ),
        )
        val rows = result.medicines.mapIndexed { index, med ->
            val audit = enriched.getOrNull(index)
            // The catalogue-resolved manufacturer wins. The VL is told never to guess a
            // company, so `med.company` is blank in almost every read, and the analytics
            // queries all filter on a non-empty company_name.
            val company = audit?.company?.takeIf { it.isNotBlank() } ?: med.company
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
                companyName = company.takeIf { it.isNotBlank() },
                companyVerified = audit?.companyVerified ?: false,
                isOwn = ownCompany != null && company.equals(ownCompany, ignoreCase = true),
                confidenceScore = med.confidence,
                imageUrl = audit?.imageUrl,
                matchType = audit?.matchType?.label ?: "pending",
                isAntibiotic = audit?.isAntibiotic ?: false,
                broadSpectrum = audit?.broadSpectrum ?: false,
                therapeuticClass = audit?.therapeuticClass,
                nemlListed = audit?.neml?.listed ?: false,
                dgdaFlagged = audit?.dgdaAlert?.flagged ?: false,
                tripsWatch = audit?.tripsWatch?.watch ?: false,
            )
        }
        if (rows.isNotEmpty()) prescriptionDao.insertMedicines(rows)
        return id
    }

    /**
     * Replays parked captures, oldest first, and stops at the first one that reads.
     *
     * A resumed read still has to pass through the officer's verification panels before it
     * can be saved, so only one can be handed back at a time - the rest stay parked and the
     * header badge keeps counting them. A row that fails stays queued with `attempts`
     * incremented; past [MAX_QUEUE_ATTEMPTS] it is dropped, so one corrupt capture cannot
     * pin the badge open forever.
     *
     * @return how many rows were drained - 0 or 1.
     */
    suspend fun drainQueue(handler: suspend (QueuedScanEntity) -> Boolean): Int {
        var drained = 0
        // Snapshot the queue once, then process: observeAll() is a Flow and must not
        // be collected while entries are being removed from it.
        val pending = queueDao.observeAll().first()
        for (row in pending) {
            if (row.attempts >= MAX_QUEUE_ATTEMPTS) {
                queueDao.remove(row)
                continue
            }
            queueDao.markAttempt(row.id)
            if (handler(row)) {
                queueDao.remove(row)
                drained++
                break
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

    /**
     * The in-memory brand index, guaranteed to reflect the imported catalogue.
     *
     * Two rules matter. It awaits [AssetCatalogue.ensureImported], because the import
     * runs on the graph's IO scope at process start and takes seconds — a caller that
     * reads Room without waiting sees an empty table. And it refuses to cache an empty
     * index, because a cached empty index is indistinguishable from a genuinely empty
     * catalogue for the rest of the process, which is what made brand suggestions and
     * pack images silently never appear.
     */
    suspend fun medexIndex(): MedexIndex {
        cachedIndex?.let { return it }
        catalogue.ensureImported()
        return withContext(Dispatchers.IO) {
            val rows = medexDao.all()
            val products = if (rows.isNotEmpty()) {
                rows.map { it.toProduct() }
            } else {
                // Room is empty even after the import attempt: fall back to the
                // bundled JSON so suggestions and pack images still resolve offline.
                catalogue.readMedexAsset()
            }
            MedexIndex(products)
        }.also { if (it.all.isNotEmpty()) cachedIndex = it }
    }

    private companion object {
        /** Retries before a parked capture is abandoned. */
        const val MAX_QUEUE_ATTEMPTS = 3
    }
}
