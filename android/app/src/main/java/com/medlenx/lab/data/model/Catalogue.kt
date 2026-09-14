package com.medlenx.lab.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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

// NOTE: data/medex_full.json is a TOP-LEVEL JSON ARRAY of 25,105 product records,
// not an object with a "medicines" key. Decode it as List<MedexProduct> directly.
/** data/neml_list.json — DGDA National Essential Medicines List. */
@Serializable
data class NemlEntry(
    val molecule: String = "",
    val aliases: List<String> = emptyList(),
    @SerialName("therapeutic_class") val therapeuticClass: String = "",
)

/** data/trips_waiver.json — LDC pharmaceutical TRIPS waiver watch list (to 2033). */
@Serializable
data class TripsMolecule(
    val molecule: String = "",
    @SerialName("therapeutic_class") val therapeuticClass: String = "",
    val originator: String = "",
    @SerialName("watch_level") val watchLevel: String = "medium",
    val criticality: String = "",
)

/** data/dgda_prices.json — MRP ceilings, bans and gazette adjustments. */
@Serializable
data class DgdaEntry(
    val brand: String = "",
    val company: String = "",
    val status: String = "",
    @SerialName("ceiling_mrp") val ceilingMrp: Double? = null,
    val note: String = "",
)

/** data/health_days.json — WHO / UN health-day calendar. */
@Serializable
data class HealthDay(
    val id: String = "",
    val name: String = "",
    val month: Int = 0,
    val day: Int = 0,
    val org: String = "",
    val focus: List<String> = emptyList(),
    val color: String = "#1D4ED8",
    val summary: String = "",
    @SerialName("mpo_tip") val mpoTip: String = "",
)

@Serializable
data class HealthDayFile(
    val source: String = "",
    val year: Int = 0,
    val days: List<HealthDay> = emptyList(),
)

/** data/pharma_jobs.json — seeded job board. */
@Serializable
data class PharmaJob(
    val id: String = "",
    val title: String = "",
    val company: String = "",
    val category: String = "",
    val department: String = "",
    val location: String = "",
    val territory: String = "",
    val experience: String = "",
    val description: String = "",
    val salary: String = "",
    val tags: List<String> = emptyList(),
    @SerialName("posted_on") val postedOn: String = "",
    @SerialName("apply_url") val applyUrl: String = "",
    val fresh: Boolean = false,
)

/** data/pharma_news.json — seeded market / regulatory news. */
@Serializable
data class NewsItem(
    val id: String = "",
    val title: String = "",
    val summary: String = "",
    val source: String = "",
    @SerialName("source_type") val sourceType: String = "market",
    @SerialName("published_at") val publishedAt: String = "",
    val url: String = "",
    val live: Boolean = false,
)

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
