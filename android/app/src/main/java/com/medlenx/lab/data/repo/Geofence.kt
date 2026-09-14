package com.medlenx.lab.data.repo

import com.medlenx.lab.data.model.BdGeo
import com.medlenx.lab.data.model.BdLocations
import com.medlenx.lab.data.model.TerritoryVerdict
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Off-territory geofence.
 *
 * Direct port of haversine_km(), resolve_geo_district(), _territory_base() and
 * territory_check() from app/compliance.py. The decisive rule is preserved: an audit
 * is only flagged when BOTH a positive assigned territory AND a positive scan
 * location signal exist and contradict each other — it never flags on missing data.
 */
object Geofence {

    /** Matches compliance.py's default search radius for district centroids. */
    private const val MAX_KM = 90.0

    /** `_norm()` — lowercase, keep [a-z0-9+ ], collapse whitespace. */
    fun normalize(text: String?): String =
        (text ?: "").lowercase()
            .replace(Regex("[^a-z0-9+ ]"), " ")
            .trim()
            .replace(Regex("\\s+"), " ")

    /** `_territory_base()` — strip territory/regional/region/zone tokens. */
    fun territoryBase(name: String?): String {
        var text = normalize(name)
        for (suffix in listOf("territory", "regional", "region", "zone")) {
            text = text.replace(Regex("\\b$suffix\\b"), " ")
        }
        return normalize(text)
    }

    fun haversineKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val r = 6371.0
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lng2 - lng1)
        val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }

    /** Nearest BD district centroid within [maxKm], or "" when nothing is close enough. */
    fun resolveGeoDistrict(
        lat: Double?,
        lng: Double?,
        geo: BdGeo,
        maxKm: Double = MAX_KM,
    ): String {
        if (lat == null || lng == null) return ""
        var best = ""
        var bestDistance: Double? = null
        for ((district, centroid) in geo.districts) {
            val d = haversineKm(lat, lng, centroid.lat, centroid.lng)
            if (bestDistance == null || d < bestDistance) {
                best = district
                bestDistance = d
            }
        }
        return if (bestDistance != null && bestDistance <= maxKm) best else ""
    }

    /**
     * `territory_check()` — the full evidence-based verdict.
     *
     * @param officerTerritory the assigned zone from the officer profile
     * @param scanTerritory territory selected/typed for this scan
     * @param scanDistrict district selected/typed for this scan
     */
    fun territoryCheck(
        officerTerritory: String,
        scanTerritory: String,
        scanDistrict: String,
        gpsLat: Double?,
        gpsLng: Double?,
        geo: BdGeo,
        locations: BdLocations?,
    ): TerritoryVerdict {
        val assigned = territoryBase(officerTerritory)
        val gpsDistrict = resolveGeoDistrict(gpsLat, gpsLng, geo)

        if (assigned.isBlank()) {
            return TerritoryVerdict(
                offTerritory = false,
                reason = "No assigned territory on the officer profile",
                gpsDistrict = gpsDistrict,
            )
        }

        // Districts implied by the officer's territory: name containment, or the
        // district's own territory list from bd_locations.json.
        val implied = mutableSetOf<String>()
        locations?.districts?.forEach { (district, info) ->
            val lower = district.lowercase()
            if (lower.contains(assigned) || assigned.contains(lower)) {
                implied.add(lower)
            }
            info.territories.forEach { t ->
                if (territoryBase(t) == assigned) implied.add(lower)
            }
        }

        data class Evidence(val matches: Boolean, val description: String)
        val evidences = mutableListOf<Evidence>()

        if (scanTerritory.isNotBlank()) {
            val st = territoryBase(scanTerritory)
            val matches = st.isNotBlank() &&
                (st == assigned || assigned.contains(st) || st.contains(assigned))
            evidences += Evidence(
                matches,
                "scanned in $scanTerritory (assigned: $officerTerritory)",
            )
        }
        if (scanDistrict.isNotBlank()) {
            val d = scanDistrict.lowercase()
            val matches = if (implied.isNotEmpty()) d in implied
            else d.contains(assigned) || assigned.contains(d)
            evidences += Evidence(matches, "scanned in $scanDistrict district (assigned: $officerTerritory)")
        }
        if (gpsDistrict.isNotBlank()) {
            val d = gpsDistrict.lowercase()
            val matches = if (implied.isNotEmpty()) d in implied
            else d.contains(assigned) || assigned.contains(d)
            evidences += Evidence(
                matches,
                "GPS pin resolves to $gpsDistrict (assigned: $officerTerritory)",
            )
        }

        if (evidences.isEmpty()) {
            return TerritoryVerdict(
                offTerritory = false,
                reason = "No scan location captured — cannot verify territory",
                gpsDistrict = gpsDistrict,
            )
        }

        val mismatches = evidences.filterNot { it.matches }
        return if (mismatches.isNotEmpty()) {
            TerritoryVerdict(
                offTerritory = true,
                reason = mismatches.joinToString("; ") { it.description },
                gpsDistrict = gpsDistrict,
            )
        } else {
            TerritoryVerdict(false, "Within assigned territory", gpsDistrict)
        }
    }
}
