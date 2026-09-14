package com.medlenx.lab.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Palette ported 1:1 from the web app.
 *
 * Source of truth: the inline `tailwind.config` in templates/index.html plus the
 * `<style>` block and the JS colour constants. Light theme only — the web app
 * deliberately removed dark mode (`<html class="light">`, zero `dark:` classes).
 */
object Mlx {

    // ---- brand ramp (tailwind `brand`) -------------------------------------
    val Brand50 = Color(0xFFF8FAFC)   // screen background (slate-50)
    val Brand100 = Color(0xFFF1F5F9)  // subtle fills, skeleton base
    val Brand200 = Color(0xFFE2E8F0)  // every card / input border
    val Brand300 = Color(0xFFCBD5E1)  // dashed borders, faint chevrons
    val Brand400 = Color(0xFF94A3B8)  // muted icons, empty-state glyphs
    val Brand500 = Color(0xFF2563EB)  // links, focus border
    val Brand600 = Color(0xFF1D4ED8)  // primary action, focus ring
    val Brand700 = Color(0xFF1E40AF)  // section icon accents, hero gradient end
    val Brand800 = Color(0xFF172554)
    val Brand900 = Color(0xFF0F172A)  // text, primary buttons, active nav

    // ---- accent ------------------------------------------------------------
    val Accent50 = Color(0xFFEFF6FF)
    val Accent100 = Color(0xFFDBEAFE)

    // ---- ok (verified / own brand) -----------------------------------------
    val Ok50 = Color(0xFFECFDF5)
    val Ok100 = Color(0xFFD1FAE5)
    /** emerald-500 — the live-scan dot, the verified marker, the >=60% SoV band. */
    val Ok400 = Color(0xFF10B981)
    val Ok500 = Color(0xFF059669)
    val Ok600 = Color(0xFF047857)
    val Ok200 = Color(0xFFA7F3D0)
    val OkDeep = Color(0xFF065F46)

    // ---- warn (needs review) -----------------------------------------------
    val Warn50 = Color(0xFFFFFBEB)
    val Warn100 = Color(0xFFFEF3C7)
    val Warn500 = Color(0xFFD97706)
    val Warn600 = Color(0xFFB45309)
    val Warn200 = Color(0xFFFDE68A)
    /** amber-400 — used once by the Figma export. */
    val Amber400 = Color(0xFFFBBF24)
    /** amber-500 — start of the unverified-company gradient, and the mid SoV band. */
    val Amber500 = Color(0xFFF59E0B)
    val WarnDeep = Color(0xFF92400E)

    // ---- AI-guess band (distinct from warn: orange, not amber) -------------
    val GuessBg = Color(0xFFFFF7ED)
    val GuessBorder = Color(0xFFFDBA74)
    val GuessText = Color(0xFFC2410C)
    val GuessAccent = Color(0xFFEA580C)
    /** orange-500 — gradient end on the unverified-company pill. */
    val GuessSoft = Color(0xFFF97316)
    /** orange-400 — prescription bounding-box stroke and its 20% fill. */
    val GuessLight = Color(0xFFFB923C)

    // ---- danger (duplicate Rx, DGDA ban, off-territory) --------------------
    val Danger = Color(0xFFDC2626)
    val DangerBg = Color(0xFFFEE2E2)
    val DangerBorder = Color(0xFFFCA5A5)
    val DangerText = Color(0xFFB91C1C)
    /** red-800 — the export uses this for the darkest red text, not #B91C1C. */
    val DangerDeep = Color(0xFFB31D1D)
    val DangerSoftBg = Color(0xFFFEF2F2)
    val DangerSoftBorder = Color(0xFFFECACA)
    val Danger500 = Color(0xFFEF4444)

    // ---- violet (own portfolio match, doctor tier A) ------------------------
    val Violet = Color(0xFF7C3AED)
    val VioletBg = Color(0xFFEDE9FE)
    val VioletBorder = Color(0xFFC4B5FD)
    val VioletDeep = Color(0xFF6D28D9)
    val Violet50 = Color(0xFFF5F3FF)
    val Violet100 = Color(0xFFDDD6FE)
    val VioletText = Color(0xFF5B21B6)

    // ---- indigo (alternatives picker) ---------------------------------------
    val Indigo = Color(0xFF4F46E5)
    val IndigoDeep = Color(0xFF4338CA)

    // ---- cyan (scan laser, shimmer) -----------------------------------------
    val Cyan500 = Color(0xFF06B6D4)
    val Cyan600 = Color(0xFF0891B2)
    val Cyan700 = Color(0xFF0284C7)
    val Cyan400 = Color(0xFF38BDF8)
    val Cyan200 = Color(0xFFA5F3FC)

    // ---- blue misc ----------------------------------------------------------
    val Blue300 = Color(0xFF93C5FD)
    val Blue400 = Color(0xFF60A5FA)
    /** blue-200 — the export's most-used missing hex (6 occurrences). */
    val Blue200 = Color(0xFFBFDBFE)
    val Blue100 = Color(0xFFDBEAFE)
    val BlueBg = Color(0xFFEFF6FF)
    val BlueText = Color(0xFF1E40AF)
    val Sky200 = Color(0xFFBAE6FD)

    // ---- slate text ---------------------------------------------------------
    val Text900 = Color(0xFF0F172A)
    val Text700 = Color(0xFF334155)
    val Text600 = Color(0xFF475569)
    val Text500 = Color(0xFF64748B)
    val Text400 = Color(0xFF94A3B8)
    val Text300 = Color(0xFFCBD5E1)

    val Surface = Color(0xFFFFFFFF)
    val Screen = Brand50

    /** Chart.js series palette — CHART_SERIES_COLORS in templates/index.html. */
    val ChartSeries = listOf(
        Color(0xFF1E40AF), Color(0xFF059669), Color(0xFFD97706),
        Color(0xFF0891B2), Color(0xFF7C3AED), Color(0xFFDC2626),
    )

    /** heatmapColor(sov) — territory penetration. */
    fun sovColor(sovPercent: Double): Color = when {
        sovPercent >= 60 -> Color(0xFF10B981)  // own-company dominant
        sovPercent >= 35 -> Color(0xFFF59E0B)  // competitive / mixed
        else -> Color(0xFFEF4444)              // competitor-heavy
    }

    /** hmClusterColor(n) — density clustering of audit points. */
    fun clusterColor(auditCount: Int): Color = when {
        auditCount >= 10 -> Color(0xFF7C3AED)  // hot cluster
        auditCount >= 5 -> Color(0xFF0284C7)
        auditCount >= 2 -> Color(0xFF10B981)
        else -> Color(0xFF94A3B8)              // isolated single audit
    }

    /**
     * getCompanyColor(name) — deterministic, high-contrast fallback used when a
     * manufacturer has no entry in COMPANY_COLOR_PALETTE. Mirrors the constrained
     * HSL in the web app (saturation 62–76%, lightness 38–48%).
     */
    fun companyColor(name: String?): Color {
        if (name.isNullOrBlank()) return Brand400
        val key = name.lowercase()
        COMPANY_PALETTE.entries.firstOrNull { key.contains(it.key) }?.let { return it.value }
        var hash = 0
        for (ch in key) hash = ch.code + ((hash shl 5) - hash)
        val a = Math.abs(hash)
        val hue = a % 360
        val sat = 62 + ((a shr 3) % 14)
        val light = 38 + ((a shr 7) % 10)
        return hslToColor(hue.toFloat(), sat / 100f, light / 100f)
    }

    private val COMPANY_PALETTE = mapOf(
        "square" to Color(0xFF1E40AF),
        "beximco" to Color(0xFF059669),
        "incepta" to Color(0xFFD97706),
        "renata" to Color(0xFF0891B2),
        "aci" to Color(0xFF7C3AED),
        "healthcare" to Color(0xFFDC2626),
        "opsonin" to Color(0xFF0D9488),
        "eskayef" to Color(0xFFBE185D),
    )

    private fun hslToColor(h: Float, s: Float, l: Float): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = h / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color(r1 + m, g1 + m, b1 + m)
    }
}
