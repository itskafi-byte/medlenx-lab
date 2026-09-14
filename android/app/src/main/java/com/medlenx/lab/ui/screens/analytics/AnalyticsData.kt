package com.medlenx.lab.ui.screens.analytics

/**
 * Analytics models and the figures the dashboard renders.
 *
 * These values are the Figma export's own dataset (`barData`, `donutData`,
 * `stackedData`, `sparkData` at App.tsx:976-990, plus the inline `scans` and
 * `recentRx` arrays). They are placeholder analytics, exactly as the export has them:
 * the Room-backed aggregation that replaces them is a later step. Keeping the same
 * numbers means the screen is visually identical to the mock while the data behind it
 * is still being wired.
 */

data class BarDatum(val name: String, val value: Int)

data class DonutDatum(val name: String, val value: Int)

/** One specialty row in the generic-vs-brand stacked matrix. */
data class StackedDatum(
    val specialty: String,
    val square: Int,
    val incepta: Int,
    val beximco: Int,
    val aci: Int,
    val renata: Int,
)

data class DoctorLeaderRow(
    val rank: Int,
    val name: String,
    val meta: String,
    val own: Int,
    val competitor: Int,
    val conversion: Int,
    val rxCount: Int,
    val shareOfVoice: Int,
)

data class LiveScanRow(
    val time: String,
    val doctor: String,
    val specialty: String,
    val brand: String,
    val company: String,
    val verified: Boolean,
    val confidence: Int,
    val location: String,
)

data class RecentRxRow(
    val doctor: String,
    val bmdc: String,
    val meds: Int,
    val area: String,
    val mr: String,
    val duplicate: Boolean,
)

data class KpiDatum(
    val label: String,
    val value: String,
    val delta: String? = null,
    val deltaUp: Boolean = true,
    val today: String? = null,
    val week: String? = null,
    val month: String? = null,
    val track: Int? = null,
)

/** App.tsx:976 — Chart A, most prescribed medicines. */
val BarData = listOf(
    BarDatum("Napa", 148),
    BarDatum("Seclo", 112),
    BarDatum("Amdocal", 94),
    BarDatum("Azithro", 87),
    BarDatum("Pantop", 76),
    BarDatum("Cefixol", 61),
)

/** App.tsx:980 — Chart B, company share of voice. */
val DonutData = listOf(
    DonutDatum("Square Pharma", 38),
    DonutDatum("Incepta", 22),
    DonutDatum("Beximco", 18),
    DonutDatum("ACI Limited", 12),
    DonutDatum("Renata", 7),
    DonutDatum("Others", 3),
)

/** App.tsx:984 — Chart D, generic vs brand matrix by specialty. */
val StackedData = listOf(
    StackedDatum("Cardiology", 45, 20, 15, 10, 10),
    StackedDatum("Gastro", 30, 35, 20, 5, 10),
    StackedDatum("Medicine", 25, 30, 25, 12, 8),
    StackedDatum("Orthopedics", 20, 25, 30, 15, 10),
)

/** The five stacked series, in drawing order, paired with their legend names. */
val StackedSeries = listOf("Square", "Incepta", "Beximco", "ACI", "Renata")

fun StackedDatum.seriesValues(): List<Int> = listOf(square, incepta, beximco, aci, renata)

/** App.tsx:1157 — Widget C, top doctor prescribers. */
val DoctorLeaders = listOf(
    DoctorLeaderRow(1, "Dr. A. K. M. Rahman", "Cardiology • Ibn Sina Chamber • Dhaka", 12, 5, 67, 18, 71),
    DoctorLeaderRow(2, "Dr. Salma Begum", "Gastroenterology • Square Hospital • Dhaka", 9, 7, 56, 14, 56),
    DoctorLeaderRow(3, "Dr. Karim Uddin", "Medicine • Private Chamber • Motijheel", 6, 11, 35, 12, 35),
)

/** App.tsx:993 — every individual medicine detected, per MR. */
val LiveScans = listOf(
    LiveScanRow("2m ago", "Dr. A. K. M. Rahman", "Cardiology", "Napa 500mg Tablet", "Square Pharmaceuticals PLC", true, 96, "Dhanmondi, Dhaka"),
    LiveScanRow("8m ago", "Dr. Salma Begum", "Gastroenterology", "Seclo 20mg Capsule", "Square Pharmaceuticals PLC", true, 88, "Gulshan, Dhaka"),
    LiveScanRow("15m ago", "Dr. Karim Uddin", "Medicine", "Azithro 500mg Tablet", "Incepta Pharmaceuticals", false, 72, "Motijheel, Dhaka"),
    LiveScanRow("22m ago", "Dr. Farida Haque", "Cardiology", "Amdocal 5mg Tablet", "ACI Limited", true, 91, "Dhanmondi, Dhaka"),
    LiveScanRow("31m ago", "Dr. Babul Islam", "Orthopedics", "Cefixol 200mg Capsule", "Beximco Pharmaceuticals", true, 85, "Mirpur, Dhaka"),
)

/** App.tsx:742. */
val RecentRx = listOf(
    RecentRxRow("Dr. A. K. M. Rahman", "A-12345", 6, "Dhanmondi Dhaka", "MR001", false),
    RecentRxRow("Dr. Salma Begum", "B-23456", 4, "Gulshan Dhaka", "MR002", false),
    RecentRxRow("Dr. Karim Uddin", "C-34567", 8, "Motijheel Dhaka", "MR001", true),
)

/** The four summary tiles — App.tsx:1109-1112. */
val SummaryKpis = listOf(
    KpiDatum("Total Captured", "342", "12%", true, today = "0", week = "0", month = "38"),
    KpiDatum("Identified Medicines", "1,284", "8%", true, today = "12", week = "89", month = "412"),
    KpiDatum("Target Share", "38%", "3%", true, track = 38),
    KpiDatum("Active Doctor Coverage", "24", "2", true),
)

/** Sort columns on the live-scans table — App.tsx:999. */
val LiveScanSortColumns = listOf("Time", "Doctor", "Medicine", "Company", "Conf.")
