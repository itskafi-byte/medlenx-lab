package com.medlenx.lab.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Shrinks a camera capture to something a vision model can actually ingest.
 *
 * A modern phone writes 12MP JPEGs of 3-8MB. Sent as-is that becomes a ~4-11MB
 * base64 JSON body: some providers reject it outright, the rest take long enough
 * that the reply hits `max_tokens` and truncates, and decoding the full bitmap
 * first is how an upload OOMs on a low-end device.
 *
 * 1600px on the long edge is comfortably above what a prescription needs
 * (handwriting at that resolution is still legible) and roughly a tenth of the
 * payload. EXIF orientation is baked in, because a rotated capture sent
 * unrotated is a guaranteed misread.
 */
object ImagePrep {

    private const val MAX_EDGE = 1600
    private const val JPEG_QUALITY = 85

    /** Re-encoded upload bytes, or null when the input could not be decoded. */
    fun toUploadJpeg(bytes: ByteArray): ByteArray? = runCatching {
        val orientation = readOrientation(bytes)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        // Decode at the nearest power-of-two reduction that still leaves the long
        // edge at or above the target, so peak bitmap memory stays bounded.
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= MAX_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val scaled = decoded.let { bmp ->
            val longEdge = maxOf(bmp.width, bmp.height)
            if (longEdge <= MAX_EDGE) {
                bmp
            } else {
                val factor = MAX_EDGE.toFloat() / longEdge
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width * factor).toInt(),
                    (bmp.height * factor).toInt(),
                    true,
                ).also { if (it !== bmp) bmp.recycle() }
            }
        }

        val rotated = rotate(scaled, orientation)
        if (rotated !== scaled) scaled.recycle()

        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        rotated.recycle()
        out.toByteArray()
    }.getOrNull()

    private fun rotate(source: Bitmap, orientation: Int): Bitmap {
        if (orientation == 0) return source
        return Bitmap.createBitmap(
            source, 0, 0, source.width, source.height,
            Matrix().apply { postRotate(orientation.toFloat()) }, true,
        )
    }

    /** EXIF orientation as a rotation in degrees, 0 when absent or normal. */
    private fun readOrientation(bytes: ByteArray): Int = runCatching {
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        when (exif.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)
}
