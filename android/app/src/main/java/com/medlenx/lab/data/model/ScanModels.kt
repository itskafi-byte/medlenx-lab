package com.medlenx.lab.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Shapes returned by MedLenX VL.
 *
 * Field names match the model's JSON contract in app/medlenx_client.py exactly, so the
 * same prompt produces the same payload on Android as on the web backend.
 */
@Serializable
data class VlScanResult(
    val doctor: VlDoctor = VlDoctor(),
    val medicines: List<VlMedicine> = emptyList(),
    @SerialName("patient_info") val patientInfo: VlPatientInfo? = null,
    val meta: VlMeta? = null,
)

@Serializable
data class VlDoctor(
    val name: String = "",
    @SerialName("bmdc_no") val bmdcNo: String = "",
    val qualifications: String = "",
    val hospital: String = "",
    val department: String = "",
    val specialty: String = "",
    val chamber: String = "",
    val district: String = "",
    val upazila: String = "",
    val territory: String = "",
)

@Serializable
data class VlMedicine(
    @SerialName("brand_name") val brandName: String = "",
    @SerialName("generic_name") val genericName: String = "",
    @SerialName("raw_text") val rawText: String = "",
    val form: String = "",
    val type: String = "",
    val strength: String = "",
    val dosage: String = "",
    @SerialName("dosage_bengali") val dosageBengali: String = "",
    @SerialName("dosage_normalized") val dosageNormalized: String = "",
    val frequency: String = "",
    /**
     * Empty unless the manufacturer is literally printed on the prescription. The
     * prompt forbids guessing it; the catalogue resolves it instead.
     */
    val company: String = "",
    val confidence: Double = 0.0,
    /** Normalised [x, y, w, h] (0..1) box of this medicine's line on the image. */
    val bbox: List<Float> = emptyList(),
)

@Serializable
data class VlPatientInfo(
    val masked: Boolean = true,
    val note: String = "",
)

@Serializable
data class VlMeta(
    @SerialName("total_medicines") val totalMedicines: Int = 0,
    val legibility: Double? = null,
    @SerialName("language_mix") val languageMix: String = "",
)

/**
 * Enriched medicine as the UI renders it.
 *
 * Adds the catalogue resolution, compliance signals and confidence bands that the web
 * app layers on top of the raw VL read (medicine_matcher.py + compliance.py).
 */
data class EnrichedMedicine(
    val lineNumber: Int,
    val brandName: String,
    val genericName: String,
    val rawText: String,
    val form: String,
    val type: String,
    val strength: String,
    val dosage: String,
    val dosageNormalized: String,
    val frequency: String,
    val confidence: Double,

    // ---- catalogue resolution -------------------------------------------
    val company: String?,
    val companyVerified: Boolean = false,
    val companyAmbiguous: Boolean = false,
    val companyConflict: Boolean = false,
    val companyModelGuess: String? = null,
    val matchType: MatchType = MatchType.None,
    val matchScore: Double = 0.0,
    val imageUrl: String? = null,
    val alternatives: List<MedexProduct> = emptyList(),

    /**
     * Normalised [x, y, w, h] region from the vision read, carried through enrichment
     * so the verification cards can still drive the per-medicine highlight box.
     */
    val bbox: List<Float> = emptyList(),

    // ---- compliance ------------------------------------------------------
    val neml: NemlStatus? = null,
    val dgdaAlert: DgdaAlert? = null,
    val isAntibiotic: Boolean = false,
    val broadSpectrum: Boolean = false,
    val therapeuticClass: String? = null,
    val tripsWatch: TripsWatch? = null,
    val substitution: Substitution? = null,
) {
    val confidencePercent: Int get() = (confidence * 100).toInt()

    /**
     * True for anything outside the emerald high-confidence band, i.e. the orange
     * "AI Guess" and amber "manual flag" states.
     *
     * Derived from [confidenceBand] rather than a local comparison: the web caption
     * says "<80%" but the rendered ConfBadge flips at 85, and the earlier hardcoded
     * `< 80` here contradicted the badge the user was actually looking at.
     */
    val lowConfidence: Boolean get() = confidenceBand(confidencePercent) != ConfidenceBand.High

    val needsReview: Boolean
        get() = companyAmbiguous || companyConflict ||
            confidenceBand(confidencePercent) == ConfidenceBand.ManualFlag ||
            (company != null && !companyVerified)
}

enum class MatchType(val label: String) {
    Exact("MedEx exact match"),
    Fuzzy("MedEx fuzzy match"),
    Live("medex.com.bd live"),
    None("No catalogue match"),
}

data class NemlStatus(
    val listed: Boolean,
    val molecule: String,
    val therapeuticClass: String? = null,
)

data class DgdaAlert(
    val flagged: Boolean,
    /** banned | price_adjusted | ceiling_exceeded | unverified */
    val status: String,
    val reason: String,
    val ceilingPrice: Double? = null,
)

data class TripsWatch(
    val watch: Boolean,
    val molecule: String,
    val originator: String,
    val watchLevel: String = "medium",
)

data class Substitution(
    val ownBrand: MedexProduct,
    val competitor: MedexProduct,
    val generic: String,
    val unitDifference: Double,
    val unitDifferenceLabel: String,
    val pitch: String,
)

/**
 * The regulatory badges the pitch card may carry, for the medicine being displaced.
 *
 * The web's pitch modal reads them off the audit item it was opened from (`m.neml`,
 * `m.dgda_price_alert`, `index.html:3034`) and renders each badge only when its flag
 * is set; its PDF does the same (`main.py:1175`). A [Substitution] carries no
 * regulatory fields, so Android's card had nothing to test and asserted both badges
 * unconditionally - telling a rep, on a card they present in the chamber, that a real
 * medicine is NEML-listed and DGDA-flagged whether or not it is.
 */
data class PitchCompliance(
    val nemlListed: Boolean = false,
    val nemlMolecule: String = "",
    /** The NEML molecule's class. Blank for a saved row, which does not store it. */
    val nemlClass: String = "",
    val dgdaFlagged: Boolean = false,
    val dgdaReason: String = "",
)

/** Doctor block plus the cascading location the officer confirms. */
data class VerifiedDoctor(
    val name: String,
    val bmdcNo: String,
    val qualifications: String,
    val hospital: String,
    val specialty: String,
    val chamber: String,
    val district: String,
    val upazila: String,
    val territory: String,
    val prescriptionSource: String,
)
