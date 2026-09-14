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
data class StackedDatum(val specialty: String, val values: List<Int>)

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





fun StackedDatum.seriesValues(): List<Int> = values





/** Sort columns on the live-scans table — App.tsx:999. */
val LiveScanSortColumns = listOf("Time", "Doctor", "Medicine", "Company", "Conf.")

/**
 * The chamber chips on the Live Recent Scans card.
 *
 * They were `onClick = { }` in the first build, so they looked selectable but did
 * nothing. They now filter the feed for real against
 * `prescriptions.prescription_source`.
 */
enum class ChamberFilter(val label: String) {
    ALL("All"),
    HOSPITAL("Hospital"),
    PRIVATE("Private Chamber"),
    ;

    /** Case-insensitive substring match, so "Govt. Hospital" still counts. */
    fun matches(prescriptionSource: String): Boolean = when (this) {
        ALL -> true
        HOSPITAL -> prescriptionSource.contains("hospital", ignoreCase = true)
        PRIVATE -> !prescriptionSource.contains("hospital", ignoreCase = true)
    }
}
