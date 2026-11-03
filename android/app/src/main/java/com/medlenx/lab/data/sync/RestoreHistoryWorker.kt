package com.medlenx.lab.data.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.medlenx.lab.data.local.MedLenXDatabase
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.local.ScannedMedicineEntity
import com.medlenx.lab.data.local.UserCaptureFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Restore only server-confirmed results from the currently assigned company. */
class RestoreHistoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid")?.takeIf { it.isNotBlank() && it != "legacy_user" }
            ?: return Result.failure()
        val app = TrustedMembership.firebase(applicationContext) ?: return Result.retry()
        val auth = FirebaseAuth.getInstance(app)
        if (auth.currentUser?.uid != uid) return Result.success()
        return try {
            val company = TrustedMembership.refresh(applicationContext, uid) ?: return Result.retry()
            val db = MedLenXDatabase.get(applicationContext)
            val dao = db.prescriptionDao()
            // Existing uploads use createdByUid, not userId. Document-ID ordering
            // permits bounded pages without a composite timestamp index.
            val base = FirebaseFirestore.getInstance(app).collection("companies").document(company)
                .collection("team_scans").whereEqualTo("createdByUid", uid)
                .orderBy(FieldPath.documentId()).limit(100)
            var cursor: DocumentSnapshot? = null
            while (true) {
                if (auth.currentUser?.uid != uid) return Result.success()
                val page = (cursor?.let { base.startAfter(it) } ?: base).get(Source.SERVER).await()
                if (auth.currentUser?.uid != uid) return Result.success()
                // Reverify after the network query, before writing any data locally.
                if (TrustedMembership.refresh(applicationContext, uid) != company) return Result.retry()
                db.withTransaction {
                    check(auth.currentUser?.uid == uid)
                    for (doc in page.documents) {
                        if (doc.metadata.hasPendingWrites() || doc.getString("createdByUid") != uid) continue
                        val restored = runCatching { parse(doc, uid, company) }.getOrNull() ?: continue
                        // Check ALL local scans, including pending ones, before using
                        // the restored-ID unique index. Never replace local detail/photos.
                        if (dao.countByScanId(uid, doc.id) != 0) {
                            // Correct only previously restored rows, and only when
                            // the original capture timestamp is actually available.
                            val captured = (doc.get("captureTimestamp") as? com.google.firebase.Timestamp)?.toDate()?.time
                            if (captured != null) dao.updateRestoredCaptureTime(uid, company, doc.id, captured)
                            continue
                        }
                        val id = dao.insertRestored(restored.first)
                        if (id != -1L) dao.insertMedicines(restored.second.map { medicine ->
                            val original = medicine.companyName
                            medicine.copy(prescriptionId = id, rawCompanyName = original,
                                companyName = original?.let { db.manufacturerAliasDao().canonicalFor(it) ?: it })
                        })
                    }
                    check(auth.currentUser?.uid == uid)
                }
                if (page.documents.size < 100) break
                cursor = page.documents.last()
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun parse(doc: DocumentSnapshot, uid: String, company: String): Pair<PrescriptionEntity, List<ScannedMedicineEntity>>? {
        val scanId = doc.id
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
        if (!uuid.matches(scanId) || doc.getString("scanId") != scanId) return null
        val doctor = doc.getString("doctorName") ?: return null
        val specialty = doc.getString("doctorSpecialty") ?: return null
        val territory = doc.getString("territory") ?: return null
        // Older cloud documents lack captureTimestamp; their upload time is the
        // only available fallback and is not the original capture time.
        val timestamp = (doc.get("captureTimestamp") as? com.google.firebase.Timestamp)?.toDate()?.time
            ?: doc.getTimestamp("timestamp")?.toDate()?.time ?: return null
        val raw = doc.get("medicines") as? List<*> ?: return null
        if (raw.size > 100) return null
        val medicines = raw.mapIndexed { index, value ->
            val med = value as? Map<*, *> ?: return null
            val brand = med["brand"] as? String ?: return null
            val generic = med["generic"] as? String ?: return null
            val confidence = (med["confidence"] as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: return null
            val manufacturer = med["company"] as? String ?: ""
            ScannedMedicineEntity(
                prescriptionId = 0, lineNumber = index + 1, brandName = brand, generic = generic,
                strength = "", dosageForm = "", dosage = "", raw = "", companyName = manufacturer.takeIf { it.isNotBlank() },
                companyVerified = false, isOwn = false, confidenceScore = confidence, imageUrl = null,
                matchType = "cloud-restored", isAntibiotic = false, broadSpectrum = false,
                therapeuticClass = null, nemlListed = false, dgdaFlagged = false, tripsWatch = false,
            )
        }
        val rx = PrescriptionEntity(
            userId = uid, scanId = scanId, restoreScanId = scanId, companyId = company,
            syncStatus = "UPLOADED", rxNo = scanId.take(8), imagePath = "", imageHash = null,
            doctorName = doctor, doctorBmdcNo = "", doctorId = null, doctorSpecialty = specialty,
            chamber = "", district = "", upazila = "", territory = territory, prescriptionSource = "",
            totalMedicines = medicines.size, mrId = "", duplicateOf = null, offTerritory = false,
            territoryNote = null, lat = null, lng = null, createdAt = timestamp,
        )
        return rx to medicines
    }
}

object HistoryRestoreScheduling {
    fun start(context: Context, uid: String) {
        if (uid.isBlank() || uid == "legacy_user") return
        val request = OneTimeWorkRequestBuilder<RestoreHistoryWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("uid" to uid)).build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            "rx-restore-" + UserCaptureFiles.key(uid), ExistingWorkPolicy.KEEP, request,
        )
    }
}
