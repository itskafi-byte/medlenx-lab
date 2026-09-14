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

/** GPS pin captured alongside a scan. */
data class GpsFix(val lat: Double, val lng: Double)
