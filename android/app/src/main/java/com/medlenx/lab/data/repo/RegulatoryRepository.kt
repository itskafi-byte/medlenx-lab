package com.medlenx.lab.data.repo

import com.medlenx.lab.data.local.AssetCatalogue
import com.medlenx.lab.data.model.DgdaPrices
import com.medlenx.lab.data.model.NemlList
import com.medlenx.lab.data.model.RegulatoryData
import com.medlenx.lab.data.model.TripsWaiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads the three regulatory datasets once per process.
 *
 * Replaces the module-level `_NEM_CACHE` / `_TRIPS_CACHE` / `_DGDA_CACHE` dicts
 * in the Python originals with one holder, since on Android the read has to be
 * suspending and the assets are inside the APK.
 */
class RegulatoryRepository(private val catalogue: AssetCatalogue) {

    @Volatile
    private var cached: RegulatoryData? = null

    suspend fun data(): RegulatoryData = cached ?: load().also { cached = it }

    /**
     * A missing file degrades to an empty dataset rather than throwing.
     *
     * That is the Python behaviour (`except: data = {...}`) and it matters: the
     * app must still open a prescription when the assets were not copied in,
     * showing unverified flags instead of crashing on the drawer.
     */
    private suspend fun load(): RegulatoryData = withContext(Dispatchers.IO) {
        val neml = catalogue.readAsset("neml_list.json", NemlList.serializer()) ?: NemlList()
        val trips = catalogue.readAsset("trips_waiver.json", TripsWaiver.serializer())
            ?: TripsWaiver()
        val dgda = catalogue.readAsset("dgda_prices.json", DgdaPrices.serializer())
            ?: DgdaPrices()
        RegulatoryData(
            neml = neml,
            trips = trips,
            dgda = dgda,
            // LinkedHashMap, deliberately: nemlLookup and tripsLookup fall
            // through to a linear scan whose winner depends on iteration order
            // matching the file's.
            nemlIndex = LinkedHashMap<String, com.medlenx.lab.data.model.NemlMolecule>().apply {
                for (m in neml.molecules) put(Compliance.norm(m.molecule), m)
            },
            tripsIndex = LinkedHashMap<String, com.medlenx.lab.data.model.TripsMolecule>().apply {
                for (m in trips.molecules) put(Compliance.norm(m.molecule), m)
            },
        )
    }
}
