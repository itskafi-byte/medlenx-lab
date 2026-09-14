package com.medlenx.lab.data.repo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.math.BigInteger
import kotlin.math.PI
import kotlin.math.cos

/**
 * DCT-based perceptual image hash, ported from `app/rx_audit.py`.
 *
 * Used to catch the same physical prescription being scanned twice, which is how
 * the Duplicate-Rx fraud alert works. The pipeline is the standard one:
 * grayscale -> 32x32 -> separable 2D DCT-II -> top-left 8x8 low-frequency block
 * -> median threshold with the DC term excluded -> 64-bit hex digest.
 *
 * PARITY NOTE: the digest is *not* byte-identical to the Python one for the same
 * file. Pillow resizes with BICUBIC and `createScaledBitmap` uses bilinear, so the
 * 32x32 planes differ slightly. That is by design tolerable - pHash exists to be
 * stable across capture pipelines, and the 8-bit duplicate threshold absorbs a few
 * differing low-order bits. What matters is that this app is internally consistent,
 * because a standalone app only ever compares hashes it computed itself. Do not
 * compare these digests against hashes stored by the FastAPI backend.
 *
 * Everything is pure and deterministic; nothing touches the network or the DB.
 */
object PHash {

    /** 8x8 hash -> 64 bits. */
    private const val HASH_SIZE = 8

    /** 32x32 DCT input plane. */
    private const val IMG_SIZE = 32

    /**
     * 16-hex-char perceptual digest, or "" on any failure - a scan must never fail
     * because hashing did.
     */
    fun compute(file: File): String = runCatching {
        val scaled = decodeScaled(file) ?: return@runCatching ""
        // decodeScaled() hands us a bitmap we own, and grayscale() reads every pixel
        // eagerly, so it can go straight back to the pool.
        val gray = grayscale(scaled)
        scaled.recycle()

        // Separable 2D DCT: every row first (index = vertical position, value =
        // horizontal frequency), then every column of that result.
        val rowDct = Array(IMG_SIZE) { y -> dct1d(gray[y]) }
        val colDct = Array(IMG_SIZE) { k ->
            dct1d(DoubleArray(IMG_SIZE) { i -> rowDct[i][k] })
        }

        // Top-left 8x8 = lowest frequencies on both axes.
        val flat = DoubleArray(HASH_SIZE * HASH_SIZE)
        var idx = 0
        for (x in 0 until HASH_SIZE) {
            for (y in 0 until HASH_SIZE) flat[idx++] = colDct[x][y]
        }

        // Median of everything except the DC term, per the pHash spec.
        val rest = flat.copyOfRange(1, flat.size).also { it.sort() }
        val median = rest[rest.size / 2]

        var bits = BigInteger.ZERO
        for (c in flat) bits = bits.shiftLeft(1).or(if (c > median) BigInteger.ONE else BigInteger.ZERO)
        bits.toString(16).padStart(16, '0')
    }.getOrDefault("")

    /** Bit distance between two hex digests; null when either is missing or malformed. */
    fun hammingDistance(hexA: String, hexB: String): Int? {
        if (hexA.isBlank() || hexB.isBlank()) return null
        // BigInteger, not toLongOrNull: a 16-hex-char digest overflows a signed Long
        // whenever its first digit is 8 or higher, and Python's int(hex, 16) does not.
        val a = runCatching { BigInteger(hexA, 16) }.getOrNull() ?: return null
        val b = runCatching { BigInteger(hexB, 16) }.getOrNull() ?: return null
        return a.xor(b).bitCount()
    }

    /**
     * Naive O(n^2) DCT-II of one vector - fine for 32-element inputs, and it keeps
     * the arithmetic identical to the Python reference.
     */
    private fun dct1d(vector: DoubleArray): DoubleArray {
        val n = vector.size
        return DoubleArray(n) { k ->
            var total = 0.0
            for (i in 0 until n) {
                total += vector[i] * cos(PI * (2 * i + 1) * k / (2.0 * n))
            }
            total * (if (k == 0) 0.5 else 1.0)
        }
    }

    /**
     * Luminance plane, using the ITU-R 601-2 coefficients Pillow applies for
     * `convert("L")`. Android's own Color.luminance() uses Rec.709, which would
     * shift the hash.
     */
    private fun grayscale(bitmap: Bitmap): Array<DoubleArray> =
        Array(IMG_SIZE) { y ->
            DoubleArray(IMG_SIZE) { x ->
                val px = bitmap.getPixel(x, y)
                0.299 * Color.red(px) + 0.587 * Color.green(px) + 0.114 * Color.blue(px)
            }
        }

    /** Downsamples then scales to 32x32, avoiding a full-size decode of a 12 MP photo. */
    private fun decodeScaled(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (bounds.outWidth / (sample * 2) >= IMG_SIZE &&
            bounds.outHeight / (sample * 2) >= IMG_SIZE
        ) {
            sample *= 2
        }
        val full = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        return if (full.width == IMG_SIZE && full.height == IMG_SIZE) {
            full
        } else {
            Bitmap.createScaledBitmap(full, IMG_SIZE, IMG_SIZE, true).also {
                if (it != full) full.recycle()
            }
        }
    }
}
