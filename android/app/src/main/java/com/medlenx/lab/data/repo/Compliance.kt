package com.medlenx.lab.data.repo

import com.medlenx.lab.data.model.DgdaAlert
import com.medlenx.lab.data.model.NemlStatus
import com.medlenx.lab.data.model.RegulatoryData
import com.medlenx.lab.data.model.TripsMolecule
import com.medlenx.lab.data.model.TripsWatch

/**
 * Port of `app/compliance.py` — the clinical & regulatory compliance layer.
 *
 * Everything here is pure and deterministic: no DB, no network, no clock. The
 * datasets arrive as a [RegulatoryData] loaded once by [RegulatoryRepository],
 * mirroring the module-level caches in the Python original.
 *
 * The geofence half of the Python module (`haversine_km`, `resolve_geo_district`,
 * `territory_check`) is already ported as [Geofence] and is deliberately not
 * duplicated here.
 */
object Compliance {

    /**
     * Python's `_norm`: lowercase, replace anything outside `[a-z0-9+ ]` with a
     * space, then collapse runs of whitespace.
     *
     * The `+` is preserved on purpose — "Co-trimoxazole" style names and vitamin
     * notations carry meaning in those characters.
     */
    fun norm(text: String?): String =
        (text ?: "")
            .lowercase()
            .replace(NON_NORMALISED, " ")
            .trim()
            .split(WHITESPACE)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private val NON_NORMALISED = Regex("[^a-z0-9+ ]")
    private val WHITESPACE = Regex("\\s+")

    // ------------------------------------------------------------- NEML ----

    /**
     * Python's `_molecule_key_match`: word-boundary molecule matching that
     * rejects substring collisions.
     *
     * "Dapagliflozin" matches "Dapagliflozin 10 mg" (strength tail) and "Vitamin
     * D" matches "Vitamin D3" (numeric tail), but "Omeprazole" must NOT match
     * "Esomeprazole" — that pair differs by a letter prefix, which makes raw
     * containment unsafe for molecule names.
     */
    fun moleculeKeyMatch(mk: String, key: String, minLen: Int = 5): Boolean {
        if (mk.isEmpty() || key.isEmpty()) return false
        if (mk == key) return true
        if (wordBoundary(mk).containsMatchIn(key)) return mk.length >= minLen
        val m = wordBoundary(key).find(mk)
        if (m != null && key.length >= minLen) {
            val remainder = (mk.substring(0, m.range.first) +
                mk.substring(m.range.last + 1)).trim()
            // Allow only strength-like remainders ("3", "d3", "20 mg").
            return STRENGTH_TAIL.matches(remainder) && remainder.any { it.isDigit() }
        }
        return false
    }

    /** `(?:^| )literal(?: |$)` — a whole-token match, not a substring match. */
    private fun wordBoundary(literal: String) =
        Regex("(?:^| )${Regex.escape(literal)}(?: |$)")

    private val STRENGTH_TAIL = Regex("[a-z0-9+\\- ]*")

    /**
     * Python's `neml_lookup` — the drawer's blue "NEML Listed" pill.
     *
     * Exact index hit first, then a word-boundary scan so "Omeprazole 20 mg"
     * still resolves to the "Omeprazole" entry. The scan is insertion ordered
     * because the Python original iterates a dict built in file order.
     */
    fun nemlLookup(data: RegulatoryData, generic: String = "", ingredient: String = ""): NemlStatus {
        for (raw in listOf(generic, ingredient)) {
            val key = norm(raw)
            if (key.isEmpty()) continue
            data.nemlIndex[key]?.let {
                return NemlStatus(
                    listed = true,
                    molecule = it.molecule.ifEmpty { raw },
                    therapeuticClass = it.`class`,
                )
            }
            for ((mk, m) in data.nemlIndex) {
                if (moleculeKeyMatch(mk, key)) {
                    return NemlStatus(
                        listed = true,
                        molecule = m.molecule,
                        therapeuticClass = m.`class`,
                    )
                }
            }
        }
        return NemlStatus(listed = false, molecule = "", therapeuticClass = null)
    }

    // ------------------------------------------------ therapeutic classes --

    /** Python's `_CLASS_KEYWORDS`. Order matters: first hit wins. */
    val CLASS_KEYWORDS: List<Pair<String, String>> = listOf(
        "antibiot" to "Antibiotics", "macrolide" to "Antibiotics",
        "cephalosporin" to "Antibiotics", "quinolone" to "Antibiotics",
        "penicillin" to "Antibiotics", "antifungal" to "Antibiotics",
        "anthelmint" to "Antibiotics", "antiviral" to "Antibiotics",
        "antiprotozoal" to "Antibiotics", "nitroimidaz" to "Antibiotics",
        "ppi" to "Gastroenterology", "proton pump" to "Gastroenterology",
        "antiulcer" to "Gastroenterology", "antacid" to "Gastroenterology",
        "laxative" to "Gastroenterology", "antiemetic" to "Gastroenterology",
        "antidiarrhoe" to "Gastroenterology", "antispasmod" to "Gastroenterology",
        "digestive" to "Gastroenterology", "carminative" to "Gastroenterology",
        "h2 receptor" to "Gastroenterology",
        "cardiac" to "Cardiology", "antihypertens" to "Cardiology",
        "statin" to "Cardiology", "antiplatelet" to "Cardiology",
        "anticoagulant" to "Cardiology", "antiarrhyth" to "Cardiology",
        "diuretic" to "Cardiology", "beta blocker" to "Cardiology",
        "ace inhibitor" to "Cardiology", "nitrate" to "Cardiology",
        "antidiabet" to "Endocrinology", "insulin" to "Endocrinology",
        "thyroid" to "Endocrinology", "corticosteroid" to "Endocrinology",
        "steroid" to "Endocrinology", "hormone" to "Endocrinology",
        "bronchodilat" to "Respiratory", "asthma" to "Respiratory",
        "expectorant" to "Respiratory", "cough" to "Respiratory",
        "antihistamine" to "Antihistamines", "antiallerg" to "Antihistamines",
        "antidepress" to "Neurology & Psychiatry",
        "antipsychot" to "Neurology & Psychiatry",
        "antiepilept" to "Neurology & Psychiatry",
        "benzodiazep" to "Neurology & Psychiatry",
        "neuropath" to "Neurology & Psychiatry", "sedative" to "Neurology & Psychiatry",
        "analgesic" to "Analgesics & Antipyretics",
        "antipyret" to "Analgesics & Antipyretics",
        "nsaid" to "Analgesics & Antipyretics", "opioid" to "Analgesics & Antipyretics",
        "nsaids" to "Analgesics & Antipyretics",
        "vitamin" to "Vitamins & Minerals", "mineral" to "Vitamins & Minerals",
        "haematin" to "Vitamins & Minerals", "supplement" to "Vitamins & Minerals",
        "antioxidant" to "Vitamins & Minerals",
        "antigout" to "Other", "muscle relax" to "Other",
        "ophthalmic" to "Other", "eye" to "Other", "derma" to "Other",
        "topical" to "Other", "contracept" to "Other", "oncology" to "Other",
        "anticancer" to "Other", "immunosuppress" to "Other",
    )

    /** Broad-spectrum / higher-priority stewardship molecules (AWaRe "Watch"). */
    val BROAD_SPECTRUM_KEYWORDS: List<String> = listOf(
        "azithromycin", "clarithromycin", "cefixime", "ceftriaxone", "cefuroxime",
        "cefotaxime", "ceftazidime", "cefepime", "ciprofloxacin", "levofloxacin",
        "moxifloxacin", "ofloxacin", "gemifloxacin", "meropenem", "imipenem",
        "clavulanic", "pipemidic", "norfloxacin", "cefpodoxime", "faropenem",
        "teicoplanin", "vancomycin", "colistin", "linezolid", "tigecycline",
    )

    val ANTIBIOTIC_KEYWORDS: List<String> = BROAD_SPECTRUM_KEYWORDS + listOf(
        "amoxicillin", "ampicillin", "cloxacillin", "penicillin", "erythromycin",
        "cephalexin", "cephradine", "doxycycline", "tetracycline", "metronidazole",
        "secnidazole", "tinidazole", "clindamycin", "trimethoprim", "sulphameth",
        "co-trimoxazole", "nitrofurantoin", "chloramphenicol", "gentamicin",
        "amikacin", "neomycin", "fluconazole", "ketoconazole", "itraconazole",
        "acyclovir", "albendazole", "mebendazole", "ivermectin", "antibiot",
        "antifungal", "antiviral", "anthelmint", "antiprotozoal",
    )

    /** Stewardship detection on the molecule text (generic/ingredient/category). */
    fun isAntibiotic(generic: String = "", category: String = "", ingredient: String = ""): Boolean {
        val blob = norm(listOf(generic, category, ingredient).joinToString(" "))
        return ANTIBIOTIC_KEYWORDS.any { blob.contains(it) }
    }

    fun isBroadSpectrum(generic: String = "", ingredient: String = ""): Boolean {
        val blob = norm(listOf(generic, ingredient).joinToString(" "))
        return BROAD_SPECTRUM_KEYWORDS.any { blob.contains(it) }
    }

    /**
     * One human label for the therapy-breakdown bar.
     *
     * Order: NEML index class → MedEx category keywords → molecule keywords →
     * "Other".
     */
    fun resolveTherapeuticClass(
        data: RegulatoryData,
        generic: String = "",
        category: String = "",
        ingredient: String = "",
    ): String {
        val neml = nemlLookup(data, generic, ingredient)
        if (neml.listed && !neml.therapeuticClass.isNullOrEmpty()) return neml.therapeuticClass
        val blob = norm(listOf(category, generic, ingredient).joinToString(" "))
        for ((kw, label) in CLASS_KEYWORDS) {
            if (blob.contains(kw)) return label
        }
        return "Other"
    }

    // -------------------------------------------------- polypharmacy ------

    const val POLYPHARMACY_HIGH = 8
    const val POLYPHARMACY_MODERATE = 5

    /** WHO-style polypharmacy banding for the drawer's top-level badge. */
    fun polypharmacyIndex(itemCount: Int): Polypharmacy {
        val n = itemCount
        return when {
            n >= POLYPHARMACY_HIGH -> Polypharmacy(
                count = n, level = "high",
                label = "⚠️ $n+ Meds Prescribed — High Polypharmacy",
            )
            n >= POLYPHARMACY_MODERATE -> Polypharmacy(
                count = n, level = "moderate",
                label = "$n Meds Prescribed — Moderate Polypharmacy",
            )
            else -> Polypharmacy(count = n, level = "normal", label = "$n Meds Prescribed")
        }
    }

    // -------------------------------------------- DGDA price ceiling ------

    /**
     * The red "DGDA Price Alert" evaluation for one drawer row.
     *
     * Combines what the Python splits across `intelligence.dgda_check` (the
     * gazette lookup) and `compliance.price_ceiling_alert` (the verdict), since
     * the Kotlin [DgdaAlert] model carries both in one value.
     *
     * Flagged when the brand is banned, its MRP was ceiling-adjusted, or a price
     * captured in the scan exceeds the gazette ceiling. Brands absent from the
     * gazette get a mild "unverified" note, not a red flag — gazette coverage is
     * partial.
     */
    fun priceCeilingAlert(
        dgda: DgdaStatus,
        detectedMrp: Double? = null,
    ): DgdaAlert {
        when (dgda.status) {
            "banned" -> return DgdaAlert(
                flagged = true,
                status = "banned",
                reason = dgda.concern?.ifBlank { null } ?: "Banned per DGDA notification",
                ceilingPrice = dgda.priceMrp,
            )
            "price_adjusted" -> return DgdaAlert(
                flagged = true,
                status = "price_adjusted",
                reason = dgda.concern?.ifBlank { null }
                    ?: "MRP revised under DGDA ceiling price notification",
                ceilingPrice = dgda.priceMrp,
            )
        }
        val ceiling = dgda.priceMrp
        if (detectedMrp != null && ceiling != null) {
            // The 1e-9 epsilon is the Python original's: MRP equality must not
            // flag on a float comparison of two values parsed from JSON.
            if (detectedMrp > ceiling + 1e-9) {
                return DgdaAlert(
                    flagged = true,
                    status = "ceiling_exceeded",
                    reason = "Detected MRP ${PyMath.pyFloat(detectedMrp)} BDT exceeds the " +
                        "DGDA ceiling ${PyMath.pyFloat(ceiling)} BDT",
                    ceilingPrice = ceiling,
                )
            }
        }
        if (dgda.status == "unknown") {
            return DgdaAlert(
                flagged = false,
                status = "unverified",
                reason = "Not found in the DGDA MRP gazette — pricing unverified",
                ceilingPrice = null,
            )
        }
        return DgdaAlert(
            flagged = false,
            status = "ok",
            reason = if (ceiling != null) {
                "Compliant with gazette MRP (${PyMath.pyFloat(ceiling)} BDT)"
            } else {
                "Compliant"
            },
            ceilingPrice = ceiling,
        )
    }

    // ------------------------------------------------ therapy breakdown ---

    /** Python's `THERAPY_COLORS`. */
    val THERAPY_COLORS: Map<String, Long> = mapOf(
        "Antibiotics" to 0xFFDC2626,
        "Cardiology" to 0xFF1E40AF,
        "Gastroenterology" to 0xFF0D9488,
        "Endocrinology" to 0xFF7C3AED,
        "Respiratory" to 0xFF0284C7,
        "Antihistamines" to 0xFFD97706,
        "Neurology & Psychiatry" to 0xFFDB2777,
        "Analgesics & Antipyretics" to 0xFF65A30D,
        "Vitamins & Minerals" to 0xFFCA8A04,
        "Other" to 0xFF64748B,
    )

    /** Percent split across therapeutic classes for the stacked bar. */
    fun therapyBreakdown(classes: List<String>): List<TherapySlice> {
        val total = classes.size
        if (total == 0) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        for (c in classes) counts[c] = (counts[c] ?: 0) + 1
        // Python sorts by (-count, class) — descending count, then the label.
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { (cls, n) ->
                TherapySlice(
                    therapeuticClass = cls,
                    count = n,
                    pct = PyMath.round1(n * 100.0 / total),
                    color = THERAPY_COLORS[cls] ?: 0xFF64748B,
                )
            }
    }

    // -------------------------------------------------- TRIPS waiver ------

    /**
     * Python's `trips_lookup` — PMD portfolio tracker entry for a molecule.
     *
     * Returns `watch = false` when the molecule is not on the list.
     */
    fun tripsLookup(
        data: RegulatoryData,
        generic: String = "",
        ingredient: String = "",
    ): TripsWatch {
        for (raw in listOf(generic, ingredient)) {
            val key = norm(raw)
            if (key.isEmpty()) continue
            data.tripsIndex[key]?.let { return it.toWatch() }
            for ((mk, m) in data.tripsIndex) {
                if (moleculeKeyMatch(mk, key)) return m.toWatch()
            }
        }
        return TripsWatch(watch = false, molecule = "", originator = "", watchLevel = "")
    }

    private fun TripsMolecule.toWatch() = TripsWatch(
        watch = true,
        molecule = molecule,
        originator = originator,
        watchLevel = watchLevel,
    )

    /** LDC pharmaceutical waiver expiry (2033-01-01) for display. */
    fun tripsExpiry(data: RegulatoryData): String =
        data.trips.waiverExpiry.split(" ").firstOrNull() ?: ""

    // --------------------------------------- MPO pitch evidence notes -----

    /**
     * Bioequivalence + dosage-advantage lines for the Doctor Pitch Card.
     *
     * Factual only: derived from the MedEx catalogue comparison (same molecule,
     * matched strength/form), never invented clinical claims.
     */
    fun substitutionEvidenceNotes(
        generic: String,
        competitorStrength: String,
        competitorType: String,
        ownStrength: String,
        ownType: String,
    ): EvidenceNotes {
        val bioequiv = if (generic.isNotEmpty()) {
            "Both products are DGDA-registered formulations of the same molecule " +
                "($generic) — therapeutic substitutes under the DGDA generic " +
                "substitution framework."
        } else {
            "Both products are DGDA-registered formulations of the same molecule — " +
                "therapeutic substitutes under the DGDA generic substitution framework."
        }
        val cs = norm(competitorStrength)
        val ct = norm(competitorType)
        val os = norm(ownStrength)
        val ot = norm(ownType)
        val dosage = when {
            cs.isNotEmpty() && os.isNotEmpty() && cs == os ->
                if (ct.isNotEmpty() && ot.isNotEmpty() && ct == ot) {
                    "Identical strength ($competitorStrength) and dosage form " +
                        "($competitorType) — no dose titration needed when switching."
                } else {
                    "Identical strength ($competitorStrength) — same dose, different " +
                        "pack form; switching needs no titration."
                }
            cs.isNotEmpty() && os.isNotEmpty() ->
                "Strength differs ($competitorStrength vs $ownStrength) — confirm the " +
                    "equivalent dose and titrate before switching the patient."
            else -> "Confirm pack-strength equivalence before switching."
        }
        return EvidenceNotes(bioequiv = bioequiv, dosageAdvantage = dosage)
    }
}

/** Python's `polypharmacy_index` return shape. */
data class Polypharmacy(val count: Int, val level: String, val label: String)

/** One slice of Python's `therapy_breakdown`. */
data class TherapySlice(
    val therapeuticClass: String,
    val count: Int,
    val pct: Double,
    val color: Long,
)

/** Python's `substitution_evidence_notes` return shape. */
data class EvidenceNotes(val bioequiv: String, val dosageAdvantage: String)

/**
 * The gazette verdict produced by [Intelligence.dgdaCheck], consumed by
 * [Compliance.priceCeilingAlert].
 */
data class DgdaStatus(
    /** ok | price_adjusted | banned | unknown */
    val status: String,
    val severity: String,
    val concern: String? = null,
    val flagType: String? = null,
    val priceMrp: Double? = null,
    val pricePack: String = "",
    val priceStrength: String = "",
    val oldMrp: Double? = null,
    val newMrp: Double? = null,
)
