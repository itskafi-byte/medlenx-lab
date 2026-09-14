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
 * the Python's conditional clause building. `doctor_specialty` is the Android
 * equivalent of the backend's `doctors.specialty`.
 */
const val RX_FILTER_SQL =
    " AND (:district IS NULL OR :district = '' OR p.district = :district)" +
        " AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)" +
        " AND (:specialty IS NULL OR :specialty = '' OR p.doctor_specialty = :specialty)" +
        " AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)"

/** Distinct values present in the data, for the sheet's dropdowns. */
data class FilterOptions(
    val districts: List<String>,
    val territories: List<String>,
    val specialties: List<String>,
    val mrIds: List<String>,
)
