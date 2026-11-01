package com.medlenx.lab.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import com.medlenx.lab.data.local.MedLenXDatabase
import com.medlenx.lab.data.local.UserCaptureFiles
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await

/** Durable Room rows are authoritative; this work request is only a wake-up signal. */
class UploadScanWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid")?.takeIf { it.isNotBlank() } ?: return Result.failure()
        val app = TrustedMembership.firebase(applicationContext) ?: return Result.retry()
        val auth = FirebaseAuth.getInstance(app)
        if (auth.currentUser?.uid != uid) return Result.success() // Dormant until this UID signs in again.
        try {
            val dao = MedLenXDatabase.get(applicationContext).prescriptionDao()
            val company = TrustedMembership.refresh(applicationContext, uid)
            if (auth.currentUser?.uid != uid) return Result.success()
            val pending = dao.pendingUploads(uid)
            if (company == null) {
                // A server-confirmed missing/inactive membership cannot access any
                // stamped tenant under our rules. Unstamped rows remain claimable.
                var rejected = false
                for (rx in pending) {
                    if (auth.currentUser?.uid != uid) return Result.success()
                    if (rx.companyId.isNotBlank()) {
                        dao.markRejected(uid, rx.id, rx.companyId, rx.scanId)
                        rejected = true
                    }
                }
                return if (rejected) Result.failure() else Result.retry()
            }
            val firestore = FirebaseFirestore.getInstance(app)
            val member = firestore.collection("companies").document(company).collection("members").document(uid)
                .get(Source.SERVER).await()
            val name = member.getString("displayName").orEmpty()
            if (member.getBoolean("active") != true || name.isBlank()) return Result.retry()
            var rejected = false
            for (rx in pending) {
                if (auth.currentUser?.uid != uid) return Result.success()
                // An empty snapshot needs explicit claiming. A different verified
                // company cannot access the stamped tenant under the deployed rules.
                if (rx.companyId.isBlank()) continue
                if (rx.companyId != company || rx.scanId.isBlank()) {
                    dao.markRejected(uid, rx.id, rx.companyId, rx.scanId)
                    rejected = true
                    continue
                }
                try {
                    val doc = firestore.collection("companies").document(rx.companyId)
                        .collection("team_scans").document(rx.scanId)
                    val medicines = dao.medicinesFor(uid, rx.id).map { med ->
                        mapOf("brand" to med.brandName, "generic" to med.generic,
                            "confidence" to med.confidenceScore, "company" to med.companyName.orEmpty())
                    }
                    val existing = doc.get(Source.SERVER).await()
                    if (existing.exists()) {
                        // An ACK may have been lost. Reconcile instead of updating.
                        if (existing.getString("createdByUid") != uid || existing.getString("scanId") != rx.scanId ||
                            existing.getString("doctorName") != rx.doctorName ||
                            existing.getString("doctorSpecialty") != rx.doctorSpecialty ||
                            existing.getString("territory") != rx.territory || existing.get("medicines") != medicines ||
                            (existing.contains("captureTimestamp") &&
                                (existing.get("captureTimestamp") as? Timestamp)?.toDate()?.time != rx.createdAt)) {
                            return Result.failure()
                        }
                    } else {
                        if (auth.currentUser?.uid != uid) return Result.success()
                        doc.set(mapOf(
                            "scanId" to rx.scanId,
                            "doctorName" to rx.doctorName,
                            "doctorSpecialty" to rx.doctorSpecialty,
                            "territory" to rx.territory,
                            "medicines" to medicines,
                            "scannedBy" to name,
                            "createdByUid" to uid,
                            "captureTimestamp" to Timestamp(Date(rx.createdAt)),
                            "timestamp" to FieldValue.serverTimestamp(),
                        )).await()
                    }
                    if (auth.currentUser?.uid != uid) return Result.success()
                    dao.markUploaded(uid, rx.id, rx.companyId, rx.scanId)
                } catch (e: FirebaseFirestoreException) {
                    if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
                    if (auth.currentUser?.uid != uid) return Result.success()
                    dao.markRejected(uid, rx.id, rx.companyId, rx.scanId)
                    rejected = true
                }
            }
            if (rejected) return Result.failure()
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.retry()
        }
    }
}

object UploadScheduling {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    fun workTag(uid: String): String = "rx-upload-user-" + UserCaptureFiles.key(uid)
    fun periodicName(uid: String): String = "rx-upload-" + UserCaptureFiles.key(uid)

    /** Periodic work rescues a crash between Room commit and one-shot enqueue. */
    fun start(context: Context, uid: String) {
        if (uid.isBlank() || uid == "legacy_user") return
        val manager = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<UploadScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network).setInputData(workDataOf("uid" to uid)).addTag(workTag(uid)).build()
        manager.enqueueUniquePeriodicWork(periodicName(uid), ExistingPeriodicWorkPolicy.KEEP, periodic)
        enqueue(context, uid)
    }

    fun enqueue(context: Context, uid: String) {
        if (uid.isBlank() || uid == "legacy_user") return
        val request = OneTimeWorkRequestBuilder<UploadScanWorker>()
            .setConstraints(network).setInputData(workDataOf("uid" to uid)).addTag(workTag(uid)).build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
