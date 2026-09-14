package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.CompanyShareRow
import com.medlenx.lab.data.local.DoctorLeaderRow2
import com.medlenx.lab.data.local.MostPrescribedRow
import com.medlenx.lab.data.local.TopBrandRow

/**
 * Ports of the Analytics widget aggregates in `app/database.py`:
 * `get_dashboard_kpis` (900), `get_most_prescribed_medicines` (1014),
 * `get_company_share` (1051), `get_top_doctor_prescribers` (1093).
 *
 * Counts arrive as arguments rather than being queried here, so the arithmetic
 * can be verified without a database.
 *
 * **Two different own-company fallbacks exist in the Python.** These widgets
 * default to `"Square Pharmaceuticals Ltd."`, whereas `get_doctor_tiers` and
 * `get_stewardship_summary` default to `"Healthcare Pharmaceuticals Ltd."`. Both
 * are reproduced as written; on a configured install neither fires.
 */
object AnalyticsMetrics {

    /** The Analytics widgets' fallback — deliberately not [TeamMetrics.DEFAULT_OWN_COMPANY]. */
    const val DEFAULT_OWN_COMPANY = "Square Pharmaceuticals Ltd."

    /** `pct_delta`: a zero baseline reports +100% when there is anything, else 0. */
    fun pctDelta(cur: Int, prev: Int): Double = when {
        prev == 0 -> if (cur != 0) 100.0 else 0.0
        else -> PyMath.round1((cur - prev) * 100.0 / prev)
    }

    fun dashboardKpis(
        totalToday: Int,
        totalWeek: Int,
        totalMonth: Int,
        totalAll: Int,
        scansCur: Int,
        scansPrev: Int,
        itemsTotal: Int,
        ownCount: Int,
        prevItems: Int,
        prevOwn: Int,
        topBrand: TopBrandRow?,
        activeDoctors: Int,
        totalDoctors: Int,
        ownCompanyName: String = "",
    ): DashboardKpis {
        val ownCompany = ownCompanyName.ifBlank { DEFAULT_OWN_COMPANY }
        val marketShare = if (itemsTotal != 0) {
            PyMath.round1(ownCount * 100.0 / itemsTotal)
        } else {
            0.0
        }
        val prevShare = if (prevItems != 0) {
            PyMath.round1(prevOwn * 100.0 / prevItems)
        } else {
            0.0
        }
        return DashboardKpis(
            totalPrescriptions = PrescriptionKpi(
                today = totalToday,
                week = totalWeek,
                month = totalMonth,
                all = totalAll,
                window = scansCur,
                deltaPercent = pctDelta(scansCur, scansPrev),
            ),
            identifiedItems = itemsTotal,
            topBrand = TopBrand(
                brand = topBrand?.brandName ?: "N/A",
                company = topBrand?.companyName.orEmpty(),
                count = topBrand?.count ?: 0,
            ),
            marketShare = MarketShare(
                ownCompany = ownCompany,
                ownCount = ownCount,
                total = itemsTotal,
                percentage = marketShare,
                // Note: a difference of two percentages, not a percent change.
                deltaPercent = PyMath.round1(marketShare - prevShare),
            ),
            doctorCoverage = DoctorCoverage(active = activeDoctors, total = totalDoctors),
        )
    }

    /**
     * Widget A. `market_share_percent` is the brand's share of the *returned
     * page*, not of all prescribing — `total` is summed over the fetched rows
     * only, exactly as the Python does.
     */
    fun mostPrescribed(rows: List<MostPrescribedRow>): List<MostPrescribed> {
        val total = rows.sumOf { it.captureCount }.takeIf { it != 0 } ?: 1
        return rows.map { r ->
            MostPrescribed(
                brandName = r.brandName,
                generic = r.generic,
                manufacturer = r.companyName.orEmpty(),
                captureCount = r.captureCount,
                marketSharePercent = PyMath.round1(r.captureCount * 100.0 / total),
            )
        }
    }

    /**
     * Widget B. Companies below [minPercent] collapse into a single "Others"
     * bucket carrying up to 40 member names.
     */
    fun companyShare(rows: List<CompanyShareRow>, minPercent: Double = 3.0): List<CompanySlice> {
        val total = rows.sumOf { it.count }.takeIf { it != 0 } ?: 1
        val slices = rows.mapNotNull { r ->
            val name = r.companyName
            if (name.isBlank() || name.lowercase().contains("unknown")) return@mapNotNull null
            CompanySlice(
                company = name,
                count = r.count,
                percentage = PyMath.round1(r.count * 100.0 / total),
            )
        }
        val main = slices.filter { it.percentage >= minPercent }.toMutableList()
        val others = slices.filter { it.percentage < minPercent }
        if (others.isNotEmpty()) {
            val cnt = others.sumOf { it.count }
            if (cnt != 0) {
                main += CompanySlice(
                    company = "Others",
                    count = cnt,
                    percentage = PyMath.round1(cnt * 100.0 / total),
                    isOthers = true,
                    members = others.take(40).map { it.company },
                )
            }
        }
        return main
    }

    /**
     * Widget C. `total` is the count of *all* matching doctors, so the caller can
     * render a real "1–10 of N" caption instead of a hardcoded one.
     */
    fun doctorLeaders(
        rows: List<DoctorLeaderRow2>,
        total: Int,
        limit: Int,
        offset: Int,
        ownCompanyName: String = "",
    ): DoctorLeaderboard {
        val ownCompany = ownCompanyName.ifBlank { DEFAULT_OWN_COMPANY }
        val doctors = rows.map { r ->
            val totalCnt = r.totalMeds
            val ownCnt = r.ownMeds
            DoctorLeader(
                doctorName = r.doctorName.ifBlank { "Unknown" },
                chamber = r.chamber.ifBlank { "N/A" },
                specialty = r.specialty.ifBlank { "General" },
                district = r.district,
                territory = r.territory,
                prescriptions = r.prescriptions,
                volumeOwn = ownCnt,
                volumeCompetitor = maxOf(totalCnt - ownCnt, 0),
                total = totalCnt,
                conversionRate = if (totalCnt != 0) {
                    PyMath.round1(ownCnt * 100.0 / totalCnt)
                } else {
                    0.0
                },
            )
        }
        return DoctorLeaderboard(
            doctors = doctors,
            total = total,
            ownCompany = ownCompany,
            limit = limit,
            offset = offset,
        )
    }
}

data class PrescriptionKpi(
    val today: Int,
    val week: Int,
    val month: Int,
    val all: Int,
    val window: Int,
    val deltaPercent: Double,
)

data class TopBrand(val brand: String, val company: String, val count: Int)

data class MarketShare(
    val ownCompany: String,
    val ownCount: Int,
    val total: Int,
    val percentage: Double,
    val deltaPercent: Double,
)

data class DoctorCoverage(val active: Int, val total: Int)

data class DashboardKpis(
    val totalPrescriptions: PrescriptionKpi,
    val identifiedItems: Int,
    val topBrand: TopBrand,
    val marketShare: MarketShare,
    val doctorCoverage: DoctorCoverage,
)

data class MostPrescribed(
    val brandName: String,
    val generic: String,
    val manufacturer: String,
    val captureCount: Int,
    val marketSharePercent: Double,
)

data class CompanySlice(
    val company: String,
    val count: Int,
    val percentage: Double,
    val isOthers: Boolean = false,
    val members: List<String> = emptyList(),
)

data class DoctorLeader(
    val doctorName: String,
    val chamber: String,
    val specialty: String,
    val district: String,
    val territory: String,
    val prescriptions: Int,
    val volumeOwn: Int,
    val volumeCompetitor: Int,
    val total: Int,
    val conversionRate: Double,
)

data class DoctorLeaderboard(
    val doctors: List<DoctorLeader>,
    val total: Int,
    val ownCompany: String,
    val limit: Int,
    val offset: Int,
)
