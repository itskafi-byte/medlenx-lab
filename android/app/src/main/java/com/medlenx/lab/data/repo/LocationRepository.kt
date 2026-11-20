package com.medlenx.lab.data.repo

import android.annotation.SuppressLint
import android.Manifest
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.os.Build
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.location.LocationManager
import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.model.BdGeo
import com.medlenx.lab.data.model.BdLocations
import com.medlenx.lab.data.model.GpsFix
import com.medlenx.lab.data.model.GpsSource
import com.medlenx.lab.data.model.TerritoryVerdict
import java.io.File
import java.lang.SecurityException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.coroutines.resume
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

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasFinePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(manager: LocationManager): List<String> = buildList {
        // Network location is generally more useful indoors and supports approximate permission.
        if (runCatching { manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) }.getOrDefault(false)) add(LocationManager.NETWORK_PROVIDER)
        if (hasFinePermission() && runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)) add(LocationManager.GPS_PROVIDER)
    }

    /** Return only a recent cached fix; older locations must not silently represent a new visit. */
    @SuppressLint("MissingPermission")
    suspend fun lastKnownFix(): GpsFix? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission()) return@withContext null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return@withContext null
        val now = SystemClock.elapsedRealtimeNanos()
        val location = providers(manager).mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { it.elapsedRealtimeNanos > 0 && now >= it.elapsedRealtimeNanos && now - it.elapsedRealtimeNanos <= 300_000_000_000L }
            .maxByOrNull { it.elapsedRealtimeNanos } ?: return@withContext null
        GpsFix(location.latitude, location.longitude)
    }

    /** Request a fresh one-shot fix; cancellation and timeout remove the platform request. */
    @SuppressLint("MissingPermission")
    suspend fun currentFix(): GpsFix? {
        if (!hasLocationPermission()) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        for (provider in providers(manager)) {
            val requestedAt = SystemClock.elapsedRealtimeNanos()
            val fix = withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine<GpsFix?> { continuation ->
                try {
                    if (Build.VERSION.SDK_INT >= 30) {
                        val signal = CancellationSignal()
                        continuation.invokeOnCancellation { signal.cancel() }
                        manager.getCurrentLocation(provider, signal, ContextCompat.getMainExecutor(context)) { location ->
                            if (continuation.isActive) continuation.resume(location?.takeIf { it.elapsedRealtimeNanos >= requestedAt }?.let { GpsFix(it.latitude, it.longitude) })
                        }
                    } else {
                        val listener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                runCatching { manager.removeUpdates(this) }
                                if (continuation.isActive) continuation.resume(location.takeIf { it.elapsedRealtimeNanos >= requestedAt }?.let { GpsFix(it.latitude, it.longitude) })
                            }
                            @Deprecated("platform callback")
                            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
                            override fun onProviderEnabled(provider: String) = Unit
                            override fun onProviderDisabled(provider: String) {
                                runCatching { manager.removeUpdates(this) }
                                if (continuation.isActive) continuation.resume(null)
                            }
                        }
                        continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                        @Suppress("DEPRECATION")
                        manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
                        if (!continuation.isActive) runCatching { manager.removeUpdates(listener) }
                    }
                } catch (_: SecurityException) {
                    if (continuation.isActive) continuation.resume(null)
                } catch (_: RuntimeException) {
                    if (continuation.isActive) continuation.resume(null)
                }
            }
            }
            if (fix != null) return fix
        }
        return null
    }

    private val geocodeExecutor = Executors.newFixedThreadPool(2) { runnable ->
        Thread(runnable, "MedLenX-geocoder").apply { isDaemon = true }
    }

    /** Reverse geocoding is optional, bounded, and may be unavailable even when online. */
    private suspend fun reverseGeocode(fix: GpsFix): Address? {
        if (!runCatching { Geocoder.isPresent() }.getOrDefault(false)) return null
        return withTimeoutOrNull(4_000L) {
            suspendCancellableCoroutine<Address?> { continuation ->
                val task = geocodeExecutor.submit {
                    try {
                        val geocoder = Geocoder(context, Locale.ENGLISH)
                        if (Build.VERSION.SDK_INT >= 33) {
                            geocoder.getFromLocation(fix.lat, fix.lng, 1, object : Geocoder.GeocodeListener {
                                override fun onGeocode(addresses: MutableList<Address>) {
                                    if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                                }
                                override fun onError(errorMessage: String?) {
                                    if (continuation.isActive) continuation.resume(null)
                                }
                            })
                        } else {
                            @Suppress("DEPRECATION")
                            val address = geocoder.getFromLocation(fix.lat, fix.lng, 1)?.firstOrNull()
                            if (continuation.isActive) continuation.resume(address)
                        }
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
                continuation.invokeOnCancellation { task.cancel(true) }
            }
        }
    }

    /** Resolve only names supported by the bundled hierarchy; never invent an upazila. */
    suspend fun resolveAddressFromGps(fix: GpsFix, online: Boolean, assignedTerritory: String): ResolvedLocation {
        val catalogue = locations()
        val district = Geofence.resolveGeoDistrict(fix.lat, fix.lng, geo())
        val address = if (online) reverseGeocode(fix) else null
        return Geofence.resolveHierarchy(district, catalogue, assignedTerritory,
            listOfNotNull(address?.subAdminArea, address?.adminArea?.takeUnless { admin -> catalogue.divisions.any { Geofence.normalize(it) == Geofence.normalize(admin) } }),
            listOfNotNull(address?.subAdminArea, address?.locality, address?.subLocality, address?.thoroughfare))
    }

    /**
     * Falls back to the coordinates embedded in the photo's own EXIF header.
     *
     * Reps scan indoors often enough that the last known fix is stale or the provider
     * is switched off, and the audit then loses its GPS evidence entirely. The photo
     * was taken at the visit, so its coordinates are better evidence than none - and
     * they are labelled as photo EXIF rather than passed off as a live fix.
     *
     * Returns null when there is no GPS tag, which is normal for screenshots and for
     * gallery images that were re-saved without their metadata.
     */
    suspend fun exifFix(file: File): GpsFix? = withContext(Dispatchers.IO) {
        runCatching {
            ExifInterface(file).getLatLong()
                ?.takeIf { it.size >= 2 }
                ?.let { GpsFix(it[0], it[1], GpsSource.PhotoExif) }
        }.getOrNull()
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
