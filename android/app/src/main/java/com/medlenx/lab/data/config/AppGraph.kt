package com.medlenx.lab.data.config

import android.content.Context
import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.local.MedLenXDatabase
import com.medlenx.lab.data.local.ProfileDao
import com.medlenx.lab.data.remote.MedLenXVlClient
import com.medlenx.lab.data.repo.DeviceStateRepository
import com.medlenx.lab.data.repo.LocationRepository
import com.medlenx.lab.data.repo.ScanRepository
import kotlinx.serialization.json.Json

/**
 * Manual composition root.
 *
 * Hilt was deliberately not adopted: this module was authored in an environment with
 * no JDK, Gradle or Android SDK available, so every annotation processor beyond the
 * Room KSP processor is an unverified way for the first build to fail. Swapping in
 * Hilt later is mechanical — the graph below is already a single seam.
 */
class AppGraph private constructor(
    val json: Json,
    val database: MedLenXDatabase,
    val catalogue: AssetCatalogue,
    val vlClient: MedLenXVlClient,
    val deviceState: DeviceStateRepository,
    val locationRepository: LocationRepository,
    val scanRepository: ScanRepository,
    val profileDao: ProfileDao,
) {
    companion object {
        fun create(context: Context): AppGraph {
            val appContext = context.applicationContext
            val json = MedLenXVlClient.defaultJson()
            val db = MedLenXDatabase.get(appContext)
            val vlClient = MedLenXVlClient(
                httpClient = MedLenXVlClient.defaultHttpClient(),
                json = json,
            )
            return AppGraph(
                json = json,
                database = db,
                catalogue = AssetCatalogue(appContext, json, db.medexDao()),
                vlClient = vlClient,
                deviceState = DeviceStateRepository(appContext, db.queueDao()),
                locationRepository = LocationRepository(
                    context = appContext,
                    assets = AssetCatalogue(appContext, json, db.medexDao()),
                ),
                scanRepository = ScanRepository(
                    vlClient = vlClient,
                    prescriptionDao = db.prescriptionDao(),
                    queueDao = db.queueDao(),
                    medexDao = db.medexDao(),
                ),
                profileDao = db.profileDao(),
            )
        }
    }
}
