package com.medlenx.lab.ui.screens.team

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.local.DoctorTargetRow
import com.medlenx.lab.data.local.DoctorTierRow
import com.medlenx.lab.data.local.DoctorVisitRow
import com.medlenx.lab.data.local.OffTerritoryRow
import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.local.StewardshipRow
import com.medlenx.lab.data.repo.BrandTarget
import com.medlenx.lab.data.repo.Centroid
import com.medlenx.lab.data.repo.GeoRegion
import com.medlenx.lab.data.repo.RsmTrends
import com.medlenx.lab.data.repo.ScanPoint
import com.medlenx.lab.data.repo.TargetProgress
import com.medlenx.lab.data.repo.TeamMetrics
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs the Territory Manager / RSM command screen.
 *
 * Everything here is an aggregate over *this device's* audits. The web app runs
 * the same queries server-side across a 50+ MPO team; a standalone build can only
 * see the scans it took itself, so the hero reports one officer rather than a
 * team roster. The numbers are real, just narrower.
 */
class TeamViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MedLenXApp
    private val prescriptionDao = app.graph.database.prescriptionDao()

    /** `get_doctor_tiers(days=30)`. */
    private val days = 30
    private val windowMillis = days * 24L * 60 * 60 * 1000

    var officerProfile by mutableStateOf<OfficerProfileEntity?>(null)
        private set

    /**
     * The company the tiering SQL actually matched on, captured at query time.
     *
     * Exposing `officerProfile?.company` instead would let the header disagree
     * with the rows below it while a profile change is in flight.
     */
    var ownCompanyUsed by mutableStateOf("")
        private set

    /** Raw DAO rows; the tier filter is applied on top without re-querying. */
    private var tierRows by mutableStateOf<List<DoctorTierRow>>(emptyList())
    private var stewardshipRows by mutableStateOf<List<StewardshipRow>>(emptyList())

    /** Guards against re-running the aggregates when the company is unchanged. */
    private var lastOwnCompany: String? = null
    private var haveLoadedOnce = false

    var tierFilter by mutableStateOf("")
        private set

    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * `get_doctor_tiers`. Own-brand matching uses the first token of the officer's
     * company, so `LIKE '%token%'` has to be built here rather than in the query.
     */
    val tiering get() = TeamMetrics.doctorTiers(
        rows = tierRows,
        ownCompany = ownCompanyUsed,
        days = days,
        tier = tierFilter,
    )

    /** `get_stewardship_summary`. */
    val stewardship get() = TeamMetrics.stewardshipSummary(
        rows = stewardshipRows,
        days = days,
        limit = 100,
    )

    /** `get_scan_points`, after the district-centroid fallback has been applied. */
    var scanPoints by mutableStateOf<List<ScanPoint>>(emptyList())
        private set

    /** `get_rsm_trends` for this officer. */
    var trends by mutableStateOf<RsmTrends?>(null)
        private set

    /** `get_target_progress` for the current calendar month. */
    var targetProgress by mutableStateOf<TargetProgress?>(null)
        private set

    /** `get_geo_heatmap` — the SoV map's regions. */
    var geoRegions by mutableStateOf<List<GeoRegion>>(emptyList())
        private set

    /**
     * District centroids from `data/bd_geo.json` — the map's base layer.
     *
     * These are geography, not metrics: all 64 districts exist whether or not this
     * device has audited anything in them. The heatmap draws them unconditionally so
     * the country is always on screen, and only the data bubbles come and go.
     */
    var districtCentroids by mutableStateOf<Map<String, Centroid>>(emptyMap())
        private set

    /** `find_off_territory_audits`. */
    var offTerritory by mutableStateOf<List<OffTerritoryRow>>(emptyList())
        private set

    var doctorTargets by mutableStateOf<List<DoctorTargetRow>>(emptyList())
        private set
    var visitLog by mutableStateOf<List<DoctorVisitRow>>(emptyList())
        private set

    /** TeamMap's SoV / density-cluster toggle. */
    var mapMode by mutableStateOf(MapMode.SOV)
        private set

    fun updateMapMode(mode: MapMode) {
        mapMode = mode
    }

    /** Removes an RSM doctor detailing target and reloads the tracker. */
    fun removeDoctorTarget(targetId: Long) {
        viewModelScope.launch {
            runCatching { app.graph.profileDao.deleteDoctorTarget(targetId) }
                .onFailure { error = it.message ?: "Could not remove the target" }
            load()
        }
    }

    fun updateTierFilter(tier: String) {
        tierFilter = tier
    }

    fun load() {
        viewModelScope.launch {
            error = null
            runCatching {
                val since = System.currentTimeMillis() - windowMillis
                // Read the profile here rather than from `officerProfile`: the
                // Flow collection below has not necessarily emitted yet when
                // `init` kicks off the first load, and reading the stale field
                // would silently tier every brand against the Python default
                // company instead of the officer's own.
                val ownCompany = app.graph.profileDao.current()?.company.orEmpty()
                ownCompanyUsed = ownCompany
                // `own_token = own_company.split()[0]`, then `%token%`.
                val ownToken = ownCompany.ifBlank { TeamMetrics.DEFAULT_OWN_COMPANY }
                    .split(" ").first()
                tierRows = prescriptionDao.doctorTierRows(
                    since = since,
                    ownLike = "%$ownToken%",
                    minRx = 1,
                    limit = 200,
                )
                stewardshipRows = prescriptionDao.stewardshipRows(since = since)

                val centroids = app.graph.locationRepository.geo().districts
                    .mapValues { Centroid(it.value.lat, it.value.lng) }
                districtCentroids = centroids
                // Same `%token%` own-brand matcher the tiering query above uses.
                val ownLike = "%$ownToken%"
                geoRegions = TeamMetrics.geoHeatmap(
                    rows = prescriptionDao.geoRegionRows(since = since, ownLike = ownLike),
                    centroids = centroids,
                )
                scanPoints = TeamMetrics.scanPoints(
                    rows = prescriptionDao.scanPointRows(since = since, limit = 2000),
                    centroids = centroids,
                )
                trends = TeamMetrics.rsmTrends(
                    rows = prescriptionDao.trendRows(since = since),
                    ownCompany = ownCompany,
                    days = days,
                    baseMillis = since,
                )
                offTerritory = prescriptionDao.offTerritoryRows(since = since, limit = 50)

                // `get_target_progress` measures the calendar month, not a rolling
                // 30 days, so it gets its own boundary.
                val monthStart = LocalDate.now().withDayOfMonth(1)
                    .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
                targetProgress = TeamMetrics.targetProgress(
                    targets = app.graph.profileDao.observeTargets().first()
                        .map { BrandTarget(it.brand, it.target) },
                    captured = prescriptionDao.brandCapturedRows(since = monthStart),
                    month = month,
                )
                doctorTargets = app.graph.profileDao.doctorTargetRows()
                visitLog = app.graph.profileDao.recentVisits(limit = 20)
            }.onFailure { e ->
                error = e.message ?: "Could not load team aggregates"
            }
            loaded = true
        }
    }

    init {
        viewModelScope.launch {
            app.graph.profileDao.observe().collect { profile ->
                officerProfile = profile
                // The own-brand token feeds the tiering SQL, so changing the
                // company on the Settings screen has to re-run the aggregates.
                // `haveLoadedOnce` matters: on a fresh install the first emission
                // is null, and null != null would skip the load entirely.
                if (!haveLoadedOnce || profile?.company != lastOwnCompany) {
                    haveLoadedOnce = true
                    lastOwnCompany = profile?.company
                    load()
                }
            }
        }
    }
}

class TeamViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        TeamViewModel(application) as T
}

/** TeamMap's two clustering modes, matching the web app's segmented control. */
enum class MapMode(val label: String) {
    SOV("SoV"),
    DENSITY("Density clusters"),
}
