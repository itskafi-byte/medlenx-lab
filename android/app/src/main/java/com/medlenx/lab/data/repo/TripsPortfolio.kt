package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.ScannedItemRow
import com.medlenx.lab.data.model.TripsMolecule

/**
 * Port of `database.get_trips_portfolio` — field-volume trends for TRIPS-waiver
 * watch molecules.
 *
 * The backend runs this as a SQL join over `prescribed_medicines` and
 * `prescriptions`; here the join is done by [com.medlenx.lab.data.local.PrescriptionDao.scannedSince]
 * and this performs the aggregation, so the numbers are this device's own scans
 * rather than a shared server's.
 */
object TripsPortfolio {

    /** Python's `level_rank`: critical first, unknown levels last. */
    private val LEVEL_RANK = mapOf("critical" to 0, "high" to 1, "medium" to 2, "low" to 3)

    fun build(
        tripsIndex: Map<String, TripsMolecule>,
        molecules: List<TripsMolecule>,
        rows: List<ScannedItemRow>,
        /** Epoch millis separating the current window from the previous one. */
        boundary: Long,
        days: Int,
        waiverExpiry: String,
        ldcGraduation: String,
        context: String,
    ): TripsPortfolioResult {

        data class Acc(
            var current: Int = 0,
            var prev: Int = 0,
            val territories: MutableMap<String, Int> = LinkedHashMap(),
            val brands: MutableSet<String> = LinkedHashSet(),
            val meta: TripsMolecule,
        )

        val stats = LinkedHashMap<String, Acc>()
        for (row in rows) {
            val g = row.generic.trim()
            if (g.isEmpty()) continue

            // Asymmetric on purpose, exactly as in the Python: the index keys were
            // built with Compliance.norm (punctuation stripped), but the lookup key
            // is only lowercased and whitespace-collapsed.
            val gk = Intelligence.normaliseKey(g)
            var entry: TripsMolecule? = null
            for ((mk, m) in tripsIndex) {
                if (Compliance.moleculeKeyMatch(mk, gk)) {
                    entry = m
                    break
                }
            }
            val hit = entry ?: continue

            val acc = stats.getOrPut(hit.molecule) { Acc(meta = hit) }
            if (row.createdAt >= boundary) acc.current++ else acc.prev++

            val terr = row.territory.ifBlank { row.district }.ifBlank { "Unknown" }
            acc.territories[terr] = (acc.territories[terr] ?: 0) + 1
            if (row.brandName.isNotBlank()) acc.brands += row.brandName
        }

        val out = mutableListOf<TripsMoleculeVolume>()
        for ((key, st) in stats) {
            val delta = st.current - st.prev
            out += TripsMoleculeVolume(
                molecule = key,
                therapeuticClass = st.meta.`class`,
                originator = st.meta.originator,
                watchLevel = st.meta.watchLevel.ifBlank { "medium" },
                note = st.meta.note,
                currentVolume = st.current,
                prevVolume = st.prev,
                delta = delta,
                // null when there is no previous period to compare against,
                // matching Python's `if st["prev"] else None`.
                deltaPct = if (st.prev != 0) PyMath.round1(delta * 100.0 / st.prev) else null,
                topTerritories = st.territories.entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .map { TerritoryVolume(it.key, it.value) },
                brands = st.brands.sorted().take(5),
            )
        }

        // Molecules with no field volume yet still appear, in dataset order, so
        // the PMD sees the whole watch list rather than only what has been scanned.
        val seen = out.map { it.molecule }.toSet()
        for (m in molecules) {
            if (m.molecule in seen) continue
            out += TripsMoleculeVolume(
                molecule = m.molecule,
                therapeuticClass = m.`class`,
                originator = m.originator,
                watchLevel = m.watchLevel.ifBlank { "medium" },
                note = m.note,
                currentVolume = 0,
                prevVolume = 0,
                delta = 0,
                deltaPct = null,
                topTerritories = emptyList(),
                brands = emptyList(),
            )
        }

        out.sortWith(
            compareBy(
                { LEVEL_RANK[it.watchLevel] ?: 9 },
                { -it.currentVolume },
                { it.molecule },
            )
        )

        return TripsPortfolioResult(
            waiverExpiry = waiverExpiry,
            ldcGraduation = ldcGraduation,
            context = context,
            days = days,
            molecules = out,
            totals = TripsTotals(
                watched = out.size,
                withFieldVolume = out.count { it.currentVolume != 0 },
                volume = out.sumOf { it.currentVolume },
                rising = out.count { it.delta > 0 },
            ),
        )
    }
}

data class TerritoryVolume(val name: String, val count: Int)

data class TripsMoleculeVolume(
    val molecule: String,
    val therapeuticClass: String,
    val originator: String,
    val watchLevel: String,
    val note: String,
    val currentVolume: Int,
    val prevVolume: Int,
    val delta: Int,
    val deltaPct: Double?,
    val topTerritories: List<TerritoryVolume>,
    val brands: List<String>,
)

data class TripsTotals(
    val watched: Int,
    val withFieldVolume: Int,
    val volume: Int,
    val rising: Int,
)

data class TripsPortfolioResult(
    val waiverExpiry: String,
    val ldcGraduation: String,
    val context: String,
    val days: Int,
    val molecules: List<TripsMoleculeVolume>,
    val totals: TripsTotals,
)
