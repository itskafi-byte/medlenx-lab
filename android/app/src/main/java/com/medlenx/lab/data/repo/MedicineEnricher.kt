package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.MedexEntity
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.MatchType
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.VlMedicine

/**
 * Attaches the MedEx catalogue to what the vision model read.
 *
 * This is the stage the app was missing: `ScanViewModel` used to map the raw
 * [VlMedicine] straight to a display card, so no medicine ever carried a verified
 * company, a match type or a catalogue image. The matcher in
 * [com.medlenx.lab.data.repo.MedicineMatcher] decides *which* catalogue row a brand
 * refers to; this decides what to record about it.
 *
 * The company rule is the important one and it is deliberately not symmetric: the
 * catalogue wins whenever it has an answer, because the VL prompt actively invites the
 * model to guess a manufacturer. The model's company survives only as
 * [EnrichedMedicine.companyModelGuess], and is flagged unverified.
 *
 * NOT YET POPULATED: `neml`, `dgdaAlert`, `isAntibiotic`, `broadSpectrum`,
 * `therapeuticClass`, `tripsWatch` and `substitution` all stay at their defaults.
 * Those come from the NEML / DGDA / TRIPS catalogue lookups and the substitution
 * engine, which live in the still-unported `database.py` and `pharma_hub.py`. They are
 * left null rather than guessed, so the audit drawer shows an honest empty flag
 * instead of a fabricated one.
 */
object MedicineEnricher {

    fun enrich(medicines: List<VlMedicine>, index: MedexIndex): List<EnrichedMedicine> =
        medicines.mapIndexed { i, m -> enrichOne(i + 1, m, index) }

    private fun enrichOne(lineNumber: Int, m: VlMedicine, index: MedexIndex): EnrichedMedicine {
        val form = m.type.ifBlank { m.form }
        val match = index.match(m.brandName, m.strength, form)
        val variants = match.variants
        val pick = index.pickVariant(variants, m.strength, form)

        val resolution = MedicineMatcher.resolveCompany(pick?.company, m.company)
        val catalogueCompany = pick?.company?.trim().orEmpty()
        val modelCompany = m.company.trim()

        val matchType = when {
            variants.isEmpty() -> MatchType.None
            match.exact -> MatchType.Exact
            else -> MatchType.Fuzzy
        }

        // A conflict is only meaningful when the model actually asserted a company and
        // the catalogue disagrees - not when the model left it blank.
        val conflict = modelCompany.isNotEmpty() &&
            catalogueCompany.isNotEmpty() &&
            !MedicineMatcher.sameCompany(catalogueCompany, modelCompany)

        return EnrichedMedicine(
            lineNumber = lineNumber,
            brandName = m.brandName,
            genericName = m.genericName.ifBlank { pick?.generic.orEmpty() },
            rawText = m.rawText,
            form = m.form,
            type = m.type,
            strength = m.strength,
            dosage = m.dosage,
            dosageNormalized = m.dosageNormalized,
            frequency = m.frequency,
            confidence = m.confidence,

            company = resolution.company.ifBlank { null },
            companyVerified = resolution.verified,
            companyAmbiguous = index.companyIsAmbiguous(variants),
            companyConflict = conflict,
            companyModelGuess = if (resolution.source == "model_guess") resolution.company else null,
            matchType = matchType,
            matchScore = match.score,
            imageUrl = pick?.image,
            // Every variant of the matched brand, so the rep can pick a different
            // strength or company by hand when the automatic choice is wrong.
            alternatives = variants.filter { it !== pick },
        )
    }
}

/**
 * Room row -> catalogue model.
 *
 * Kept as an extension here rather than on the entity so the persistence layer does
 * not have to know the matcher exists.
 */
fun MedexEntity.toProduct(): MedexProduct = MedexProduct(
    id = id,
    brandName = brandName,
    generic = generic,
    strength = strength,
    form = form,
    type = type,
    company = company,
    ingredient = ingredient,
    category = category,
    imageUrl = imageUrl,
    packImage = packImage,
    url = url,
)
