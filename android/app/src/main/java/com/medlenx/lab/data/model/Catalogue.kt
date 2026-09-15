package com.medlenx.lab.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models for the catalogue and geo datasets bundled under assets/data.
 *
 * The regulatory / hub / news datasets each live in their own file
 * (RegulatoryData.kt, HubData.kt, NewsData.kt). Those declarations used to be
 * duplicated here, which is a compile error in Kotlin - a package cannot declare
 * the same top-level name twice - so this file now holds only what is unique to it.
 */

// NOTE: data/medex_full.json is a TOP-LEVEL JSON ARRAY of 25,105 product records,
// not an object with a "medicines" key. Decode it as List<MedexProduct> directly.
/** One row of data/medex_full.json — a DGDA-registered MedEx SKU. */
@Serializable
data class MedexProduct(
    val id: String = "",
    @SerialName("brand_name") val brandName: String = "",
    val generic: String = "",
    val strength: String = "",
    val form: String = "",
    val type: String = "",
    val company: String = "",
    val ingredient: String = "",
    val category: String = "",
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("pack_image") val packImage: String? = null,
    val url: String? = null,
    @SerialName("strength_value") val strengthValue: String = "",
    @SerialName("scraped_at") val scrapedAt: String = "",
    /** Present on catalogue rows enriched by the backend, absent in the raw scrape. */
    @SerialName("mrp") val mrp: Double? = null,
    val pack: String = "",
) {
    val image: String? get() = imageUrl ?: packImage
}

/** data/bd_locations.json — 8 divisions / 64 districts / 508 upazilas. */
@Serializable
data class BdLocations(
    val divisions: List<String> = emptyList(),
    val specialties: List<String> = emptyList(),
    val districts: Map<String, BdDistrict> = emptyMap(),
)

@Serializable
data class BdDistrict(
    val division: String = "",
    val upazilas: List<String> = emptyList(),
    val territories: List<String> = emptyList(),
    val lat: Double? = null,
    val lng: Double? = null,
)
