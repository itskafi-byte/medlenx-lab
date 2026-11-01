package com.medlenx.lab.data.sync

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Query
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
                    val territory: String, val timestamp: Long?, val medicines: List<TeamMedicine>)
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
        val registration = FirebaseFirestore.getInstance(app).collection("companies").document(company)
            .collection("team_scans").orderBy("timestamp", Query.Direction.DESCENDING).limit(30)
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) { close(error); return@addSnapshotListener }
                if (auth.currentUser?.uid != uid || snapshot == null || snapshot.metadata.isFromCache) {
                    // Firestore disk cache is shared between sign-ins. Do not show
                    // cached results to a different user or after connectivity loss.
                    trySend(TeamFeedState(message = "Connect to verify and view team activity."))
                    return@addSnapshotListener
                }
                val scans = snapshot.documents.map { doc ->
                    val meds = (doc.get("medicines") as? List<*>)?.mapNotNull { value ->
                        val m = value as? Map<*, *> ?: return@mapNotNull null
                        TeamMedicine(m["brand"] as? String ?: "", m["generic"] as? String ?: "",
                            (m["confidence"] as? Number)?.toDouble() ?: 0.0, m["company"] as? String ?: "")
                    }.orEmpty()
                    TeamScan(doc.id, doc.getString("scannedBy").orEmpty(), doc.getString("doctorName").orEmpty(),
                        doc.getString("doctorSpecialty").orEmpty(), doc.getString("territory").orEmpty(),
                        doc.getTimestamp("timestamp")?.toDate()?.time, meds)
                }
                trySend(TeamFeedState(scans, if (scans.isEmpty()) "No team activity yet." else ""))
            }
        awaitClose { registration.remove() }
    }
}
