package com.medlenx.lab.data.repo

import com.medlenx.lab.data.model.MedexProduct
import java.text.Normalizer

/**
 * Medicine <-> MedEx catalogue matcher, ported from `app/medicine_matcher.py`.
 *
 * This module exists to fix the "detected medicine shows the wrong company" bug, and
 * the three root causes it addresses are worth keeping visible, because each one is a
 * trap that a naive re-implementation falls straight back into:
 *
 *  1. Indexing as `{brand.lower(): entry}` keeps only the LAST row for each brand. In
 *     the shipped MedEx dump that discarded 8,819 of 25,105 rows, so "Napa 500 mg
 *     Tablet" resolved to the *Napa Syrup* row, and for the 29 brand names sold by
 *     more than one company it returned a different manufacturer entirely. Hence
 *     [MedexIndex] maps one brand to **all** of its variants and never collapses.
 *  2. Taking the company as `model.company ?: catalogue.company` let the vision
 *     model's guess outrank the authoritative catalogue - and the VL prompt actively
 *     nudges it to guess. Hence [resolveCompany] always prefers the catalogue.
 *  3. A flat `+0.2` substring bonus with no length sanity check let a 3-letter
 *     catalogue brand such as "Epa" beat the real "Napa". Hence the length guard, the
 *     first-letter rule and the bounded bonuses in [MedexIndex.fuzzy].
 */
object MedicineMatcher {

    /**
     * Dosage-form words the OCR / vision model frequently glues onto the brand name
     * ("Tab. Napa", "Napa Syrup", "Cap Seclo 20mg").
     */
    private val FORM_WORDS = setOf(
        "tab", "tabs", "tablet", "tablets", "cap", "caps", "capsule", "capsules",
        "syr", "syrup", "susp", "suspension", "inj", "injection", "iv", "infusion",
        "cream", "oint", "ointment", "gel", "lotion", "drop", "drops", "eye",
        "ear", "nasal", "spray", "inhaler", "inhalation", "puff", "sachet",
        "powder", "vial", "ampoule", "amp", "suppository", "supp", "solution",
        "soln", "sol", "nebuliser", "nebulizer", "chewable", "er", "sr", "xr",
        "cr", "la", "plus", "forte",
    )

    /** Strength tokens: 500mg, 20 mg, 120mg/5ml, 10ml, 2.5%, 40 mg/vial. */
    private val STRENGTH_RE = Regex(
        "\\d+(?:\\.\\d+)?\\s*(?:%|mcg|microgram|mg|gm?|g|ml|l|iu|unit|units)" +
            "(?:\\s*/\\s*\\d*(?:\\.\\d+)?\\s*(?:ml|l|vial|amp|dose|puff|gm?|g))?",
        RegexOption.IGNORE_CASE,
    )

    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val LEADING_NUMBER = Regex("^[\\d.]+")
    private val WHITESPACE = Regex("\\s+")

    /** Bangla digits, which appear throughout the MedEx dump. */
    private val BN_DIGITS = "০১২৩৪৫৬৭৮৯"

    /** Corporate suffixes that carry no identity ("Square Pharmaceuticals Ltd."). */
    private val COMPANY_NOISE = Regex(
        "\\b(ltd|limited|plc|inc|co|company|pharmaceuticals?|pharma|laboratories|" +
            "labs?|industries|healthcare|health\\s*care|bd|bangladesh)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** Character differences between two equal-length strings. */
    fun hamming(a: String, b: String): Int {
        if (a.length != b.length) return maxOf(a.length, b.length)
        var diff = 0
        for (i in a.indices) if (a[i] != b[i]) diff++
        return diff
    }

    /** Strips accents and diacritics so "Rosuvas" == "Rosuvás". */
    fun asciiFold(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}"), "")

    private fun foldDigits(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            val idx = BN_DIGITS.indexOf(ch)
            out.append(if (idx >= 0) ('0' + idx) else ch)
        }
        return out.toString()
    }

    /**
     * Canonical form of a brand name for matching.
     *
     * 'Tab. Napa 500mg'  -> 'napa'
     * 'Cap  Seclo-20 mg' -> 'seclo'
     * 'SECLO®'           -> 'seclo'
     */
    fun normalizeBrand(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = asciiFold(raw).let { foldDigits(it) }.lowercase()
            .replace("®", " ").replace("™", " ")
        text = STRENGTH_RE.replace(text, " ")
        text = NON_ALNUM.replace(text, " ")
        val tokens = text.split(WHITESPACE).filter { it.isNotEmpty() }.toMutableList()

        // Drop leading/trailing dosage-form noise, but never drop everything.
        while (tokens.isNotEmpty() && tokens.first() in FORM_WORDS) tokens.removeAt(0)
        while (tokens.isNotEmpty() && tokens.last() in FORM_WORDS) tokens.removeAt(tokens.size - 1)

        // The brand *was* the form word (rare) - fall back to the raw tokens.
        if (tokens.isEmpty()) {
            tokens.addAll(
                NON_ALNUM.replace(text, " ").split(WHITESPACE).filter { it.isNotEmpty() },
            )
        }
        // Drop bare numeric tokens ("napa 500" -> "napa").
        val trimmed = tokens.filter { !it.all(Char::isDigit) }
        return (trimmed.ifEmpty { tokens }).joinToString(" ").trim()
    }

    /** '500 mg' / '500mg' / '৫০০ mg' -> '500mg'; '120 mg/5 ml' -> '120mg/5ml'. */
    fun normalizeStrength(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return foldDigits(raw).lowercase()
            .replace(WHITESPACE, "")
            .replace("microgram", "mcg")
            .replace("µg", "mcg")
    }

    /** Maps every spelling of a dosage form onto one canonical bucket. */
    fun normalizeForm(raw: String?): String {
        val text = (raw.orEmpty()).lowercase()
        // Order matters: the most specific buckets are checked first.
        val buckets = listOf(
            "eye_drop" to listOf("eye drop", "eye/ear", "ophthalmic", "eye "),
            "nasal" to listOf("nasal", "nose"),
            "inhaler" to listOf("inhaler", "inhalation", "puff", "hfa", "nebul"),
            "injection" to listOf(
                "injection", "inj", " iv", "iv ", "infusion", "vial",
                "ampoule", "amp",
            ),
            "suppository" to listOf("suppository", "supp"),
            "syrup" to listOf("syrup", "syr", "suspension", "susp", "elixir", "oral solution"),
            "drop" to listOf("drop", "paediatric drop", "pediatric drop"),
            "cream" to listOf("cream"),
            "ointment" to listOf("ointment", "oint"),
            "gel" to listOf("gel"),
            "lotion" to listOf("lotion"),
            "spray" to listOf("spray"),
            "powder" to listOf("powder", "sachet", "granule"),
            "capsule" to listOf("capsule", "cap"),
            "tablet" to listOf("tablet", "tab"),
        )
        for ((name, needles) in buckets) {
            for (needle in needles) if (needle in text) return name
        }
        return ""
    }

    /** Loose key so 'Square Pharmaceuticals Ltd.' == 'Square Pharmaceuticals PLC'. */
    fun companyKey(name: String?): String {
        if (name.isNullOrBlank()) return ""
        val folded = asciiFold(name).lowercase()
        var key = NON_ALNUM.replace(COMPANY_NOISE.replace(folded, " "), " ")
            .split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        if (key.isEmpty()) {
            // Corporate-noise-only name ("Healthcare Pharmaceuticals Ltd." is
            // healthcare + pharmaceuticals + ltd), so stripping noise left nothing.
            // Fall back to the normalised raw name so the key stays stable and
            // comparable instead of silently disabling own-company matching.
            key = NON_ALNUM.replace(folded, " ").split(WHITESPACE)
                .filter { it.isNotEmpty() }.joinToString(" ")
        }
        return key
    }

    /** True when two company strings refer to the same manufacturer. */
    fun sameCompany(a: String?, b: String?): Boolean {
        val ka = companyKey(a)
        val kb = companyKey(b)
        if (ka.isEmpty() || kb.isEmpty()) return false
        return ka == kb || ka.startsWith(kb) || kb.startsWith(ka)
    }

    /**
     * Decides which company name to trust.
     *
     * The MedEx catalogue is authoritative. The vision model's company is a guess and
     * is used only when the catalogue has nothing - and is then flagged unverified.
     */
    fun resolveCompany(catalogueCompany: String?, modelCompany: String?): CompanyResolution {
        val catalogue = (catalogueCompany.orEmpty()).trim()
        val model = (modelCompany.orEmpty()).trim()
        return when {
            catalogue.isNotEmpty() -> CompanyResolution(catalogue, "medex", true)
            model.isNotEmpty() && model.lowercase() !in setOf("unknown", "n/a", "none") ->
                CompanyResolution(model, "model_guess", false)
            else -> CompanyResolution("", "none", false)
        }
    }

    /**
     * `difflib.SequenceMatcher(None, a, b).ratio()`.
     *
     * Ported from CPython rather than approximated, because the fuzzy thresholds in
     * [MedexIndex.fuzzy] were tuned against exactly this function. The junk / autojunk
     * heuristics are not reproduced: autojunk only engages at 200+ characters, and
     * brand names are nowhere near that, so the basic longest-matching-block
     * recursion is exact for this input.
     */
    fun sequenceRatio(a: String, b: String): Double {
        val total = a.length + b.length
        if (total == 0) return 1.0
        return 2.0 * matchingCharacters(a, b) / total
    }

    /** Sum of the sizes of the matching blocks, per SequenceMatcher.get_matching_blocks. */
    private fun matchingCharacters(a: String, b: String): Int {
        // b2j: character -> sorted indices in b where it occurs.
        val b2j = HashMap<Char, MutableList<Int>>()
        for (j in b.indices) b2j.getOrPut(b[j]) { mutableListOf() }.add(j)

        var matches = 0
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(0, a.length, 0, b.length))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeLast()
            val m = findLongestMatch(a, alo, ahi, b, blo, bhi, b2j)
            val i = m[0]; val j = m[1]; val k = m[2]
            if (k > 0) {
                matches += k
                if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
                if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
            }
        }
        return matches
    }

    private fun findLongestMatch(
        a: String, alo: Int, ahi: Int,
        b: String, blo: Int, bhi: Int,
        b2j: Map<Char, List<Int>>,
    ): IntArray {
        var besti = alo; var bestj = blo; var bestsize = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val newj2len = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                newj2len[j] = k
                if (k > bestsize) {
                    besti = i - k + 1; bestj = j - k + 1; bestsize = k
                }
            }
            j2len = newj2len
        }
        return intArrayOf(besti, bestj, bestsize)
    }
}

/** Which company name was trusted, and whether it can be relied on. */
data class CompanyResolution(
    val company: String,
    /** "medex" | "model_guess" | "none" */
    val source: String,
    val verified: Boolean,
)

/** Outcome of a brand lookup. */
data class MatchResult(
    val variants: List<MedexProduct>,
    val score: Double,
    val matchedKey: String,
    val exact: Boolean,
) {
    val isEmpty: Boolean get() = variants.isEmpty()
}

/**
 * Brand -> every catalogue variant of that brand.
 *
 * Never collapses duplicates: that collapse was the primary source of the
 * wrong-company bug.
 */
class MedexIndex(entries: Iterable<MedexProduct> = emptyList()) {

    private val byBrand = LinkedHashMap<String, MutableList<MedexProduct>>()
    private var brandKeys: List<String> = emptyList()

    init { build(entries) }

    fun build(entries: Iterable<MedexProduct>): MedexIndex {
        byBrand.clear()
        for (entry in entries) {
            val key = MedicineMatcher.normalizeBrand(entry.brandName)
            if (key.isEmpty()) continue
            byBrand.getOrPut(key) { mutableListOf() }.add(entry)
        }
        brandKeys = byBrand.keys.toList()
        return this
    }

    val distinctBrands: Int get() = brandKeys.size
    val variantCount: Int get() = byBrand.values.sumOf { it.size }

    /**
     * Every indexed product, in catalogue order.
     *
     * [Intelligence.findOwnBrand] has to scan by company rather than by brand,
     * which the brand-keyed map cannot serve.
     */
    val all: List<MedexProduct> get() = byBrand.values.flatten()

    /** All catalogue variants whose normalised brand equals this one. */
    fun exact(brand: String): List<MedexProduct> =
        byBrand[MedicineMatcher.normalizeBrand(brand)].orEmpty()

    /** True when one brand name is marketed by more than one company. */
    fun companyIsAmbiguous(entries: List<MedexProduct>): Boolean {
        val companies = entries
            .map { it.company.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()
        return companies.size > 1
    }

    /** Chooses the catalogue row that best fits strength + dosage form. */
    fun pickVariant(entries: List<MedexProduct>, strength: String = "", form: String = ""): MedexProduct? {
        if (entries.isEmpty()) return null
        if (entries.size == 1) return entries.first()
        // Sorted on the same composite key the Python maximises, so the winner is
        // identical: highest score, then -len(strength) (i.e. shortest strength),
        // then the LARGEST id string. That last one is easy to invert - Python's
        // max() takes the greatest id, not the least.
        return entries.sortedWith(
            compareByDescending<MedexProduct> { variantScore(it, strength, form) }
                .thenBy { it.strength.length }
                .thenByDescending { it.id },
        ).first()
    }

    /** Full lookup: exact first, then the bounded fuzzy fallback. */
    fun match(brand: String, strength: String = "", form: String = ""): MatchResult {
        val exactHits = exact(brand)
        if (exactHits.isNotEmpty()) {
            return MatchResult(exactHits, 1.0, MedicineMatcher.normalizeBrand(brand), true)
        }
        val (variants, score, key) = fuzzy(brand)
        return MatchResult(variants, score, key, false)
    }

    /**
     * Fuzzy brand lookup that cannot be hijacked by tiny catalogue names.
     *
     * @return (variants, score, matchedKey)
     */
    fun fuzzy(brand: String, minScore: Double = 0.80): Triple<List<MedexProduct>, Double, String> {
        val query = MedicineMatcher.normalizeBrand(brand)
        if (query.length < 3) return Triple(emptyList(), 0.0, "")

        var bestKey = ""
        var bestScore = 0.0
        val qLen = query.length

        for (key in brandKeys) {
            val kLen = key.length
            // Length sanity: 'nepa' must never match 'epa'-style stubs or very long
            // unrelated names. The ratio cannot exceed 2*min/(a+b) anyway.
            if (kotlin.math.abs(kLen - qLen) > maxOf(3, qLen * 0.5)) continue
            if ((2.0 * minOf(kLen, qLen)) / (kLen + qLen) < minScore) continue
            // A different first letter almost always means a different drug ('Nepa'
            // must not collapse onto the 3-letter stub 'Epa'). Only tolerated for an
            // equal-length single-character substitution.
            if (key[0] != query[0] && !(kLen == qLen && MedicineMatcher.hamming(query, key) == 1)) {
                continue
            }

            var score = MedicineMatcher.sequenceRatio(query, key)

            when {
                // Equal-length single-character substitution is the classic OCR error
                // ('Secio' -> 'Seclo'); trust it above a longer partial name.
                kLen == qLen && MedicineMatcher.hamming(query, key) == 1 ->
                    score = minOf(1.0, score + 0.15)
                // Containment bonus, bounded so the score stays <= 1.0 and short stubs
                // cannot outrank a genuinely closer name.
                qLen >= 4 && kLen >= 4 && (query in key || key in query) ->
                    score = minOf(1.0, score + 0.08)
            }
            if (score > bestScore) { bestKey = key; bestScore = score }
        }

        val rounded = round3(bestScore)
        return if (bestKey.isNotEmpty() && bestScore >= minScore) {
            Triple(byBrand[bestKey].orEmpty(), rounded, bestKey)
        } else {
            Triple(emptyList(), rounded, "")
        }
    }

    /** Ranks catalogue variants of one brand against the detected medicine. */
    private fun variantScore(entry: MedexProduct, strength: String, form: String): Double {
        var score = 0.0
        val wantStrength = MedicineMatcher.normalizeStrength(strength)
        if (wantStrength.isNotEmpty()) {
            val have = MedicineMatcher.normalizeStrength(entry.strength)
            when {
                have.isNotEmpty() && have == wantStrength -> score += 10.0
                have.isNotEmpty() && wantStrength in have -> score += 6.0
                else -> {
                    // Compare the leading number only: '500mg' vs '500 mg tablet'.
                    val a = LEADING_NUMBER.find(wantStrength)?.value
                    val b = LEADING_NUMBER.find(have)?.value
                    if (a != null && b != null && a == b) score += 4.0
                }
            }
        }
        val wantForm = MedicineMatcher.normalizeForm(form)
        if (wantForm.isNotEmpty()) {
            val haveForm = MedicineMatcher.normalizeForm("${entry.type} ${entry.form}")
            when {
                haveForm.isNotEmpty() && haveForm == wantForm -> score += 5.0
                haveForm.isNotEmpty() -> score -= 1.0
            }
        }
        // Tie-breakers: prefer rows with a company and a real pack image.
        if (entry.company.isNotBlank()) score += 0.5
        val pack = entry.packImage ?: entry.imageUrl ?: ""
        if ("medex.com.bd/storage" in pack) score += 0.3
        return score
    }

    private fun round3(v: Double): Double = Math.rint(v * 1000.0) / 1000.0

    private val LEADING_NUMBER = Regex("^[\\d.]+")
}
