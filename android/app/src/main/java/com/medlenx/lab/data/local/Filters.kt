package com.medlenx.lab.data.local

/**
 * The global filter bar's dimensions — `get_filter_options` and `_filter_sql`
 * (database.py:1380 / 1408).
 *
 * Every filter is an optional equality match: a null dimension contributes no
 * clause at all, and non-null ones are AND-ed together, exactly as the Python
 * builds its WHERE fragment. Kotlin `null` plays the role the Python's empty
 * string does.
 */
data class FilterState(
    val district: String? = null,
    val territory: String? = null,
    val specialty: String? = null,
    val mrId: String? = null,
    /** `days` in `_filter_sql`; null is the Python's "all time". */
    val days: Int? = DEFAULT_DAYS,
) {
    val activeCount: Int
        get() = listOf(district, territory, specialty, mrId).count { !it.isNullOrBlank() } +
            (if (days != DEFAULT_DAYS) 1 else 0)

    val isClear: Boolean get() = activeCount == 0

    companion object {
        /** The export opens on "Last 30 Days". */
        const val DEFAULT_DAYS = 30
        val None = FilterState()
    }
}

/**
 * Shared WHERE fragment appended to every filtered query.
 *
 * Room has no dynamic SQL, so the `(:x IS NULL OR col = :x)` idiom stands in for
 * the Python's conditional clause building.
 *
 * Three of the four dimensions read the prescription row, exactly as `_filter_sql`
 * does (`alias="p"`). Specialty is the exception and reads the joined doctor:
 * `_filter_sql` matches `doctor_alias.specialty`, which is `d.specialty`, and
 * every one of its eighteen filtered statements in the backend joins `doctors`.
 * The distinction is not cosmetic. `p.doctor_specialty` is the value captured
 * when that prescription was scanned; `d.specialty` is the doctor's current
 * profile, refreshed on every save. Correcting a doctor's specialty moves their
 * whole history under the new value on the web, and did not here.
 *
 * Because the fragment references `d`, every query that appends it MUST join
 * `doctors` as `d`. That is checked, not remembered: a query using this constant
 * without the join is reported by `agent/roomcheck.py`.
 */
const val RX_FILTER_SQL =
    " AND (:district IS NULL OR :district = '' OR p.district = :district)" +
        " AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)" +
        " AND (:specialty IS NULL OR :specialty = '' OR d.specialty = :specialty)" +
        " AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)"

/** Distinct values present in the data, for the sheet's dropdowns. */
data class FilterOptions(
    val districts: List<String>,
    val territories: List<String>,
    val specialties: List<String>,
    val mrIds: List<String>,
)
