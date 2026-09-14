package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.MedexEntity
import com.medlenx.lab.data.model.EnrichedMedicine
import com.medlenx.lab.data.model.MatchType
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.RegulatoryData
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
 * The regulatory fields follow `main.py`'s `prescription_audit` assembly, not the
 * module docstrings, because two of its rules are easy to get wrong:
 *
 *  - `broadSpectrum` is gated on the antibiotic test (`bool(abx and
 *    is_broad_spectrum(...))`), so a broad-spectrum keyword on a non-antibiotic
 *    row does not set it.
 *  - `therapeuticClass` takes the NEML class *directly* when the molecule is
 *    listed, even when that class is blank, and only falls through to keyword
 *    resolution otherwise.
 */
object MedicineEnricher {

    fun enrich(
        medicines: List<VlMedicine>,
        index: MedexIndex,
        regulatory: RegulatoryData,
        ownCompany: String = "",
    ): List<EnrichedMedicine> =
        medicines.mapIndexed { i, m -> enrichOne(i + 1, m, index, regulatory, ownCompany) }

    private fun enrichOne(
        lineNumber: Int,
        m: VlMedicine,
        index: MedexIndex,
        regulatory: RegulatoryData,
        ownCompany: String,
    ): EnrichedMedicine {
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

        // ---- regulatory + clinical intelligence ------------------------
        // `main.py` resolves generic/ingredient/category off the merged
        // catalogue row, so a blank VL generic still gets NEML coverage.
        val generic = m.genericName.ifBlank { pick?.generic.orEmpty() }
        val ingredient = pick?.ingredient.orEmpty()
        val category = pick?.category.orEmpty()
        val resolvedCompany = resolution.company.trim()

        val neml = Compliance.nemlLookup(regulatory, generic, ingredient)
        val abx = Compliance.isAntibiotic(generic, category, ingredient)
        val therapeuticClass = if (neml.listed) {
            neml.therapeuticClass.orEmpty()
        } else {
            Compliance.resolveTherapeuticClass(regulatory, generic, category, ingredient)
        }
        val trips = Compliance.tripsLookup(regulatory, generic, ingredient)
        val dgdaStatus = Intelligence.dgdaCheck(
            dgda = regulatory.dgda,
            brand = m.brandName,
            generic = generic,
            company = resolvedCompany,
        )
        val substitution = Intelligence.genericSubstitution(
            data = regulatory,
            detectedBrand = m.brandName,
            detectedCompany = resolvedCompany,
            detectedGeneric = generic,
            detectedStrength = m.strength,
            detectedType = form,
            detectedImageUrl = pick?.image,
            ownCompany = ownCompany,
            medexDb = index.all,
        )

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

            neml = neml,
            dgdaAlert = Compliance.priceCeilingAlert(dgdaStatus, detectedMrp = pick?.mrp),
            isAntibiotic = abx,
            broadSpectrum = abx && Compliance.isBroadSpectrum(generic, ingredient),
            therapeuticClass = therapeuticClass.ifBlank { null },
            tripsWatch = trips,
            substitution = substitution,
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
