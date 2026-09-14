package com.medlenx.lab.data.repo

import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/**
 * Python numeric formatting semantics, shared by the ported backend modules.
 *
 * Python's `round()` is half-to-even on the *exact decimal value* of the double.
 * `rint(v * 10) / 10` is not equivalent: `round(0.05, 1)` is `0.1` in Python
 * because 0.05 is actually 0.05000000000000000277…, while scaling first lands
 * exactly on the halfway point and rounds to 0.0. These datasets are prices and
 * percentages, so the difference is visible in the UI.
 */
object PyMath {

    /** `round(x)` — half to even, returned as Int. */
    fun round(v: Double): Int = Math.rint(v).toInt()

    /** `round(x, 1)` */
    fun round1(v: Double): Double =
        BigDecimal(v).setScale(1, RoundingMode.HALF_EVEN).toDouble()

    /** `round(x, 2)` */
    fun round2(v: Double): Double =
        BigDecimal(v).setScale(2, RoundingMode.HALF_EVEN).toDouble()

    /**
     * `f"{x:.2f}"`.
     *
     * Locale.US is explicit: a device in a comma-decimal locale would otherwise
     * render "7,50 BDT" inside an English pitch sentence.
     */
    fun fixed2(v: Double): String = String.format(Locale.US, "%.2f", v)

    /**
     * `f"{x}"` for a float.
     *
     * Kotlin's `Double.toString()` already matches Python for the values in these
     * datasets (7.0 → "7.0", 9.5 → "9.5"). It only diverges on integral JSON
     * integers, which the gazette does not contain — every MRP is written as a
     * float in dgda_prices.json.
     */
    fun pyFloat(v: Double): String = v.toString()
}
