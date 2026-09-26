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
    /**
     * `prescription_source` — the web's `fSource`.
     *
     * Listed after `days` to mirror `globalFilters`' own field order
     * (`{territory, district, specialty, mr_id, days, source}`), which also keeps
     * the field order stable for anything constructing this positionally.
     */
    val source: String? = null,
) {
    /**
     * How many filters the bar reports as active.
     *
     * Deliberately not the web's count. `activeFilterCount` there tallies only
     * `['territory','district','specialty','mr_id']` — it omits both `days` and
     * `source`, so changing the window to 90 days leaves the badge reading the
     * same as a fresh dashboard. The two agree in the default state (30 days is
     * [DEFAULT_DAYS], so it contributes nothing) and diverge only once a user
     * actually changes the window, which is the moment the badge is worth
     * reading. Keeping that.
     */
    val activeCount: Int
        get() = listOf(district, territory, specialty, mrId, source).count { !it.isNullOrBlank() } +
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
 * All dimensions but one read the prescription row, exactly as `_filter_sql`
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
        " AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)" +
        " AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)"

/**
 * The same fragment for the one endpoint the web backs with the denormalised
 * `recent_scanned_medicines` row instead of a join.
 *
 * `get_recent_scanned_medicines` (`database.py:1329`) filters its own
 * `specialty` column — the value written when the scan was saved — and
 * `/api/export/recent-medicines.csv` is the endpoint that reads it. So the
 * specialty here matches the prescription's captured value rather than the
 * doctor's current profile, and the statement needs no `doctors` join.
 *
 * Kept as a second constant rather than a hand-written clause list so that
 * `agent/roomcheck.py` can compare every statement that filters against a known
 * variant and report one that matches none. The two differ only in the
 * specialty clause; anything else changing in one and not the other is the
 * drift that check exists to catch.
 */
const val RX_FILTER_SQL_SNAPSHOT =
    " AND (:district IS NULL OR :district = '' OR p.district = :district)" +
        " AND (:territory IS NULL OR :territory = '' OR p.territory = :territory)" +
        " AND (:specialty IS NULL OR :specialty = '' OR p.doctor_specialty = :specialty)" +
        " AND (:mrId IS NULL OR :mrId = '' OR p.mr_id = :mrId)" +
        " AND (:source IS NULL OR :source = '' OR p.prescription_source = :source)"

/**
 * The `fSource` options, fixed in the web's markup (`index.html:379`) rather
 * than read from the data, so they are fixed here too. `get_filter_options`
 * does return a `sources` list, but nothing renders it — the two `<option>`s
 * below the "All Sources" entry are written out by hand.
 */
val SOURCE_OPTIONS = listOf("Hospital", "Private Chamber")

/** Distinct values present in the data, for the sheet's dropdowns. */
data class FilterOptions(
    val districts: List<String>,
    val territories: List<String>,
    val specialties: List<String>,
    val mrIds: List<String>,
)
