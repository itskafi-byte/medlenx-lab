package com.medlenx.lab.data.repo

import com.medlenx.lab.data.model.DgdaPrices
import com.medlenx.lab.data.model.DgdaPriceRow
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.RegulatoryData
import com.medlenx.lab.data.model.Substitution

/**
 * Port of `app/intelligence.py` — the enterprise intelligence layer.
 *
 * Powers the B2B modules: the brand-substitution engine behind the Doctor Pitch
 * Card, and the DGDA registration/price compliance monitor.
 *
 * Like the Python original these take the already-loaded catalogue as a
 * parameter, so they stay pure and never re-read 25k rows per call.
 */
object Intelligence {

    /**
     * Python's `_normalise_key` — lowercase and collapse whitespace.
     *
     * Deliberately NOT [Compliance.norm]: that one also strips punctuation and
     * non-alphanumerics, which would break brand matching here ("Co-trimoxazole"
     * and "Vitamin D+" must survive intact).
     */
    fun normaliseKey(text: String?): String =
        (text ?: "").lowercase().trim().split(WS).filter { it.isNotEmpty() }.joinToString(" ")

    private val WS = Regex("\\s+")

    /** Python's `_gazette_lookup`: the gazette price row for a brand. */
    fun gazetteLookup(dgda: DgdaPrices, brand: String, generic: String = ""): DgdaPriceRow? {
        val brandK = normaliseKey(brand)
        val genericK = normaliseKey(generic)
        val prices = dgda.prices
        // 1. exact brand
        prices.firstOrNull { normaliseKey(it.brand) == brandK }?.let { return it }
        // 2. generic match
        if (genericK.isNotEmpty()) {
            prices.firstOrNull { it.generic.isNotEmpty() && normaliseKey(it.generic) == genericK }
                ?.let { return it }
        }
        // 3. loose containment on brand
        if (brandK.length >= 4) {
            prices.firstOrNull {
                val rk = normaliseKey(it.brand)
                rk.isNotEmpty() && (brandK in rk || rk in brandK)
            }?.let { return it }
        }
        return null
    }

    /**
     * Python's `dgda_check`: cross-reference a scanned medicine against the
     * DGDA gazette.
     *
     * Status is one of ok | price_adjusted | banned | unknown.
     */
    fun dgdaCheck(
        dgda: DgdaPrices,
        brand: String = "",
        generic: String = "",
        company: String = "",
    ): DgdaStatus {
        val brandK = normaliseKey(brand)

        for (entry in dgda.banned) {
            // The doubled-string containment is the Python original's, quirks
            // included: `norm(brand) in brand_k + " " + brand_k`. Reproduced
            // verbatim so a banned brand that is a suffix of the scanned name
            // still trips.
            if (entry.brand.isNotEmpty() &&
                normaliseKey(entry.brand) in "$brandK $brandK"
            ) {
                return DgdaStatus(
                    status = "banned",
                    severity = "critical",
                    concern = "🚫 ${entry.brand} (${entry.generic}): ${entry.reason}",
                    flagType = "banned",
                )
            }
            if (entry.generic.isNotEmpty() && generic.isNotEmpty() &&
                normaliseKey(entry.generic) == normaliseKey(generic)
            ) {
                return DgdaStatus(
                    status = "banned",
                    severity = "critical",
                    concern = "🚫 ${entry.brand}: ${entry.reason}",
                    flagType = "banned",
                )
            }
        }

        for (entry in dgda.priceAdjusted) {
            if (entry.brand.isNotEmpty() && normaliseKey(entry.brand) == brandK) {
                return DgdaStatus(
                    status = "price_adjusted",
                    severity = "warn",
                    concern = "⚠️ ${entry.brand} MRP ${fmtMrp(entry.oldMrp)} → " +
                        "${fmtMrp(entry.newMrp)} BDT. ${entry.note}",
                    flagType = "price_adjusted",
                    oldMrp = entry.oldMrp,
                    newMrp = entry.newMrp,
                    priceMrp = entry.newMrp,
                    pricePack = "",
                    priceStrength = entry.generic,
                )
            }
        }

        gazetteLookup(dgda, brand, generic)?.let { row ->
            return DgdaStatus(
                status = "ok",
                severity = "info",
                concern = null,
                flagType = null,
                priceMrp = row.mrp,
                pricePack = row.pack,
                priceStrength = row.strength,
            )
        }
        return DgdaStatus(status = "unknown", severity = "info")
    }

    /** Python interpolates the raw value, so an absent MRP prints as "None". */
    private fun fmtMrp(v: Double?): String = if (v == null) "None" else PyMath.pyFloat(v)

    /**
     * Python's `find_own_brand`: the client company's catalogue brand for a
     * generic.
     *
     * Generic is matched loosely (substring on generic/ingredient/category),
     * then an EXACT generic-name match is preferred — so "omeprazole" picks
     * Omenix over Esonix, whose generic is "esomeprazole", a substring
     * collision — and finally the variant closest to the detected strength.
     */
    fun findOwnBrand(
        ownCompany: String,
        generic: String,
        medexDb: List<MedexProduct>,
        preferStrength: String = "",
    ): MedexProduct? {
        if (generic.isEmpty()) return null
        if (MedicineMatcher.companyKey(ownCompany).isEmpty()) return null
        val genericL = normaliseKey(generic)

        val candidates = medexDb.filter { row ->
            MedicineMatcher.sameCompany(row.company, ownCompany) && run {
                val blob = normaliseKey(
                    listOf(row.generic, row.ingredient, row.category).joinToString(" ")
                )
                blob.isNotEmpty() && (genericL in blob || blob in genericL)
            }
        }
        if (candidates.isEmpty()) return null

        val exact = candidates.filter { normaliseKey(it.generic) == genericL }
        val pool = exact.ifEmpty { candidates }

        val want = normaliseKey(preferStrength)
        if (want.isNotEmpty()) {
            pool.firstOrNull { want in normaliseKey(it.strength) }?.let { return it }
        }
        // Python's list.sort is stable, and so is sortedBy — equal-length
        // strengths keep their catalogue order either way.
        return pool.sortedBy { it.strength.length }.first()
    }

    /**
     * Python's `generic_substitution`: build a competitor → own-brand
     * substitution card for a detected medicine.
     *
     * Returns null when the detected medicine is already the officer's own
     * brand, or when no own-brand equivalent exists in the catalogue.
     */
    fun genericSubstitution(
        data: RegulatoryData,
        detectedBrand: String,
        detectedCompany: String,
        detectedGeneric: String,
        detectedStrength: String,
        detectedType: String,
        detectedImageUrl: String?,
        ownCompany: String,
        medexDb: List<MedexProduct>,
    ): Substitution? {
        if (ownCompany.isEmpty()) return null
        if (detectedCompany.isNotEmpty() &&
            MedicineMatcher.sameCompany(detectedCompany, ownCompany)
        ) {
            return null
        }
        val generic = detectedGeneric.trim()
        if (generic.isEmpty()) return null

        val own = findOwnBrand(ownCompany, generic, medexDb, preferStrength = detectedStrength)
            ?: return null

        val detectedPrice = gazetteLookup(data.dgda, detectedBrand, generic)
        val ownPrice = gazetteLookup(data.dgda, own.brandName, generic)
        val dMrp = detectedPrice?.mrp
        val oMrp = ownPrice?.mrp

        var unitDiff = 0.0
        var unitDiffLabel = ""
        if (dMrp != null && oMrp != null) {
            unitDiff = PyMath.round2(oMrp - dMrp)
            // Parenthesised on purpose. Without them Kotlin binds the else
            // branch greedily - `if (c) a else b + " per unit"` parses as
            // `if (c) a else (b + " per unit")`, silently dropping the unit
            // suffix from the "higher" case only.
            val direction = if (unitDiff > 0) "higher" else "lower"
            unitDiffLabel = "${PyMath.fixed2(kotlin.math.abs(unitDiff))} BDT " +
                "$direction per unit"
        }

        val competitor = MedexProduct(
            brandName = detectedBrand,
            company = detectedCompany,
            generic = generic,
            strength = detectedStrength,
            type = detectedType.ifEmpty { "Tablet" },
            imageUrl = detectedImageUrl,
            mrp = dMrp,
            pack = detectedPrice?.pack ?: "",
        )
        return Substitution(
            ownBrand = own.copy(
                company = ownCompany,
                generic = generic,
                type = own.type.ifEmpty { "Tablet" },
                mrp = oMrp,
                pack = ownPrice?.pack ?: "",
            ),
            competitor = competitor,
            generic = generic,
            unitDifference = unitDiff,
            unitDifferenceLabel = unitDiffLabel,
            pitch = smartPitchNote(
                detectedBrand = detectedBrand,
                // Untrimmed, matching Python: generic_substitution passes the
                // original `detected` dict to smart_pitch_note, not the stripped
                // generic it stores on the card.
                detectedGeneric = detectedGeneric,
                own = own,
                dMrp = dMrp,
                oMrp = oMrp,
            ),
        )
    }

    /** Python's `smart_pitch_note`: a 2-sentence promo script for the MPO. */
    fun smartPitchNote(
        detectedBrand: String,
        detectedGeneric: String,
        own: MedexProduct,
        dMrp: Double? = null,
        oMrp: Double? = null,
    ): String {
        val compBrand = detectedBrand.ifEmpty { "the brand they prescribe" }
        val ownBrand = own.brandName.ifEmpty { "our brand" }
        val generic = own.generic.ifEmpty { detectedGeneric }.ifEmpty { "this molecule" }
        val strength = own.strength
        val dose = own.type.ifEmpty { "dose" }

        val priceLine = if (dMrp != null && oMrp != null) {
            val diff = oMrp - dMrp
            when {
                diff > 0 ->
                    "While $ownBrand carries a small premium, it keeps the same " +
                        "$strength $dose as $compBrand."
                diff < 0 ->
                    "Switching to $ownBrand saves the patient " +
                        "${PyMath.fixed2(kotlin.math.abs(diff))} BDT per unit with an " +
                        "equivalent $strength $dose."
                else ->
                    "$ownBrand matches $compBrand on price at $strength $dose."
            }
        } else {
            "$ownBrand offers the same $generic at an equivalent $strength $dose."
        }

        return "Doctor, $compBrand is being written for $generic at this chamber. " +
            "Our $ownBrand is the same $strength ${own.type.ifEmpty { "form" }} of " +
            "$generic. $priceLine May I leave a starter pack of $ownBrand to consider " +
            "for your next patient?"
    }
}
