package com.medlenx.lab.data.local

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext

/**
 * Quarantine old, unscoped images without assigning them to an account.
 * Re-run on startup: a crash or an I/O error leaves the source available for retry.
 * Temporary camera cache files are deliberately not moved: a camera activity may
 * still hold a URI to one after Android recreates the process.
 */
object LegacyImageQuarantine {
    suspend fun run(context: Context, db: MedLenXDatabase) = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "rx")
        val archive = File(context.filesDir, "prescriptions/legacy_user")
        val rootCanonical = root.canonicalFile
        val images = root.listFiles().orEmpty().filter { file ->
            file.isFile && file.canonicalFile.parentFile == rootCanonical &&
                file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp")
        }
        if (images.isEmpty()) return@withContext
        check(archive.mkdirs() || archive.isDirectory)
        for (source in images) {
            // A distinct destination prevents overwriting either a previous copy or
            // an unrelated image with the same name. No originals are removed if
            // copying, verification, or the database transaction fails.
            val dest = File(archive, "${UUID.randomUUID()}_${source.name}")
            try {
                source.copyTo(dest, overwrite = false)
                check(source.length() == dest.length() && digest(source).contentEquals(digest(dest)))
                db.withTransaction {
                    db.prescriptionDao().relocateLegacyImage(source.absolutePath, dest.absolutePath)
                    db.queueDao().relocateLegacyImage(source.absolutePath, dest.absolutePath)
                }
                // If deletion fails both verified copies remain; startup retries safely.
                source.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Leave both the original and any archive copy intact for recovery.
                android.util.Log.w("LegacyImageQuarantine", "Could not quarantine an image", e)
            }
        }
    }

    private fun digest(file: File): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                hash.update(buffer, 0, count)
            }
        }
        return hash.digest()
    }
}
