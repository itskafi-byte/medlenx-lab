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
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.medlenx.lab.data.local.MedLenXDatabase
import com.medlenx.lab.data.local.UserCaptureFiles
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
            val company = TrustedMembership.refresh(applicationContext, uid) ?: return Result.retry()
            if (auth.currentUser?.uid != uid) return Result.success()
            val firestore = FirebaseFirestore.getInstance(app)
            val member = firestore.collection("companies").document(company).collection("members").document(uid)
                .get(Source.SERVER).await()
            val name = member.getString("displayName").orEmpty()
            if (member.getBoolean("active") != true || name.isBlank()) return Result.retry()
            val dao = MedLenXDatabase.get(applicationContext).prescriptionDao()
            for (rx in dao.pendingUploads(uid)) {
                // Rows predating the capture-time snapshot, or assigned to a prior
                // company, need deliberate review. Never silently reroute them.
                if (rx.companyId != company || rx.scanId.isBlank()) continue
                if (auth.currentUser?.uid != uid) return Result.success()
                val doc = firestore.collection("companies").document(rx.companyId)
                    .collection("team_scans").document(rx.scanId)
                val medicines = dao.medicinesFor(uid, rx.id).map { med ->
                    mapOf("brand" to med.brandName, "generic" to med.generic,
                        "confidence" to med.confidenceScore, "company" to med.companyName.orEmpty())
                }
                val existing = doc.get(Source.SERVER).await()
                if (existing.exists()) {
                    // Rules are create-only. An ACK may have been lost; reconcile
                    // the immutable document instead of issuing a forbidden update.
                    if (existing.getString("createdByUid") != uid || existing.getString("scanId") != rx.scanId ||
                        existing.getString("doctorName") != rx.doctorName ||
                        existing.getString("doctorSpecialty") != rx.doctorSpecialty ||
                        existing.getString("territory") != rx.territory || existing.get("medicines") != medicines) {
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
                        "timestamp" to FieldValue.serverTimestamp(),
                    )).await()
                }
                if (auth.currentUser?.uid != uid) return Result.success()
                dao.markUploaded(uid, rx.id, rx.companyId, rx.scanId)
            }
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

    /** Periodic work rescues a crash between Room commit and one-shot enqueue. */
    fun start(context: Context, uid: String) {
        if (uid.isBlank() || uid == "legacy_user") return
        val manager = WorkManager.getInstance(context)
        val periodic = PeriodicWorkRequestBuilder<UploadScanWorker>(15, TimeUnit.MINUTES)
            .setConstraints(network).setInputData(workDataOf("uid" to uid)).build()
        manager.enqueueUniquePeriodicWork("rx-upload-" + UserCaptureFiles.key(uid), ExistingPeriodicWorkPolicy.KEEP, periodic)
        enqueue(context, uid)
    }

    fun enqueue(context: Context, uid: String) {
        if (uid.isBlank() || uid == "legacy_user") return
        val request = OneTimeWorkRequestBuilder<UploadScanWorker>()
            .setConstraints(network).setInputData(workDataOf("uid" to uid)).build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
