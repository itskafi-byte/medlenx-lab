package com.medlenx.lab.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Wire models for the three regulatory datasets bundled by `copyMedLenXAssets`.
 *
 * Shapes are taken from the files themselves, not from the Python type hints:
 * neml_list.json holds 165 molecules, trips_waiver.json 26, dgda_prices.json
 * 32 price rows / 4 banned / 3 price-adjusted.
 */

/** One `data/neml_list.json` molecule row. */
@Serializable
data class NemlMolecule(
    val molecule: String = "",
    val `class`: String = "",
    val category: String = "",
)

@Serializable
data class NemlList(
    val version: String = "",
    val source: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val molecules: List<NemlMolecule> = emptyList(),
)

/** One `data/trips_waiver.json` molecule row. */
@Serializable
data class TripsMolecule(
    val molecule: String = "",
    val `class`: String = "",
    val originator: String = "",
    @SerialName("watch_level") val watchLevel: String = "",
    val note: String = "",
)

@Serializable
data class TripsWaiver(
    val context: String = "",
    @SerialName("waiver_expiry") val waiverExpiry: String = "",
    @SerialName("ldc_graduation") val ldcGraduation: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val molecules: List<TripsMolecule> = emptyList(),
)

/** One `data/dgda_prices.json` gazette price row. */
@Serializable
data class DgdaPriceRow(
    val brand: String = "",
    val generic: String = "",
    val company: String = "",
    val strength: String = "",
    val type: String = "",
    val pack: String = "",
    val mrp: Double? = null,
)

@Serializable
data class DgdaBannedRow(
    val brand: String = "",
    val generic: String = "",
    val reason: String = "",
)

@Serializable
data class DgdaAdjustedRow(
    val brand: String = "",
    val generic: String = "",
    @SerialName("old_mrp") val oldMrp: Double? = null,
    @SerialName("new_mrp") val newMrp: Double? = null,
    val note: String = "",
)

@Serializable
data class DgdaPrices(
    val source: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    val gazette: String = "",
    val prices: List<DgdaPriceRow> = emptyList(),
    val banned: List<DgdaBannedRow> = emptyList(),
    @SerialName("price_adjusted") val priceAdjusted: List<DgdaAdjustedRow> = emptyList(),
)

/**
 * The three datasets together, loaded once per process.
 *
 * `nemlIndex` / `tripsIndex` are built at load time and are *insertion ordered*,
 * because `neml_lookup` and `trips_lookup` fall through to a linear scan whose
 * result depends on iteration order matching the file's.
 */
data class RegulatoryData(
    val neml: NemlList,
    val trips: TripsWaiver,
    val dgda: DgdaPrices,
    val nemlIndex: Map<String, NemlMolecule>,
    val tripsIndex: Map<String, TripsMolecule>,
) {
    companion object {
        /** Everything empty — the shape the Python loaders degrade to on a read error. */
        val Empty = RegulatoryData(
            neml = NemlList(),
            trips = TripsWaiver(),
            dgda = DgdaPrices(),
            nemlIndex = emptyMap(),
            tripsIndex = emptyMap(),
        )
    }
}
