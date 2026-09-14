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

    /** Web threshold: below 80% the row flips into the orange AI-guess state. */
    val lowConfidence: Boolean get() = confidencePercent < 80

    val needsReview: Boolean
        get() = companyAmbiguous || companyConflict || confidencePercent < 70 ||
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
