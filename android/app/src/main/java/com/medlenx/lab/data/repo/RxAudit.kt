package com.medlenx.lab.data.repo

import com.medlenx.lab.data.model.EnrichedMedicine

/**
 * Prescription Audit Summary helpers, ported from `app/rx_audit.py`.
 *
 * Pure and deterministic: nothing here touches the DB, the network or the UI, so
 * every function stays unit-testable and the drawer's numbers are reproducible.
 *
 * Covers the duplicate-Rx fraud alert ([isDuplicateHash]), the own-vs-competitor
 * footer ([buildMarketShare]) and the two export payloads ([itemsToCsv],
 * [itemsToClipboard]).
 */
object RxAudit {

    /**
     * Two scans whose pHashes differ by this many bits or fewer are the same
     * physical prescription. For a 64-bit DCT pHash, <= 8 bits is the sweet spot for
     * "same image, different capture pipeline" - re-compression, a rotated phone
     * shot, a slightly different crop.
     */
    const val DUPLICATE_THRESHOLD = 8

    /** Mirrors `CSV_HEADERS` in rx_audit.py, including the column order. */
    val CSV_HEADERS = listOf(
        "Brand Name", "Strength", "Dosage Form", "Generic Composition",
        "Pharmaceutical Company", "Confidence %", "Own/Competitor",
    )

    // ------------------------------------------------------- duplicates ----

    /** True when both digests exist and sit within [threshold] bits of each other. */
    fun isDuplicateHash(hexA: String, hexB: String, threshold: Int = DUPLICATE_THRESHOLD): Boolean {
        val distance = PHash.hammingDistance(hexA, hexB) ?: return false
        return distance <= threshold
    }

    // --------------------------------------------------- market share -----

    /**
     * Own-vs-competitor split for the drawer footer.
     *
     * A medicine counts as "own" only when an own company is set *and* matches
     * loosely; with no own company configured everything is a competitor, which is
     * what the web drawer shows before the rep picks a company.
     */
    fun buildMarketShare(medicines: List<EnrichedMedicine>, ownCompany: String): RxMarketShare {
        val ownBrands = mutableListOf<String>()
        val competitorBrands = mutableListOf<String>()
        for (med in medicines) {
            if (ownCompany.isNotBlank() && sameCompanyLoose(med.company, ownCompany)) {
                ownBrands += med.brandName
            } else {
                competitorBrands += med.brandName
            }
        }
        val total = medicines.size
        return RxMarketShare(
            ownCompany = ownCompany,
            totalMedicines = total,
            ownCount = ownBrands.size,
            competitorCount = competitorBrands.size,
            ownSharePct = if (total == 0) 0.0 else round1(ownBrands.size * 100.0 / total),
            competitorSharePct = if (total == 0) 0.0 else round1(competitorBrands.size * 100.0 / total),
            ownBrands = ownBrands,
            competitorBrands = competitorBrands,
        )
    }

    /**
     * Company equality, delegated to [MedicineMatcher.sameCompany].
     *
     * `rx_audit.py` carries its own cheap `same_company_loose` because that module
     * cannot assume the matcher is importable. Kotlin has no such constraint, so this
     * delegates instead: two subtly different notions of "same company" in one app is
     * exactly how the audit drawer ends up disagreeing with the match that produced
     * the row. The matcher's version also strips corporate noise, so
     * "Healthcare Pharmaceuticals Ltd." == "Healthcare Pharmaceuticals".
     */
    fun sameCompanyLoose(a: String?, b: String?): Boolean =
        MedicineMatcher.sameCompany(a, b)

    // -------------------------------------------------------- exports -----

    /**
     * CSV payload for the drawer's "Export Rx Items as CSV" button.
     *
     * Confidence is normalised the way the Python does it - a value <= 1 is treated
     * as a fraction, anything above as an already-percentage number - so both the
     * 0..1 model output and a stray 0..100 value land in the same column.
     */
    fun itemsToCsv(medicines: List<EnrichedMedicine>, ownCompany: String = ""): String =
        buildString {
            // LF, not PyCsv's CRLF default: rx_audit.py:153 passes
            // `lineterminator="\n"`, so this export is the one that differs.
            append(PyCsv.row(CSV_HEADERS, PyCsv.LF))
            for (med in medicines) {
                val own = ownCompany.isNotBlank() && sameCompanyLoose(med.company, ownCompany)
                val row = listOf(
                    med.brandName,
                    med.strength,
                    med.type.ifBlank { med.form },
                    med.genericName,
                    med.company.orEmpty(),
                    pyRound(confidencePercentOf(med)).toString(),
                    if (own) "Own" else "Competitor",
                )
                append(PyCsv.row(row, PyCsv.LF))
            }
        }

    /**
     * Plain-text audit list a field rep can paste into a reporting channel.
     *
     * The em dashes and the "(NN%)" suffix are reproduced verbatim from the web
     * version, and a missing company reads "Unknown Brand" rather than blank.
     */
    fun itemsToClipboard(medicines: List<EnrichedMedicine>, header: String = ""): String =
        buildList {
            if (header.isNotBlank()) add(header)
            medicines.forEachIndexed { i, med ->
                val brand = listOf(
                    med.brandName,
                    med.strength,
                    med.type.ifBlank { med.form },
                ).filter { it.isNotBlank() }.joinToString(" ")
                val company = med.company?.takeIf { it.isNotBlank() } ?: "Unknown Brand"
                add("${i + 1}. $brand — ${med.genericName} — $company (${pyRound(confidencePercentOf(med))}%)")
            }
        }.joinToString("\n")

    // -------------------------------------------------------- internals ----

    /** Delegated to [PyMath] so the rounding semantics live in exactly one place. */
    private fun pyRound(v: Double): Int = PyMath.round(v)

    private fun round1(v: Double): Double = PyMath.round1(v)

    /**
     * `round(conf * 100) if conf <= 1 else round(conf)` — a value at or below 1 is a
     * fraction, anything above is already a percentage. Reproduced exactly, including
     * how a stray negative falls into the fraction branch.
     */
    private fun confidencePercentOf(med: EnrichedMedicine): Double {
        val conf = med.confidence
        return if (conf <= 1.0) conf * 100.0 else conf
    }

    /** Delegated so both exports escape identically — see [PyCsv]. */
    private fun csvEscape(field: String): String = PyCsv.escape(field)
}

/** Own-vs-competitor summary for one prescription. Mirrors `build_market_share`. */
data class RxMarketShare(
    val ownCompany: String,
    val totalMedicines: Int,
    val ownCount: Int,
    val competitorCount: Int,
    val ownSharePct: Double,
    val competitorSharePct: Double,
    val ownBrands: List<String>,
    val competitorBrands: List<String>,
)
