package com.medlenx.lab.data.sync

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import com.medlenx.lab.BuildConfig
import com.medlenx.lab.data.local.UserCaptureFiles
import kotlinx.coroutines.tasks.await

/** Company IDs originate only in administrator-managed Firestore documents. */
object TrustedMembership {
    @Synchronized
    fun firebase(context: Context): FirebaseApp? {
        if (BuildConfig.FIREBASE_API_KEY.isBlank() || BuildConfig.FIREBASE_APP_ID.isBlank() || BuildConfig.FIREBASE_PROJECT_ID.isBlank()) return null
        return FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(
            context, FirebaseOptions.Builder().setApiKey(BuildConfig.FIREBASE_API_KEY)
                .setApplicationId(BuildConfig.FIREBASE_APP_ID).setProjectId(BuildConfig.FIREBASE_PROJECT_ID).build(),
        )
    }

    private fun prefs(context: Context, uid: String) = context.getSharedPreferences(
        "medlenx_membership_" + UserCaptureFiles.key(uid), Context.MODE_PRIVATE,
    )

    fun cachedCompany(context: Context, uid: String): String = prefs(context, uid).getString("company", "").orEmpty()

    /** Never treat editable Settings or the device-wide Firestore document cache as authority. */
    suspend fun refresh(context: Context, uid: String): String? {
        val app = firebase(context) ?: return null
        if (FirebaseAuth.getInstance(app).currentUser?.uid != uid) return null
        val db = FirebaseFirestore.getInstance(app)
        val user = db.collection("users").document(uid).get(Source.SERVER).await()
        val company = user.getString("companyId").orEmpty()
        if (company.isBlank() || company.contains('/') || company == "." || company == "..") {
            prefs(context, uid).edit().remove("company").apply()
            return null
        }
        val member = db.collection("companies").document(company).collection("members").document(uid)
            .get(Source.SERVER).await()
        if (member.getBoolean("active") != true || member.getString("displayName").isNullOrBlank()) {
            prefs(context, uid).edit().remove("company").apply()
            return null
        }
        if (FirebaseAuth.getInstance(app).currentUser?.uid != uid) return null
        prefs(context, uid).edit().putString("company", company).apply()
        return company
    }
}
