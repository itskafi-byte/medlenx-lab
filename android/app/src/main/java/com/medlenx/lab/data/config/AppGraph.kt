package com.medlenx.lab.data.config

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.local.MedLenXDatabase
import com.medlenx.lab.data.local.ProfileDao
import com.medlenx.lab.data.remote.MedLenXVlClient
import com.medlenx.lab.data.repo.DeviceStateRepository
import com.medlenx.lab.data.repo.LocationRepository
import com.medlenx.lab.data.repo.NewsRepository
import com.medlenx.lab.data.repo.RegulatoryRepository
import com.medlenx.lab.data.repo.MedicineImageStore
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
    val medicineImages: MedicineImageStore,
    val profileDao: ProfileDao,
    val regulatoryRepository: RegulatoryRepository,
    val newsRepository: NewsRepository,
    /**
     * Application-lifetime scope for work that must outlive any screen.
     *
     * There is no ViewModel to hang the catalogue import on: it has to start the
     * moment the process does, because the matcher, the drug index and the
     * enrichment step all read Room and would otherwise see an empty table.
     */
    val scope: CoroutineScope,
) {
    /**
     * Imports the bundled catalogue into Room if it is not already there.
     *
     * Without this call `medex_products` is empty for the life of the process:
     * the matcher finds no brand, so no scanned medicine ever gets a verified
     * company, match type or pack image, and the drug index renders nothing.
     * Idempotent - `ensureImported` no-ops once rows exist.
     */
    fun importCatalogue() {
        scope.launch { catalogue.ensureImported() }
    }

    companion object {
        fun create(context: Context): AppGraph {
            val appContext = context.applicationContext
            val json = MedLenXVlClient.defaultJson()
            val db = MedLenXDatabase.get(appContext)
            val vlClient = MedLenXVlClient(
                httpClient = MedLenXVlClient.defaultHttpClient(),
                json = json,
            )
            val catalogue = AssetCatalogue(appContext, json, db.medexDao())
            return AppGraph(
                json = json,
                database = db,
                catalogue = catalogue,
                vlClient = vlClient,
                deviceState = DeviceStateRepository(
                    context = appContext,
                    queueDao = db.queueDao(),
                    profileDao = db.profileDao(),
                ),
                locationRepository = LocationRepository(
                    context = appContext,
                    // The shared instance, not a spare one: each AssetCatalogue
                    // tracks its own import state, so a second instance would start a
                    // concurrent 25k-row import of the same file.
                    assets = catalogue,
                ),
                scanRepository = ScanRepository(
                    vlClient = vlClient,
                    prescriptionDao = db.prescriptionDao(),
                    doctorDao = db.doctorDao(),
                    queueDao = db.queueDao(),
                    medexDao = db.medexDao(),
                    catalogue = catalogue,
                ),
                medicineImages = MedicineImageStore(
                    catalogue = catalogue,
                    medexDao = db.medexDao(),
                    httpClient = MedLenXVlClient.defaultHttpClient(),
                ),
                profileDao = db.profileDao(),
                regulatoryRepository = RegulatoryRepository(catalogue),
                newsRepository = NewsRepository(
                    catalogue = catalogue,
                    httpClient = MedLenXVlClient.defaultHttpClient(),
                ),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
            )
        }
    }
}
