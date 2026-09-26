package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.BrandCapturedRow
import com.medlenx.lab.data.local.DoctorTierRow
import com.medlenx.lab.data.local.GeoRegionRow
import com.medlenx.lab.data.local.ScanPointRow
import com.medlenx.lab.data.local.StewardshipRow
import com.medlenx.lab.data.local.TrendItemRow

/**
 * Ports of the RSM aggregates in `app/database.py`.
 *
 * These are re-implementations rather than transliterations: the backend runs
 * them as SQL over `prescribed_medicines` joined to a `doctors` table. Android
 * now has that table too, so specialty, district and territory are read from the
 * joined doctor exactly as the backend reads them. That is not a detail: the
 * prescription row carries its own copy of each, captured at scan time, so
 * reading `p.district` in a query that groups by doctor would take whichever
 * row happened to be scanned first and silently disagree with the web.
 *
 * The SQL lives in [com.medlenx.lab.data.local.PrescriptionDao]; only the
 * post-query arithmetic is here, so it can be verified without a database.
 */
object TeamMetrics {

    /** Python's fallback when no own company is configured. */
    const val DEFAULT_OWN_COMPANY = "Healthcare Pharmaceuticals Ltd."

    /**
     * `get_doctor_tiers`: classify doctors by monthly audit volume and flag
     * at-risk switchers.
     *
     * Tier A >10 Rx, Tier B 4-9, Tier C <=3. At-risk is a high-volume doctor
     * (>=4 Rx) whose own-brand share is below 30% — they are writing competitor
     * brands most of the time, so they are a switch-away risk.
     */
    fun doctorTiers(
        rows: List<DoctorTierRow>,
        ownCompany: String = "",
        days: Int = 30,
        tier: String = "",
    ): DoctorTiering {
        val company = ownCompany.ifBlank { DEFAULT_OWN_COMPANY }

        val doctors = rows.map { r ->
            val items = r.items
            val own = r.ownItems
            val sov = if (items != 0) PyMath.round1(own * 100.0 / items) else 0.0
            DoctorTier(
                doctorName = r.doctorName,
                specialty = r.specialty.ifBlank { "General" },
                district = r.district,
                territory = r.territory,
                rx = r.rx,
                items = items,
                ownItems = own,
                competitorItems = maxOf(items - own, 0),
                sov = sov,
                tier = when {
                    r.rx > 10 -> "A"
                    r.rx >= 4 -> "B"
                    else -> "C"
                },
                atRisk = r.rx >= 4 && sov < 30,
            )
        }

        val filtered = if (tier.isNotBlank()) {
            doctors.filter { it.tier == tier.uppercase() }
        } else {
            doctors
        }
        return DoctorTiering(
            ownCompany = company,
            days = days,
            total = filtered.size,
            doctors = filtered,
        )
    }

    /**
     * `get_stewardship_summary`: per-doctor antibiotic prescribing audit.
     *
     * **Deliberate divergence from the Python.** The original builds `rx_ids`
     * with `d["rx_ids"].add(True)` — the literal `True`, not the prescription
     * id — so `len(rx_ids)` is always 1 and every doctor's "Rx" column reads 1
     * no matter how many prescriptions they wrote. That is a bug, not a
     * definition, so this counts distinct prescriptions properly.
     *
     * Antibiotic classification is called with the generic only, matching the
     * Python; the enrichment path passes generic, category and ingredient, so a
     * molecule identifiable only from its MedEx category will count here as a
     * non-antibiotic.
     */
    fun stewardshipSummary(
        rows: List<StewardshipRow>,
        days: Int = 30,
        limit: Int = 100,
    ): StewardshipSummary {
        data class Acc(
            val doctorName: String,
            val specialty: String,
            val district: String,
            val rxIds: MutableSet<Long> = HashSet(),
            var items: Int = 0,
            var abxItems: Int = 0,
            var broadItems: Int = 0,
            val abxBrands: MutableSet<String> = LinkedHashSet(),
        )

        val docs = LinkedHashMap<String, Acc>()
        for (r in rows) {
            val key = r.doctorName
            val acc = docs.getOrPut(key) {
                Acc(
                    doctorName = r.doctorName,
                    specialty = r.specialty,
                    district = r.district,
                )
            }
            acc.rxIds += r.prescriptionId
            acc.items += 1
            val g = r.generic
            if (Compliance.isAntibiotic(g)) {
                acc.abxItems += 1
                if (r.brandName.isNotBlank()) acc.abxBrands += r.brandName
                if (Compliance.isBroadSpectrum(g)) acc.broadItems += 1
            }
        }

        val all = docs.values.map { d ->
            StewardshipDoctor(
                doctorName = d.doctorName,
                specialty = d.specialty,
                district = d.district,
                rx = d.rxIds.size,
                items = d.items,
                abxItems = d.abxItems,
                broadItems = d.broadItems,
                abxSharePct = if (d.items != 0) {
                    PyMath.round1(d.abxItems * 100.0 / d.items)
                } else {
                    0.0
                },
                abxBrands = d.abxBrands.sorted().take(5),
            )
        }.sortedWith(
            compareByDescending<StewardshipDoctor> { it.abxItems }
                .thenByDescending { it.abxSharePct }
        )

        val itemsTotal = all.sumOf { it.items }
        val abxTotal = all.sumOf { it.abxItems }
        return StewardshipSummary(
            days = days,
            doctors = all.take(limit),
            totals = StewardshipTotals(
                doctorsAudited = all.size,
                doctorsWithAbx = all.count { it.abxItems > 0 },
                items = itemsTotal,
                abxItems = abxTotal,
                broadItems = all.sumOf { it.broadItems },
                abxSharePct = PyMath.round1(abxTotal * 100.0 / maxOf(itemsTotal, 1)),
            ),
        )
    }

    /**
     * `get_scan_points`: point-level scan locations for the density map.
     *
     * A scan with no GPS pin falls back to its district centroid with a
     * deterministic jitter keyed on the prescription id, so district-level
     * audits still spread out instead of stacking on one pixel. Points that
     * still have no coordinates are dropped, exactly as the Python does.
     */
    fun scanPoints(
        rows: List<ScanPointRow>,
        centroids: Map<String, Centroid>,
    ): List<ScanPoint> {
        val out = ArrayList<ScanPoint>(rows.size)
        for (r in rows) {
            var lat = r.lat
            var lng = r.lng
            if (lat == null || lng == null) {
                val c = centroids[r.district]
                if (c != null) {
                    lat = c.lat + ((r.id % 13) - 6) * 0.008
                    lng = c.lng + ((r.id % 7) - 3) * 0.008
                }
            }
            if (lat != null && lng != null) {
                out += ScanPoint(
                    id = r.id,
                    createdAt = r.createdAt,
                    mrId = r.mrId,
                    doctorName = r.doctorName,
                    district = r.district,
                    territory = r.territory,
                    lat = lat,
                    lng = lng,
                    offTerritory = r.offTerritory,
                    duplicate = r.duplicateOf != null,
                    items = r.items,
                )
            }
        }
        return out
    }

    /**
     * `get_geo_heatmap`: prescriptions by district + upazila with own vs
     * competitor counts and market penetration.
     *
     * Missing coordinates fall back to the district centroid. Unlike
     * [scanPoints] there is no jitter here - the Python does not apply any, so
     * same-district regions genuinely stack.
     */
    fun geoHeatmap(
        rows: List<GeoRegionRow>,
        centroids: Map<String, Centroid>,
    ): List<GeoRegion> = rows.map { r ->
        val items = r.items
        val own = r.ownItems
        val c = centroids[r.district]
        GeoRegion(
            district = r.district,
            upazila = r.upazila.ifBlank { r.district },
            territory = r.territory,
            rx = r.rx,
            items = items,
            ownItems = own,
            competitorItems = maxOf(items - own, 0),
            sov = if (items != 0) PyMath.round1(own * 100.0 / items) else 0.0,
            lat = r.lat ?: c?.lat,
            lng = r.lng ?: c?.lng,
        )
    }

    /**
     * `get_rsm_trends`: week-over-week Share-of-Voice series.
     *
     * **Docstring/code mismatch preserved.** The Python docstring says the growth
     * figure is "last full vs previous", but the loop reassigns `growth` on every
     * bucket, so what it actually returns is the final bucket's change over the
     * one before it. Ported as written, not as documented.
     */
    fun rsmTrends(
        rows: List<TrendItemRow>,
        ownCompany: String = "",
        days: Int = 30,
        baseMillis: Long = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000,
    ): RsmTrends {
        val bucketCount = maxOf(1, (days + 6) / 7)
        val company = ownCompany.ifBlank { DEFAULT_OWN_COMPANY }
        val ownToken = company.split(" ").first()

        val own = IntArray(bucketCount)
        val comp = IntArray(bucketCount)
        val rx = IntArray(bucketCount)
        for (r in rows) {
            val diff = Math.floorDiv(r.createdAt - baseMillis, 86_400_000L)
            val b = Math.floorDiv(diff, 7L).toInt()
            if (b < 0 || b >= bucketCount) continue
            rx[b] += 1
            val name = r.companyName.orEmpty()
            if (ownToken.isNotEmpty() && name.lowercase().contains(ownToken.lowercase())) {
                own[b] += 1
            } else {
                comp[b] += 1
            }
        }

        val series = (0 until bucketCount).map { b ->
            val total = own[b] + comp[b]
            TrendWeek(
                week = b + 1,
                label = "W${b + 1}",
                own = own[b],
                competitor = comp[b],
                rx = rx[b],
                sov = if (total != 0) PyMath.round1(own[b] * 100.0 / total) else 0.0,
            )
        }

        var growth = 0.0
        var prev: Int? = null
        for (pt in series) {
            val p = prev
            if (p == null) {
                prev = pt.own
            } else {
                if (p > 0) growth = PyMath.round1((pt.own - p) * 100.0 / p)
                prev = pt.own
            }
        }
        return RsmTrends(
            ownCompany = company,
            days = days,
            bucketCount = bucketCount,
            series = series,
            ownGrowth = growth,
        )
    }

    /**
     * `get_target_progress`: this calendar month's captured Rx against the
     * officer's brand targets.
     *
     * `percent` is capped at 999.0 by the Python; overachievement past that is
     * clipped rather than reported.
     */
    fun targetProgress(
        targets: List<BrandTarget>,
        captured: List<BrandCapturedRow>,
        month: String,
    ): TargetProgress {
        val byBrand = HashMap<String, Int>(captured.size)
        for (c in captured) byBrand[c.brand.lowercase()] = c.captured

        val brands = targets.map { t ->
            val hit = byBrand[t.brand.lowercase()] ?: 0
            val pct = if (t.monthlyTarget != 0) {
                PyMath.round1(hit * 100.0 / t.monthlyTarget)
            } else {
                0.0
            }
            BrandProgress(
                brandName = t.brand,
                monthlyTarget = t.monthlyTarget,
                captured = hit,
                remaining = maxOf(t.monthlyTarget - hit, 0),
                percent = minOf(pct, 999.0),
                month = month,
            )
        }
        return TargetProgress(month = month, brands = brands)
    }
}

data class DoctorTier(
    val doctorName: String,
    val specialty: String,
    val district: String,
    val territory: String,
    val rx: Int,
    val items: Int,
    val ownItems: Int,
    val competitorItems: Int,
    val sov: Double,
    val tier: String,
    val atRisk: Boolean,
)

data class DoctorTiering(
    val ownCompany: String,
    val days: Int,
    val total: Int,
    val doctors: List<DoctorTier>,
)

data class StewardshipDoctor(
    val doctorName: String,
    val specialty: String,
    val district: String,
    val rx: Int,
    val items: Int,
    val abxItems: Int,
    val broadItems: Int,
    val abxSharePct: Double,
    val abxBrands: List<String>,
)

data class StewardshipTotals(
    val doctorsAudited: Int,
    val doctorsWithAbx: Int,
    val items: Int,
    val abxItems: Int,
    val broadItems: Int,
    val abxSharePct: Double,
)

data class StewardshipSummary(
    val days: Int,
    val doctors: List<StewardshipDoctor>,
    val totals: StewardshipTotals,
)

/** District centroid from `bd_geo.json`. */
data class Centroid(val lat: Double, val lng: Double)

data class ScanPoint(
    val id: Long,
    val createdAt: Long,
    val mrId: String,
    val doctorName: String,
    val district: String,
    val territory: String,
    val lat: Double,
    val lng: Double,
    val offTerritory: Boolean,
    val duplicate: Boolean,
    val items: Int,
)

data class TrendWeek(
    val week: Int,
    val label: String,
    val own: Int,
    val competitor: Int,
    val rx: Int,
    val sov: Double,
)

data class RsmTrends(
    val ownCompany: String,
    val days: Int,
    val bucketCount: Int,
    val series: List<TrendWeek>,
    val ownGrowth: Double,
)

data class BrandTarget(val brand: String, val monthlyTarget: Int)

data class BrandProgress(
    val brandName: String,
    val monthlyTarget: Int,
    val captured: Int,
    val remaining: Int,
    val percent: Double,
    val month: String,
)

data class TargetProgress(val month: String, val brands: List<BrandProgress>)

data class GeoRegion(
    val district: String,
    val upazila: String,
    val territory: String,
    val rx: Int,
    val items: Int,
    val ownItems: Int,
    val competitorItems: Int,
    val sov: Double,
    val lat: Double?,
    val lng: Double?,
)
