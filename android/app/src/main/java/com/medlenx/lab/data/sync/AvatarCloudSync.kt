package com.medlenx.lab.data.sync

import android.content.Context
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
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.medlenx.lab.data.local.AvatarStore
import com.medlenx.lab.data.local.UserCaptureFiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await

/** Durable, account-scoped ID sync. Avatar images are never uploaded. */
object AvatarCloudSync {
    private val edited = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun startSession(uid: String) { edited.remove(uid) }
    fun markEdited(uid: String) { edited.add(uid) }
    fun schedule(context: Context, uid: String) {
        if (uid.isBlank() || uid == "legacy_user") return
        val request = OneTimeWorkRequestBuilder<AvatarSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf("uid" to uid)).build()
        // Serialize writes for one UID. Every worker rereads the newest selection.
        WorkManager.getInstance(context).enqueueUniqueWork("avatar-sync-" + UserCaptureFiles.key(uid),
            ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** Restore only server-confirmed own data, never over a pending local selection. */
    suspend fun watch(context: Context, uid: String) {
        val app = TrustedMembership.firebase(context) ?: return
        val auth = FirebaseAuth.getInstance(app)
        if (auth.currentUser?.uid != uid) return
        if (AvatarStore.snapshot(context, uid).second != null) schedule(context, uid)
        val document = FirebaseFirestore.getInstance(app).collection("users").document(uid)
        val snapshot = callbackFlow<DocumentSnapshot> {
            val listener = document.addSnapshotListener(MetadataChanges.INCLUDE) { value, error ->
                if (error != null) close(error)
                else if (value != null && !value.metadata.isFromCache && !value.metadata.hasPendingWrites()) trySend(value)
            }
            awaitClose { listener.remove() }
        }.first()
        if (auth.currentUser?.uid != uid || uid in edited) return
        val remote = snapshot.getString("avatarId")
        if (AvatarStore.valid(remote)) AvatarStore.restoreIfClean(context, uid, checkNotNull(remote))
        // Mirror a restored or older local selection to the current verified company.
        if (auth.currentUser?.uid == uid && AvatarStore.snapshot(context, uid).first != null) schedule(context, uid)
    }
}

class AvatarSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val uid = inputData.getString("uid")?.takeIf { it.isNotBlank() && it != "legacy_user" } ?: return Result.failure()
        val app = TrustedMembership.firebase(applicationContext) ?: return Result.retry()
        val auth = FirebaseAuth.getInstance(app)
        if (auth.currentUser?.uid != uid) return Result.success()
        return try {
            val (id, token) = AvatarStore.snapshot(applicationContext, uid)
            if (!AvatarStore.valid(id)) return Result.success()
            val selected = checkNotNull(id)
            val db = FirebaseFirestore.getInstance(app)
            val user = db.collection("users").document(uid)
            val company = TrustedMembership.refresh(applicationContext, uid)
            if (auth.currentUser?.uid != uid) return Result.success()
            if (company == null) {
                // Still back up the ID; retry mirroring when membership is approved.
                user.set(mapOf("avatarId" to selected), SetOptions.merge()).await()
                Result.retry()
            } else {
                val directory = db.collection("companies").document(company).collection("avatars").document(uid)
                val batch = db.batch()
                batch.set(user, mapOf("avatarId" to selected), SetOptions.merge())
                batch.set(directory, mapOf("avatarId" to selected))
                batch.commit().await()
                if (auth.currentUser?.uid == uid) AvatarStore.acknowledge(applicationContext, uid, token)
                Result.success()
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}
