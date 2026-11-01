package com.medlenx.lab.data.local

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Directories for newly captured images; legacy images remain under the old rx root. */
object UserCaptureFiles {
    // Hash the UID so it can never introduce a path separator or a traversal segment.
    private fun key(uid: String): String {
        require(uid.isNotBlank()) { "An authenticated user is required" }
        return MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte ->
                val n = byte.toInt() and 0xff
                "0123456789abcdef"[n ushr 4].toString() + "0123456789abcdef"[n and 15]
            }
    }

    fun stored(context: Context, uid: String): File = File(context.filesDir, "rx/${key(uid)}")
    fun temporary(context: Context, uid: String): File = File(context.cacheDir, "rx/${key(uid)}")
}
