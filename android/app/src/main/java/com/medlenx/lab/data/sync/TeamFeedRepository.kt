package com.medlenx.lab.data.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ListenerRegistration
import com.medlenx.lab.data.local.AvatarStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** Only verified scan results are shared; no prescription image is included. */
data class TeamMedicine(val brand: String, val generic: String, val confidence: Double, val company: String)
data class TeamScan(val id: String, val officer: String, val doctor: String, val specialty: String,
                    val territory: String, val timestamp: Long?, val medicines: List<TeamMedicine>,
                    val captureTimestamp: Long? = null, val createdByUid: String = "",
                    val avatarId: String? = null, val avatarResolved: Boolean = false)
data class TeamFeedState(val scans: List<TeamScan> = emptyList(), val message: String = "Connecting to team activity…")

object TeamFeedRepository {
    fun observe(context: Context, uid: String): Flow<TeamFeedState> = flow {
        while (FirebaseAuth.getInstance(TrustedMembership.firebase(context) ?: return@flow).currentUser?.uid == uid) {
            try {
                val company = TrustedMembership.refresh(context, uid)
                if (company == null) {
                    emit(TeamFeedState(message = "An administrator must approve your company membership to view team activity."))
                    delay(30_000)
                    continue
                }
                emitAll(listen(context, uid, company))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(TeamFeedState(message = "Team activity unavailable. Check your connection or company membership."))
                delay(30_000)
            }
        }
    }

    private fun listen(context: Context, uid: String, company: String): Flow<TeamFeedState> = callbackFlow {
        val app = TrustedMembership.firebase(context) ?: run { close(); return@callbackFlow }
        val auth = FirebaseAuth.getInstance(app)
        val db = FirebaseFirestore.getInstance(app)
        var scans: List<TeamScan> = emptyList()
        var avatars: Map<String, String> = emptyMap()
        var feedReady = false
        var avatarsReady = false
        fun publish() {
            if (auth.currentUser?.uid != uid || !feedReady) return
            val resolved = scans.map { scan -> scan.copy(avatarId = avatars[scan.createdByUid], avatarResolved = avatarsReady) }
            trySend(TeamFeedState(resolved, if (resolved.isEmpty()) "No team activity yet." else ""))
        }
        var avatarRegistration: ListenerRegistration? = null
        var watchedIds: List<String>? = null
        var avatarGeneration = 0
        fun watchAvatars(ids: List<String>) {
            if (ids == watchedIds) return
            watchedIds = ids
            avatarGeneration += 1
            val generation = avatarGeneration
            avatarRegistration?.remove()
            avatars = emptyMap()
            avatarsReady = ids.isEmpty()
            if (ids.isEmpty()) return
            // At most 30 scan authors; query only the visible feed's IDs.
            avatarRegistration = db.collection("companies").document(company).collection("avatars")
                .whereIn(FieldPath.documentId(), ids)
                .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                    if (auth.currentUser?.uid != uid || generation != avatarGeneration) return@addSnapshotListener
                    if (error != null || snapshot == null || snapshot.metadata.isFromCache) {
                        avatars = emptyMap()
                        avatarsReady = false
                    } else {
                        avatars = snapshot.documents.mapNotNull { doc ->
                            val id = doc.getString("avatarId")
                            if (!doc.metadata.hasPendingWrites() && AvatarStore.valid(id)) doc.id to checkNotNull(id) else null
                        }.toMap()
                        avatarsReady = !snapshot.metadata.hasPendingWrites()
                    }
                    publish()
                }
        }
        val registration = db.collection("companies").document(company)
            .collection("team_scans").orderBy("timestamp", Query.Direction.DESCENDING).limit(30)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                if (auth.currentUser?.uid != uid || snapshot == null || snapshot.metadata.isFromCache) {
                    feedReady = false
                    trySend(TeamFeedState(message = "Connect to verify and view team activity."))
                    return@addSnapshotListener
                }
                scans = snapshot.documents.filterNot { it.metadata.hasPendingWrites() }.map { doc ->
                    val meds = (doc.get("medicines") as? List<*>)?.mapNotNull { value ->
                        val m = value as? Map<*, *> ?: return@mapNotNull null
                        TeamMedicine(m["brand"] as? String ?: "", m["generic"] as? String ?: "",
                            (m["confidence"] as? Number)?.toDouble() ?: 0.0, m["company"] as? String ?: "")
                    }.orEmpty()
                    TeamScan(doc.id, doc.getString("scannedBy").orEmpty(), doc.getString("doctorName").orEmpty(),
                        doc.getString("doctorSpecialty").orEmpty(), doc.getString("territory").orEmpty(),
                        doc.getTimestamp("timestamp")?.toDate()?.time, meds,
                        doc.getTimestamp("captureTimestamp")?.toDate()?.time,
                        doc.getString("createdByUid").orEmpty())
                }
                feedReady = true
                watchAvatars(scans.map { it.createdByUid }.filter { it.isNotBlank() && it.length <= 128 && '/' !in it }.distinct().sorted())
                publish()
            }
        awaitClose { registration.remove(); avatarRegistration?.remove() }
    }
}
