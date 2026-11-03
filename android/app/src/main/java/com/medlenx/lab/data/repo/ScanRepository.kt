package com.medlenx.lab.data.repo

import com.google.firebase.auth.FirebaseAuth
import android.content.Context
import com.medlenx.lab.data.sync.TrustedMembership
import com.medlenx.lab.data.sync.UploadScheduling
import androidx.room.withTransaction
import com.medlenx.lab.data.local.MedLenXDatabase
import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.local.DoctorDao
import com.medlenx.lab.data.local.DoctorEntity
import com.medlenx.lab.data.local.DoctorIdentity
import com.medlenx.lab.data.local.MedexDao
import com.medlenx.lab.data.local.PrescriptionDao
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.QueueDao
import com.medlenx.lab.data.local.QueuedScanEntity
import com.medlenx.lab.data.local.ScannedMedicineEntity
import com.medlenx.lab.data.local.UserCaptureFiles
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.VlScanResult
import com.medlenx.lab.data.remote.MedLenXVlClient
import com.medlenx.lab.data.remote.VlOutcome
import com.medlenx.lab.util.Bengali
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val context: Context,
    private val database: MedLenXDatabase,
    private val vlClient: MedLenXVlClient,
    private val prescriptionDao: PrescriptionDao,
    private val doctorDao: DoctorDao,
    private val queueDao: QueueDao,
    private val medexDao: MedexDao,
    private val catalogue: AssetCatalogue,
) {

    suspend fun scan(imageBytes: ByteArray, mimeType: String = "image/jpeg"): ScanProgress {
        if (!vlClient.hasKey) return ScanProgress.NeedsApiKey
        return when (val outcome = vlClient.scanPrescription(imageBytes, mimeType)) {
            is VlOutcome.Success -> ScanProgress.Done(outcome.result, outcome.rawContent)
            is VlOutcome.Failure -> ScanProgress.Failed(outcome.message)
            VlOutcome.NoKey -> ScanProgress.NeedsApiKey
        }
    }

    /** Parks a capture for replay when connectivity returns. */
    suspend fun queueForLater(currentUid: String, imagePath: String, payload: String): Long {
        check(currentUid.isNotBlank() && FirebaseAuth.getInstance().currentUser?.uid == currentUid) {
            "Sign in before queuing a capture"
        }
        return queueDao.enqueue(
            QueuedScanEntity(
                userId = currentUid,
                imagePath = imagePath,
                payload = payload,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * The [DoctorEntity] id this prescription belongs to, creating it if needed.
     *
     * Keyed by [DoctorIdentity], which is the same rule the v1->v2 migration
     * backfills with. Current user identities are namespaced separately, so
     * pre-isolation doctor rows remain unassigned rather than being claimed.
     *
     * Null when the prescription carries nothing to identify a doctor by. The
     * column is nullable and the drill-down groups the nulls together, which is
     * what the web's own null `doctor_id` does.
     *
     * The read-then-insert is not atomic and does not need to be: the scoped
     * `identity_key` is UNIQUE, so a racing insert is ignored rather than duplicated, and the
     * fallback read then finds whichever one landed.
     */
    private suspend fun resolveDoctorId(prescription: PrescriptionEntity, uid: String): Long? {
        val identity = DoctorIdentity.keyOf(prescription) ?: return null
        // Existing unique index stays valid: namespacing the key isolates doctor
        // identities while leaving legacy identities untouched and recoverable.
        val key = "u:${UserCaptureFiles.key(uid)}:$identity"

        val incoming = DoctorEntity(
            userId = uid,
            identityKey = key,
            name = DoctorIdentity.displayName(prescription.doctorName),
            specialty = prescription.doctorSpecialty,
            chamber = prescription.chamber,
            district = prescription.district,
            territory = prescription.territory,
            upazila = prescription.upazila,
            bmdcNo = prescription.doctorBmdcNo,
        )

        val existing = doctorDao.byKey(uid, key)
        if (existing != null) {
            // The web refreshes the profile on every save, and its rule is that a
            // non-empty incoming value always wins (database.py:508): the value
            // came from a human verifying the prescription, so it is more current
            // than whatever was stored. Without this the joined columns the
            // tiering and leaderboard queries read would keep the *first* value
            // ever recorded, and correcting a doctor's specialty would never show
            // up.
            val merged = existing.copy(
                name = incoming.name.ifBlank { existing.name },
                specialty = incoming.specialty.ifBlank { existing.specialty },
                chamber = incoming.chamber.ifBlank { existing.chamber },
                district = incoming.district.ifBlank { existing.district },
                territory = incoming.territory.ifBlank { existing.territory },
                upazila = incoming.upazila.ifBlank { existing.upazila },
                bmdcNo = incoming.bmdcNo.ifBlank { existing.bmdcNo },
            )
            // Only write when something actually changed: an update per scan would
            // touch the table on every save for no reason.
            if (merged != existing) doctorDao.update(merged)
            return existing.id
        }

        val inserted = doctorDao.insertIgnoring(incoming)
        return if (inserted != -1L) inserted else doctorDao.idFor(uid, key)
    }

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
        expectedUid: String,
        enriched: List<EnrichedMedicine> = emptyList(),
    ): Long {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
            ?: throw IllegalStateException("Sign in before saving a prescription")
        check(uid == expectedUid && expectedUid.isNotBlank()) { "The signed-in user changed; capture the prescription again" }
        val stampedCompany = TrustedMembership.cachedCompany(context, uid)
        val savedId = database.withTransaction {
            check(FirebaseAuth.getInstance().currentUser?.uid == uid) { "The signed-in user changed; capture the prescription again" }
            val id = prescriptionDao.insert(
                prescription.copy(
                    userId = uid,
                    scanId = java.util.UUID.randomUUID().toString(),
                    companyId = stampedCompany,
                    syncStatus = "PENDING",
                    totalMedicines = result.medicines.size,
                    mrId = mrId,
                    createdAt = prescription.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
                    doctorId = resolveDoctorId(prescription, uid),
                ),
            )
            val rows = result.medicines.mapIndexed { index, med ->
                val audit = enriched.getOrNull(index)
                // The catalogue-resolved manufacturer wins. The VL is told never to guess a
                // company, so `med.company` is blank in almost every read, and the analytics
                // queries all filter on a non-empty company_name.
                val originalCompany = audit?.company?.takeIf { it.isNotBlank() } ?: med.company
                val company = database.manufacturerAliasDao().canonicalFor(originalCompany) ?: originalCompany
                val canonicalOwn = ownCompany?.let { database.manufacturerAliasDao().canonicalFor(it) ?: it }
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
                    rawCompanyName = med.company.takeIf { it.isNotBlank() } ?: originalCompany.takeIf { it.isNotBlank() },
                    companyVerified = audit?.companyVerified ?: false,
                    isOwn = canonicalOwn != null && company.equals(canonicalOwn, ignoreCase = true),
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
            id
        }
        // Failure to schedule does not roll back the saved audit; periodic work
        // and sign-in scheduling will discover the durable pending row later.
        runCatching { UploadScheduling.enqueue(context, uid) }
        return savedId
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
    suspend fun drainQueue(currentUid: String, handler: suspend (QueuedScanEntity) -> Boolean): Int {
        var drained = 0
        // Snapshot the queue once, then process: observeAll() is a Flow and must not
        // be collected while entries are being removed from it.
        if (currentUid.isBlank() || FirebaseAuth.getInstance().currentUser?.uid != currentUid) return 0
        val pending = queueDao.observeAll(currentUid).first()
        for (row in pending) {
            if (FirebaseAuth.getInstance().currentUser?.uid != currentUid) break
            if (row.attempts >= MAX_QUEUE_ATTEMPTS) {
                queueDao.remove(currentUid, row.id)
                continue
            }
            queueDao.markAttempt(currentUid, row.id)
            if (handler(row)) {
                queueDao.remove(currentUid, row.id)
                drained++
                break
            }
        }
        return drained
    }

    suspend fun recentMedicines(currentUid: String, limit: Int, offset: Int) =
        prescriptionDao.recentMedicines(currentUid, limit, offset)

    suspend fun medicinesFor(currentUid: String, prescriptionId: Long) =
        prescriptionDao.medicinesFor(currentUid, prescriptionId)

    fun recentPrescriptions(currentUid: String, limit: Int = 10) = prescriptionDao.observeRecent(currentUid, limit)

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
