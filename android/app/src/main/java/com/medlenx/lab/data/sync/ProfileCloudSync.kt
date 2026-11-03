package com.medlenx.lab.data.sync

import android.content.Context
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import kotlinx.coroutines.tasks.await
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.local.BrandTargetEntity
import com.medlenx.lab.data.local.DailyTargetSettings
import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.local.UserCaptureFiles
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Own-user profile only. Firestore's Android persistent cache queues writes offline. */
object ProfileCloudSync {
    private val lock = Mutex()
    // Once edited in a session, old server snapshots must not roll back a save
    // even if their callbacks arrive after the write has been acknowledged.
    private val editedThisSession = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun startSession(uid: String) { editedThisSession.remove(uid) }
    val restored = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)
    val errors = MutableSharedFlow<Pair<String, String>>(extraBufferCapacity = 8)

    private fun prefs(context: Context, uid: String) = context.getSharedPreferences(
        DailyTargetSettings.PREFERENCES + "_" + UserCaptureFiles.key(uid), Context.MODE_PRIVATE)
    private fun auth(context: Context) = FirebaseAuth.getInstance(checkNotNull(TrustedMembership.firebase(context)))
    private fun firestore(context: Context) = FirebaseFirestore.getInstance(checkNotNull(TrustedMembership.firebase(context)))
    private fun requireUser(context: Context, uid: String) {
        check(uid.isNotBlank() && uid != "legacy_user" && auth(context).currentUser?.uid == uid)
    }

    suspend fun save(context: Context, profile: OfficerProfileEntity, targets: List<BrandTargetEntity>, daily: Int) {
        val uid = profile.userId
        lock.withLock {
            requireUser(context, uid)
            editedThisSession.add(uid)
            val app = context.applicationContext as MedLenXApp
            val prefs = prefs(context, uid)
            // The dirty marker is set first, so a crash between local commit and
            // Firestore enqueue cannot make a stale server restore overwrite it.
            val token = UUID.randomUUID().toString()
            check(prefs.edit().putString("profile_sync_dirty", token).commit())
            app.graph.database.withTransaction {
                requireUser(context, uid)
                app.graph.profileDao.save(profile)
                app.graph.profileDao.clearTargets(uid)
                app.graph.profileDao.saveTargets(targets)
            }
            check(prefs.edit().putInt(DailyTargetSettings.KEY, daily).commit())
            try { enqueue(context, profile, targets, daily, token) }
            catch (_: Exception) { errors.tryEmit(uid to "Saved locally, but cloud backup could not be queued. It will retry on sign-in.") }
        }
    }

    /** Onboarding requires a server check before creating an otherwise empty profile. */
    suspend fun saveCompany(context: Context, uid: String, company: String) {
        requireUser(context, uid)
        require(com.medlenx.lab.data.local.CompanySelection.isAssigned(company))
        val snapshot = firestore(context).collection("users").document(uid).get(Source.SERVER).await()
        requireUser(context, uid)
        val app = context.applicationContext as MedLenXApp
        val local = app.graph.profileDao.current(uid)
        val remoteMap = snapshot.get("officerProfile") as? Map<*, *>
        val remote = remoteMap?.let { parse(uid, it) }
        if (snapshot.contains("officerProfile") && remote == null) {
            error("Your cloud profile could not be read safely. Contact support before continuing.")
        }
        // A dirty local profile has unsynced edits; otherwise prefer the checked
        // server snapshot so a fresh-device stub cannot erase cloud details.
        val useLocal = local != null && (remote == null || prefs(context, uid).contains("profile_sync_dirty"))
        val profile = (if (useLocal) local else remote?.first)
            ?: OfficerProfileEntity(uid, "", "", "", "", "", "", "")
        val targets = if (useLocal) app.graph.profileDao.observeTargets(uid).first() else remote?.second.orEmpty()
        val daily = if (useLocal) prefs(context, uid).getInt(DailyTargetSettings.KEY, DailyTargetSettings.DEFAULT)
            else remote?.third ?: DailyTargetSettings.DEFAULT
        save(context, profile.copy(company = company.trim()), targets, daily)
    }

    private fun enqueue(context: Context, profile: OfficerProfileEntity, targets: List<BrandTargetEntity>, daily: Int, token: String) {
        val uid = profile.userId
        requireUser(context, uid)
        val payload = mapOf("officerProfile" to mapOf(
            "company" to profile.company, "employeeId" to profile.employeeId,
            "fullName" to profile.fullName, "designation" to profile.role,
            "division" to profile.division, "territory" to profile.territory,
            "portfolio" to profile.portfolio, "dailyTarget" to daily,
            "monthlyTarget" to targets.map { mapOf("brand" to it.brand, "target" to it.target) },
        ))
        // No await: offline saves complete locally and the SDK sends them later.
        firestore(context).collection("users").document(uid).set(payload, SetOptions.merge())
            .addOnCompleteListener { task ->
                val app = context.applicationContext as MedLenXApp
                app.graph.scope.launch {
                    lock.withLock {
                        val prefs = prefs(context, uid)
                        if (prefs.getString("profile_sync_dirty", null) == token) {
                            if (task.isSuccessful) prefs.edit().remove("profile_sync_dirty").commit()
                            else errors.tryEmit(uid to "Saved locally, but cloud backup failed. Check your connection or contact your administrator.")
                        }
                    }
                }
            }
    }

    /** Re-enqueue an interrupted local save, then apply only server-confirmed snapshots. */
    suspend fun watch(context: Context, uid: String) {
        val app = context.applicationContext as MedLenXApp
        lock.withLock {
            requireUser(context, uid)
            val prefs = prefs(context, uid)
            val token = prefs.getString("profile_sync_dirty", null)
            if (token != null) {
                editedThisSession.add(uid)
                val profile = app.graph.profileDao.current(uid)
                if (profile == null) {
                    // A crash before the first local profile commit left nothing to send.
                    prefs.edit().remove("profile_sync_dirty").commit()
                } else {
                    val targets = app.graph.profileDao.observeTargets(uid).first()
                    enqueue(context, profile, targets, prefs.getInt(DailyTargetSettings.KEY, DailyTargetSettings.DEFAULT), token)
                }
            }
        }
        val doc = firestore(context).collection("users").document(uid)
        callbackFlow {
            val registration = doc.addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (error != null) {
                    errors.tryEmit(uid to "Cloud profile restore is unavailable. Local settings are unchanged.")
                } else if (snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()) {
                    trySend(snapshot)
                }
            }
            awaitClose { registration.remove() }
        }.collect { snapshot ->
            val map = snapshot.get("officerProfile") as? Map<*, *> ?: return@collect
            val profile = parse(uid, map) ?: return@collect
            lock.withLock {
                if (auth(context).currentUser?.uid != uid || uid in editedThisSession || prefs(context, uid).contains("profile_sync_dirty")) return@withLock
                app.graph.database.withTransaction {
                    requireUser(context, uid)
                    app.graph.profileDao.save(profile.first)
                    app.graph.profileDao.clearTargets(uid)
                    app.graph.profileDao.saveTargets(profile.second)
                }
                prefs(context, uid).edit().putInt(DailyTargetSettings.KEY, profile.third).commit()
                restored.tryEmit(uid)
            }
        }
    }

    private fun parse(uid: String, map: Map<*, *>): Triple<OfficerProfileEntity, List<BrandTargetEntity>, Int>? {
        fun text(key: String): String? = (map[key] as? String)?.takeIf { it.length <= 500 }
        val company = text("company") ?: return null
        val employeeId = text("employeeId") ?: return null
        val fullName = text("fullName") ?: return null
        val role = text("designation") ?: return null
        val division = text("division") ?: return null
        val territory = text("territory") ?: return null
        val portfolio = text("portfolio") ?: return null
        val daily = (map["dailyTarget"] as? Long)?.takeIf { it in 1L..9999L }?.toInt() ?: return null
        val raw = map["monthlyTarget"] as? List<*> ?: return null
        if (raw.size > 100) return null
        val targets = raw.map { item ->
            val entry = item as? Map<*, *> ?: return null
            val brand = (entry["brand"] as? String)?.takeIf { it.isNotBlank() && it.length <= 500 } ?: return null
            val target = (entry["target"] as? Long)?.takeIf { it in 0L..Int.MAX_VALUE.toLong() }?.toInt() ?: return null
            BrandTargetEntity(userId = uid, brand = brand, target = target)
        }
        return Triple(OfficerProfileEntity(uid, company, employeeId, fullName, role, division, territory, portfolio), targets, daily)
    }
}
