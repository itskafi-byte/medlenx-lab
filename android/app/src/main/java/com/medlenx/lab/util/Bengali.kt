package com.medlenx.lab.util

/**
 * Bengali numeral and unit normalisation.
 *
 * Direct port of MedLenXVLClient.normalize_bengali_dosage(): converts ০-৯ to 0-9 and
 * translates চামচ -> spoon and চা -> tea, so "১+০+১" becomes "1+0+1".
 */
object Bengali {

    private const val BN_DIGITS = "০১২৩৪৫৬৭৮৯"

    fun normalizeDosage(text: String?): String {
        if (text.isNullOrBlank()) return ""
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val idx = BN_DIGITS.indexOf(ch)
            sb.append(if (idx >= 0) idx.toString() else ch.toString())
        }
        return sb.toString()
            .replace("চামচ", "spoon")
            .replace("চা", "tea")
            .trim()
    }
}
