package com.medlenx.lab.data.remote

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** Prepare temporary, bounded JPEG uploads without changing the saved source image. */
object ImagePrep {

    private const val MAX_EDGE = 800
    private const val JPEG_QUALITY = 70

    /** Dimensions for either a full image or a crop; never enlarge. */
    internal fun uploadDimensions(width: Int, height: Int): Pair<Int, Int> {
        require(width > 0 && height > 0)
        val longEdge = maxOf(width, height)
        if (longEdge <= MAX_EDGE) return width to height
        val scale = MAX_EDGE.toDouble() / longEdge
        return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
    }

    /** Resize the full image for upload; do not recycle the caller's bitmap. */
    fun bitmapUploadJpeg(source: Bitmap): ByteArray {
        val (width, height) = uploadDimensions(source.width, source.height)
        val upload = if (width == source.width && height == source.height) source
            else Bitmap.createScaledBitmap(source, width, height, true)
        try {
            return jpegBytes(upload)
        } finally {
            if (upload !== source) upload.recycle()
        }
    }

    /** Draw a selected region directly into a bounded bitmap, avoiding a full-sized crop. */
    fun cropUploadJpeg(source: Bitmap, box: Rect): ByteArray {
        require(box.left >= 0 && box.top >= 0 && box.right <= source.width && box.bottom <= source.height && !box.isEmpty)
        val (width, height) = uploadDimensions(box.right - box.left, box.bottom - box.top)
        val upload = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            Canvas(upload).drawBitmap(source, box, Rect(0, 0, width, height), Paint(Paint.FILTER_BITMAP_FLAG))
            return jpegBytes(upload)
        } finally {
            upload.recycle()
        }
    }

    private fun jpegBytes(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "JPEG encoding failed" }
        output.toByteArray()
    }

    /** Re-encoded upload bytes, or null when the input could not be decoded. */
    fun toUploadJpeg(bytes: ByteArray, rotationDegrees: Int? = null): ByteArray? = runCatching {
        val orientation = rotationDegrees ?: readOrientation(bytes)

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
                    (bmp.width * factor).toInt().coerceAtLeast(1),
                    (bmp.height * factor).toInt().coerceAtLeast(1),
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
