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
     * `f"{x:.1f}"` — the percentages on the dashboards, the team screen and the exports.
     * The web renders those with `toFixed(1)` (`index.html:4306`).
     *
     * `String.format(Locale.US, "%.1f")` rounds a tie away from zero; Python's `:.1f`
     * takes it to even. That is the one place this is deliberately not a Python
     * transcription: 24.5 formats as "25" here and as "24" in CPython, and JavaScript's
     * `toFixed` agrees with this, not with CPython - which is what matters, because the
     * string being mirrored is the one the web's browser draws.
     */
    fun fixed1(v: Double): String = String.format(Locale.US, "%.1f", v)

    /** `f"{x:.0f}"`, with the same tie rule as [fixed1]. */
    fun fixed0(v: Double): String = String.format(Locale.US, "%.0f", v)

    /**
     * `f"{lat:.4f}, {lng:.4f}"` for the scan's GPS pair.
     *
     * Locale.US for a reason a percentage does not have: on a comma-decimal device the
     * default locale renders "23,8106, 90,4123", which is two coordinates and three
     * commas, and nothing downstream can tell which is which.
     */
    fun fixedCoords(lat: Double, lng: Double): String =
        String.format(Locale.US, "%.4f, %.4f", lat, lng)

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
