package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.DoctorTierRow
import com.medlenx.lab.data.local.StewardshipRow

/**
 * Ports of the RSM aggregates in `app/database.py`.
 *
 * These are re-implementations rather than transliterations: the backend runs
 * them as SQL over `prescribed_medicines` joined to a `doctors` table, whereas
 * Android stores specialty, district and territory directly on the prescription
 * row and has no doctor primary key. The SQL lives in
 * [com.medlenx.lab.data.local.PrescriptionDao]; only the post-query arithmetic
 * is here, so it can be verified without a database.
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
