package com.medlenx.lab.data.repo

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.model.BdGeo
import com.medlenx.lab.data.model.BdLocations
import com.medlenx.lab.data.model.GpsFix
import com.medlenx.lab.data.model.TerritoryVerdict
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.serializer

/**
 * Cascading location engine plus GPS capture.
 *
 * Mirrors the web app's bound District -> Upazila -> Territory selects, where
 * changing the district invalidates its dependents, and the GPS pin button that
 * embeds coordinates with an audit for territory verification.
 *
 * Uses the framework LocationManager rather than play-services-location so the
 * module carries no Google Play dependency.
 */
class LocationRepository(
    private val context: Context,
    private val assets: AssetCatalogue,
) {

    private var locations: BdLocations? = null
    private var geo: BdGeo? = null

    suspend fun locations(): BdLocations = locations ?: withContext(Dispatchers.IO) {
        (assets.readAsset("bd_locations.json", BdLocations.serializer()) ?: BdLocations())
            .also { locations = it }
    }

    suspend fun geo(): BdGeo = geo ?: withContext(Dispatchers.IO) {
        (assets.readAsset("bd_geo.json", BdGeo.serializer()) ?: BdGeo())
            .also { geo = it }
    }

    fun districtNames(): List<String> =
        locations?.districts?.keys?.sorted() ?: emptyList()

    /** Empty until a district is chosen — the web shows "Select district first". */
    fun upazilasFor(district: String): List<String> =
        locations?.districts?.get(district)?.upazilas ?: emptyList()

    fun territoriesFor(district: String): List<String> =
        locations?.districts?.get(district)?.territories ?: emptyList()

    fun specialties(): List<String> = locations?.specialties ?: emptyList()

    fun divisions(): List<String> = locations?.divisions ?: emptyList()

    /** Loads the last known fix without waiting for a new one. Null if unavailable. */
    @SuppressLint("MissingPermission")
    suspend fun lastKnownFix(): GpsFix? = withContext(Dispatchers.IO) {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return@withContext null
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER) &&
            !lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        ) {
            return@withContext null
        }
        // GPS is preferred; network is the fallback for indoor chamber scans.
        val location = runCatching {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return@withContext null
        GpsFix(location.latitude, location.longitude)
    }

    suspend fun verdict(
        officerTerritory: String,
        scanTerritory: String,
        scanDistrict: String,
        fix: GpsFix?,
    ): TerritoryVerdict = Geofence.territoryCheck(
        officerTerritory = officerTerritory,
        scanTerritory = scanTerritory,
        scanDistrict = scanDistrict,
        gpsLat = fix?.lat,
        gpsLng = fix?.lng,
        geo = geo(),
        locations = locations(),
    )
}
