package com.medlenx.lab.data.repo

import android.content.Context
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.local.DeletedScanTombstone
import com.medlenx.lab.data.sync.TrustedMembership

/** Local-only actions: never claim to delete or approve a cloud copy. */
object AuditActions {
    private fun requireOwner(context: Context, uid: String) {
        val app = checkNotNull(TrustedMembership.firebase(context))
        check(uid.isNotBlank() && uid != "legacy_user" && FirebaseAuth.getInstance(app).currentUser?.uid == uid) { "Account changed; sign in again" }
    }

    suspend fun delete(context: Context, uid: String, id: Long) {
        requireOwner(context, uid)
        val db = (context.applicationContext as MedLenXApp).graph.database
        val dao = db.prescriptionDao()
        db.withTransaction {
            requireOwner(context, uid)
            val rx = checkNotNull(dao.byId(uid, id)) { "Scan not found" }
            if (rx.companyId.isNotBlank() && rx.scanId.isNotBlank()) {
                dao.addDeletedScanTombstone(DeletedScanTombstone(uid, rx.companyId, rx.scanId))
            }
            dao.deleteOwnedMedicines(uid, id)
            dao.deleteOwnedVisits(uid, id)
            check(dao.deleteOwnedPrescription(uid, id) == 1) { "Scan could not be deleted" }
        }
    }

    suspend fun approve(context: Context, uid: String, id: Long) {
        requireOwner(context, uid)
        val db = (context.applicationContext as MedLenXApp).graph.database
        db.withTransaction {
            requireOwner(context, uid)
            check(db.prescriptionDao().approveDuplicateForTesting(uid, id) == 1) { "Duplicate alert is no longer available" }
        }
    }
}
