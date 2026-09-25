package com.medlenx.lab.ui.screens.analytics

import com.medlenx.lab.data.local.BrandDoctorRow
import com.medlenx.lab.data.local.DrillCountRow
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

/**
 * One donut segment.
 *
 * `isOthers` marks the synthetic bucket that [com.medlenx.lab.data.repo.AnalyticsMetrics.companyShare]
 * builds by collapsing every company under the minimum share. It carries a real
 * count but is not a company, so it must not be drilled into: querying
 * `company_name = 'Others'` returns nothing at all.
 */
data class DonutDatum(
    val name: String,
    val value: Int,
    val isOthers: Boolean = false,
    /** For [isOthers] only: the companies folded into the bucket. */
    val members: List<String> = emptyList(),
)

/**
 * An open drill-down.
 *
 * Two shapes rather than one, because the web serves them from two different
 * endpoints with two different payloads: `/api/dashboard/company-drilldown`
 * (donut slice click) returns generics + brands + doctors for a company, while
 * `/api/dashboard/brand-doctors` (bar click) returns the doctor table for a
 * brand. Both inherit the filter bar, so both are computed at open time from
 * the live [FilterState] rather than from whatever the last `load()` saw.
 */
sealed interface Drilldown {
    val title: String

    /**
     * A company's breakdown.
     *
     * Two totals on purpose. `topGenericItems` is the sum of the generic counts
     * and is the figure the web reports as the total (`database.py:1197`);
     * `totalItems` is an independent `COUNT(*)` that the web has no equivalent
     * for. They diverge once a company has more distinct generics than
     * [TOP_GENERIC_ROWS], and showing both is what keeps the headline honest
     * without silently disagreeing with the web's number.
     */
    data class Company(
        val company: String,
        val totalItems: Int,
        val topGenericItems: Int,
        val generics: List<DrillCountRow>,
        val brands: List<DrillCountRow>,
        val doctors: List<DrillCountRow>,
    ) : Drilldown {
        override val title: String get() = company
    }

    /**
     * The donut's synthetic "Others" slice, opened.
     *
     * Not a company, so it cannot be drilled into directly — `company_name =
     * 'Others'` matches nothing. Its members can, though: they are the real
     * company names that were folded together to build the slice, each of which
     * has a breakdown of its own.
     */
    data class Bucket(
        val label: String,
        val members: List<String>,
    ) : Drilldown {
        override val title: String get() = label
    }

    companion object {
        /** The web's `limit: int = 10` on `/api/dashboard/company-drilldown`. */
        const val TOP_GENERIC_ROWS = 10
    }

    /** The Doctor | Specialty | Chamber | Times table for one brand. */
    data class Brand(
        val brand: String,
        val doctors: List<BrandDoctorRow>,
    ) : Drilldown {
        override val title: String get() = brand
    }
}

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
    /** Row id in `prescriptions`; used to load the item breakdown on tap. */
    val id: Long = 0,
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
