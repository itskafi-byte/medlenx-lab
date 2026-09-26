package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.ScannedMedicineEntity
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
    fun buildMarketShare(lines: List<RxAuditLine>, ownCompany: String): RxMarketShare {
        val ownBrands = mutableListOf<String>()
        val competitorBrands = mutableListOf<String>()
        for (line in lines) {
            if (ownCompany.isNotBlank() && sameCompanyLoose(line.company, ownCompany)) {
                ownBrands += line.brand
            } else {
                competitorBrands += line.brand
            }
        }
        val total = lines.size
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

    // ----------------------------------------------------- row model -----

    /**
     * Maps a whole live scan onto the drawer's row shape.
     *
     * The audit arithmetic - market share, the CSV payload, the clipboard list - is
     * written once, against [RxAuditLine], because the drawer audits *saved* rows and
     * the Rx Audit screen audits in-memory ones; a second implementation would sooner
     * or later disagree with this one about the same prescription. The adapters that
     * used to do this mapping were `List<EnrichedMedicine>` *overloads* of those three
     * functions, which is legal Kotlin and not legal bytecode: `List<A>` and `List<B>`
     * have one erased JVM signature between them, so each pair drew a
     * `Platform declaration clash ... same JVM signature` error - and only once every
     * frontend error in the same build was fixed, because a failed frontend never
     * reaches the backend that emits that diagnostic.
     */
    fun linesOf(medicines: List<EnrichedMedicine>): List<RxAuditLine> =
        medicines.map { lineOf(it) }

    /** Maps an in-memory scan row onto the audit shape. */
    fun lineOf(med: EnrichedMedicine): RxAuditLine = RxAuditLine(
        brand = med.brandName,
        strength = med.strength,
        type = med.type.ifBlank { med.form },
        dosage = med.dosageNormalized.ifBlank { med.dosage },
        generic = med.genericName,
        company = med.company,
        confidencePercent = confidencePercentOf(med.confidence),
        imageUrl = med.imageUrl,
        nemlListed = med.neml?.listed == true,
        // `m.neml.molecule||m.generic` on the web: the NEML entry is keyed by
        // molecule, so a listed row with no reported molecule falls back to the
        // generic the scan did read.
        nemlMolecule = med.neml?.molecule?.ifBlank { med.genericName }.orEmpty(),
        // The web's item carries `dgda_price_alert.flagged`; the ported alert carries
        // the same verdict plus the sentence explaining it (`main.py:963`).
        dgdaFlagged = med.dgdaAlert?.flagged == true,
        dgdaReason = med.dgdaAlert?.reason.orEmpty(),
        isAntibiotic = med.isAntibiotic,
        broadSpectrum = med.broadSpectrum,
        tripsWatch = med.tripsWatch?.watch == true,
        therapeuticClass = med.therapeuticClass,
    )

    /** Maps a saved `scanned_medicines` row onto the audit shape. */
    fun lineOf(row: ScannedMedicineEntity): RxAuditLine = RxAuditLine(
        brand = row.brandName,
        strength = row.strength,
        type = row.dosageForm,
        dosage = row.dosage,
        generic = row.generic,
        company = row.companyName,
        confidencePercent = confidencePercentOf(row.confidenceScore),
        imageUrl = row.imageUrl,
        nemlListed = row.nemlListed,
        // The saved row keeps the NEML verdict but not the molecule it matched, and the
        // molecule is only ever a tooltip here; the generic is the same value the web
        // falls back to anyway (`m.neml.molecule||m.generic`).
        nemlMolecule = row.generic,
        dgdaFlagged = row.dgdaFlagged,
        // Same story: `scanned_medicines` stores the flag, not the sentence. The drawer
        // shows the pill either way; only its long-press detail is lost.
        dgdaReason = "",
        isAntibiotic = row.isAntibiotic,
        broadSpectrum = row.broadSpectrum,
        tripsWatch = row.tripsWatch,
        therapeuticClass = row.therapeuticClass,
    )

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
    fun itemsToCsv(lines: List<RxAuditLine>, ownCompany: String = ""): String =
        buildString {
            // LF, not PyCsv's CRLF default: rx_audit.py:153 passes
            // `lineterminator="\n"`, so this export is the one that differs.
            append(PyCsv.row(CSV_HEADERS, PyCsv.LF))
            for (line in lines) {
                val own = ownCompany.isNotBlank() && sameCompanyLoose(line.company, ownCompany)
                val row = listOf(
                    line.brand,
                    line.strength,
                    line.type,
                    line.generic,
                    line.company.orEmpty(),
                    line.confidencePercent.toString(),
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
    fun itemsToClipboard(lines: List<RxAuditLine>, header: String = ""): String =
        buildList {
            if (header.isNotBlank()) add(header)
            lines.forEachIndexed { i, line ->
                val brand = listOf(line.brand, line.strength, line.type)
                    .filter { it.isNotBlank() }.joinToString(" ")
                val company = line.company?.takeIf { it.isNotBlank() } ?: "Unknown Brand"
                add("${i + 1}. $brand — ${line.generic} — $company (${line.confidencePercent}%)")
            }
        }.joinToString("\n")

    /**
     * The clipboard header the web builds server-side (`main.py:1086`):
     * `Rx #A-12 - Ahmed - 4 medicines - MR 12`.
     *
     * Lives here rather than at the two call sites because they disagreed with each
     * other: the audit screen passed `"Rx $rxId"` and the drawer passed
     * `"Rx #${rxNo}"`, and neither carried the doctor, the count or the MR that the
     * web's header does. The receipt number keeps this app's own `RX-n` numbering, which
     * is what the screen title above the table already shows.
     */
    fun clipboardHeader(
        rxNo: String,
        doctorName: String,
        count: Int,
        mrId: String?,
    ): String =
        "Rx #$rxNo • ${doctorName.takeIf { it.isNotBlank() } ?: "Unknown"} • " +
            "$count medicines • MR ${mrId?.takeIf { it.isNotBlank() } ?: "-"}"

    // -------------------------------------------------------- internals ----

    /** Delegated to [PyMath] so the rounding semantics live in exactly one place. */
    private fun pyRound(v: Double): Int = PyMath.round(v)

    private fun round1(v: Double): Double = PyMath.round1(v)

    /**
     * `round(conf * 100) if conf <= 1 else round(conf)` — a value at or below 1 is a
     * fraction, anything above is already a percentage. Reproduced exactly, including
     * how a stray negative falls into the fraction branch.
     */
    fun confidencePercentOf(conf: Double): Int =
        pyRound(if (conf <= 1.0) conf * 100.0 else conf)

    /** Delegated so both exports escape identically — see [PyCsv]. */
    private fun csvEscape(field: String): String = PyCsv.escape(field)
}

/**
 * One detected medicine, reduced to what the audit surfaces actually read.
 *
 * The drawer and the Rx Audit screen reach their rows by different routes - one
 * loads `scanned_medicines` for a saved prescription, the other holds the enriched
 * in-memory list - and the two must produce byte-identical CSV, clipboard text and
 * market share for the same prescription. Reducing both to this shape first is what
 * makes that true by construction rather than by review.
 *
 * `type` is the web's `type || form`, already resolved: `scanned_medicines` folds
 * the two into one `dosage_form` column at save time, so a saved row has nothing
 * left to fall back to. `form` is not carried separately for that reason.
 *
 * `isOwn` is deliberately absent. The web drawer recomputes `is_own` from the saved
 * company with the loose matcher (`main.py:947`), and the footer's share uses the
 * same one; reading the entity's stored `isOwn` instead would put a strict
 * `equals` in the row badge and the loose matcher in the footer below it, and the
 * two would disagree on "Square Pharmaceuticals" vs "Square Pharmaceuticals Ltd.".
 */
data class RxAuditLine(
    val brand: String,
    val strength: String,
    val type: String,
    val dosage: String,
    val generic: String,
    val company: String?,
    /** Already normalised to 0..100 - see [confidencePercentOf]. */
    val confidencePercent: Int,
    val imageUrl: String? = null,
    // ── The drawer row's regulatory badges. `main.py:928` attaches every one of these
    //    to the item it returns, and the row renders them inline; they belong on the
    //    line rather than in a second list the UI would have to index in step.
    val nemlListed: Boolean = false,
    val nemlMolecule: String = "",
    val dgdaFlagged: Boolean = false,
    val dgdaReason: String = "",
    val isAntibiotic: Boolean = false,
    val broadSpectrum: Boolean = false,
    val tripsWatch: Boolean = false,
    val therapeuticClass: String? = null,
)

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
