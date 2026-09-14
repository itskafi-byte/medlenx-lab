package com.medlenx.lab.data.model

import kotlinx.serialization.Serializable

/** data/bd_geo.json — `{"districts": {"Dhaka": {"lat": 23.8103, "lng": 90.4125}, ...}}`. */
@Serializable
data class BdGeo(val districts: Map<String, GeoPoint> = emptyMap())

@Serializable
data class GeoPoint(val lat: Double = 0.0, val lng: Double = 0.0)

/** Verdict from the off-territory geofence. Mirrors territory_check() in compliance.py. */
data class TerritoryVerdict(
    val offTerritory: Boolean,
    val reason: String,
    val gpsDistrict: String,
)

/**
 * Where a captured coordinate came from.
 *
 * Kept on the record because the two sources carry different weight in a territory
 * dispute: a live fix is stronger evidence than metadata embedded in a photo, and an
 * RSM reviewing the audit is entitled to know which one they are looking at.
 */
enum class GpsSource(val label: String) {
    Device("device GPS"),
    PhotoExif("photo EXIF"),
}

/** GPS pin captured alongside a scan. */
data class GpsFix(
    val lat: Double,
    val lng: Double,
    val source: GpsSource = GpsSource.Device,
)
