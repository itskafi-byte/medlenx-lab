package com.medlenx.lab.ui.screens.analytics

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.local.ScannedMedicineEntity
import com.medlenx.lab.data.local.BrandDoctorRow
import com.medlenx.lab.data.local.DrillCountRow
import com.medlenx.lab.data.export.ExportDocuments
import com.medlenx.lab.data.local.FilterOptions
import com.medlenx.lab.data.local.FilterState
import com.medlenx.lab.data.local.LiveScanFeedRow
import com.medlenx.lab.data.local.PrescriptionEntity
import com.medlenx.lab.data.repo.AnalyticsMetrics
import com.medlenx.lab.data.repo.CompanySlice
import com.medlenx.lab.data.repo.DashboardKpis
import com.medlenx.lab.data.repo.DoctorLeader
import com.medlenx.lab.data.repo.GenericMatrix
import com.medlenx.lab.data.repo.MostPrescribed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * Backs the Analytics dashboard.
 *
 * Until now this screen rendered the Figma export's hardcoded arrays
 * (`AnalyticsData.kt`); it now reads the on-device aggregates ported from
 * `get_dashboard_kpis`, `get_most_prescribed_medicines`, `get_company_share`
 * and `get_top_doctor_prescribers`.
 *
 * The global filter bar's district / territory / specialty / MR dimensions are
 * **not** applied yet — the FilterSheet that would set them is still unbuilt, so
 * every aggregate runs unfiltered over a 30-day window.
 */
class AnalyticsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MedLenXApp
    private val prescriptionDao = app.graph.database.prescriptionDao()

    /** The global filter bar's dimensions. Null dimensions contribute no clause. */
    var filters by mutableStateOf(FilterState.None)
        private set

    var filterOptions by mutableStateOf(FilterOptions(emptyList(), emptyList(), emptyList(), emptyList()))
        private set

    /** Widget C page size, matching `get_top_doctor_prescribers(limit=10)`. */
    private val pageSize = 10

    var kpis by mutableStateOf<DashboardKpis?>(null)
        private set
    var mostPrescribed by mutableStateOf<List<MostPrescribed>>(emptyList())
        private set
    var companyShare by mutableStateOf<List<CompanySlice>>(emptyList())
        private set

    /** Widget D — generic vs brand matrix by specialty. */
    var genericMatrix by mutableStateOf<GenericMatrix?>(null)
        private set
    var leaders by mutableStateOf<List<DoctorLeader>>(emptyList())
        private set

    /** Total matching doctors, so the pagination caption is real rather than "1–3 of 42". */
    var leaderTotal by mutableIntStateOf(0)
        private set
    var leaderOffset by mutableIntStateOf(0)
        private set

    var liveScans by mutableStateOf<List<LiveScanFeedRow>>(emptyList())
        private set

    /** Page size for the Live Recent Scans feed — the export showed 25 per page. */
    private val livePageSize = 25
    var liveOffset by mutableIntStateOf(0)
        private set
    var liveTotal by mutableIntStateOf(0)
        private set

    val livePageLabel: String
        get() = if (liveTotal == 0) "No medicines scanned yet"
        else "${liveOffset + 1}–${minOf(liveOffset + livePageSize, liveTotal)} of $liveTotal medicines"

    /** Chamber chip state for the live feed. */
    var chamberFilter by mutableStateOf(ChamberFilter.ALL)
        private set

    fun updateChamberFilter(filter: ChamberFilter) {
        chamberFilter = filter
        liveOffset = 0
    }

    /** The visible slice of the fetched rows, after the chamber filter. */
    val livePage: List<LiveScanFeedRow>
        get() = liveScans
            .filter { chamberFilter.matches(it.prescriptionSource) }
            .drop(liveOffset)
            .take(livePageSize)

    val canLivePrev: Boolean get() = liveOffset > 0
    val canLiveNext: Boolean get() = liveOffset + livePageSize < liveTotal

    fun pageLiveScans(forward: Boolean) {
        val next = if (forward) liveOffset + livePageSize else liveOffset - livePageSize
        if (next < 0 || next >= liveTotal) return
        liveOffset = next
        viewModelScope.launch {
            runCatching { liveScans = prescriptionDao.liveScanRows(limit = next + livePageSize) }
                .onFailure { error = it.message ?: "Could not page the live feed" }
        }
    }
    var recentPrescriptions by mutableStateOf<List<PrescriptionEntity>>(emptyList())
        private set

    /**
     * Item breakdown for a tapped Recent Prescriptions row, or null when closed.
     *
     * The web build renders the row with a "tap for item breakdown" caption but never
     * passes its own `onSelect` prop, so the tap is inert there. Here it loads the
     * real `scanned_medicines` rows for that prescription.
     */
    var breakdown by mutableStateOf<List<ScannedMedicineEntity>?>(null)
        private set

    /** Doctor name for the row whose breakdown is open, so the sheet can title itself. */
    var breakdownDoctor by mutableStateOf("")
        private set

    fun showBreakdown(row: RecentRxRow) {
        breakdownDoctor = row.doctor
        viewModelScope.launch {
            breakdown = runCatching { prescriptionDao.medicinesFor(row.id) }
                .getOrDefault(emptyList())
        }
    }

    fun dismissBreakdown() {
        breakdown = null
    }

    var loaded by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val leaderPageLabel: String
        get() {
            if (leaderTotal == 0) return "No doctors yet"
            val from = leaderOffset + 1
            val to = minOf(leaderOffset + pageSize, leaderTotal)
            return "$from–$to of $leaderTotal"
        }

    val canPagePrev: Boolean get() = leaderOffset > 0
    val canPageNext: Boolean get() = leaderOffset + pageSize < leaderTotal

    fun pageLeaders(forward: Boolean) {
        val next = if (forward) leaderOffset + pageSize else leaderOffset - pageSize
        if (next < 0 || next >= leaderTotal) return
        leaderOffset = next
        loadLeaderPage()
    }

    fun updateFilters(next: FilterState) {
        filters = next
        leaderOffset = 0
        load()
    }

    fun clearFilters() = updateFilters(FilterState.None)

    /**
     * The window the current `days` filter selects, in millis.
     *
     * Shared with [load] so the drill-down and the dashboard cannot drift onto
     * different windows: a null `days` is "all time", which the Python gets by
     * emitting no clause, and which here means a span reaching back to the epoch.
     */
    private fun filterSpan(): Long =
        (filters.days?.let { it * 24L * 60 * 60 * 1000 } ?: System.currentTimeMillis())

    /**
     * The CSV payload for the dashboard's Export button — the web's
     * `/api/export/recent-medicines.csv`.
     *
     * Suspends and runs the row-building on [Dispatchers.Default]: the query
     * itself hops off the main thread inside Room, but assembling up to
     * [EXPORT_LIMIT] rows into one string would otherwise land back on it.
     */
    suspend fun buildExportCsv(): String = withContext(Dispatchers.Default) {
        val f = filters
        ExportDocuments.recentMedicinesCsv(
            prescriptionDao.recentMedicineExportRows(
                since = System.currentTimeMillis() - filterSpan(),
                limit = EXPORT_LIMIT,
                district = f.district,
                territory = f.territory,
                specialty = f.specialty,
                mrId = f.mrId,
            ),
        )
    }

    /** The open drill-down, or null when the modal is closed. */
    var drilldown by mutableStateOf<Drilldown?>(null)
        private set

    var drilldownLoading by mutableStateOf(false)
        private set

    fun closeDrilldown() {
        drillJob?.cancel()
        drillJob = null
        drilldownLoading = false
        drilldown = null
    }

    /**
     * A donut slice (or its legend row) was tapped.
     *
     * Runs on its own job rather than inside [load] so opening a drill-down
     * cannot interleave with a dashboard refresh, and so a slow drill-down is
     * cancelled by closing the modal instead of landing into a closed one.
     */
    fun openCompanyDrilldown(company: String) {
        if (company.isBlank()) return
        openDrilldown { f, since ->
            val generics = prescriptionDao.companyGenerics(
                company, since, DRILL_LIMIT, f.district, f.territory, f.specialty, f.mrId,
            )
            Drilldown.Company(
                company = company,
                total = generics.sumOf { it.count },
                generics = generics,
                brands = prescriptionDao.companyBrands(
                    company, since, DRILL_LIMIT, f.district, f.territory, f.specialty, f.mrId,
                ),
                doctors = prescriptionDao.companyDoctors(
                    company, since, DRILL_LIMIT, f.district, f.territory, f.specialty, f.mrId,
                ),
            )
        }
    }

    /** A bar in chart A was tapped. */
    fun openBrandDrilldown(brand: String) {
        if (brand.isBlank()) return
        openDrilldown { f, since ->
            Drilldown.Brand(
                brand = brand,
                doctors = prescriptionDao.brandDoctors(
                    brand, since, DRILL_LIMIT, f.district, f.territory, f.specialty, f.mrId,
                ),
            )
        }
    }

    private var drillJob: kotlinx.coroutines.Job? = null

    /**
     * `filters` is read inside the coroutine rather than passed in, so the modal
     * always reflects the filter bar as it stands when it is opened.
     */
    private inline fun openDrilldown(
        crossinline fetch: suspend (FilterState, Long) -> Drilldown,
    ) {
        drillJob?.cancel()
        drilldown = null
        drilldownLoading = true
        drillJob = viewModelScope.launch {
            val result = runCatching { fetch(filters, System.currentTimeMillis() - filterSpan()) }
            drilldownLoading = false
            result
                .onSuccess { drilldown = it }
                .onFailure { e -> error = e.message ?: "Could not load the drill-down" }
        }
    }

    init {
        // Without this the dashboard is empty until the user opens the FilterSheet
        // and applies a filter, because `load()` is otherwise only called from
        // `updateFilters`.
        load()
    }

    fun load() {
        viewModelScope.launch {
            error = null
            runCatching {
                val f = filters
                filterOptions = FilterOptions(
                    districts = prescriptionDao.filterDistricts(),
                    territories = prescriptionDao.filterTerritories(),
                    specialties = prescriptionDao.filterSpecialties(),
                    mrIds = prescriptionDao.filterMrIds(),
                )
                val now = System.currentTimeMillis()
                val span = filterSpan()
                val since = now - span
                val prevStart = since - span
                val ownCompany = app.graph.profileDao.current()?.company.orEmpty()
                val ownToken = ownCompany.ifBlank { AnalyticsMetrics.DEFAULT_OWN_COMPANY }
                    .split(" ").first()
                val ownLike = "%$ownToken%"

                val itemsTotal = prescriptionDao.itemCountSince(since, f.district, f.territory, f.specialty, f.mrId)
                kpis = AnalyticsMetrics.dashboardKpis(
                    totalToday = prescriptionDao.prescriptionCountSince(startOfToday(), f.district, f.territory, f.specialty, f.mrId),
                    totalWeek = prescriptionDao.prescriptionCountSince(now - 7L * 24 * 60 * 60 * 1000, f.district, f.territory, f.specialty, f.mrId),
                    totalMonth = prescriptionDao.prescriptionCountSince(now - 30L * 24 * 60 * 60 * 1000, f.district, f.territory, f.specialty, f.mrId),
                    totalAll = prescriptionDao.prescriptionCountAll(f.district, f.territory, f.specialty, f.mrId),
                    scansCur = prescriptionDao.prescriptionCountSince(since, f.district, f.territory, f.specialty, f.mrId),
                    scansPrev = prescriptionDao.prescriptionCountBetween(prevStart, since, f.district, f.territory, f.specialty, f.mrId),
                    itemsTotal = itemsTotal,
                    ownCount = prescriptionDao.ownItemCountSince(since, ownLike, f.district, f.territory, f.specialty, f.mrId),
                    prevItems = prescriptionDao.itemCountBetween(prevStart, since, f.district, f.territory, f.specialty, f.mrId),
                    prevOwn = prescriptionDao.ownItemCountBetween(prevStart, since, ownLike, f.district, f.territory, f.specialty, f.mrId),
                    topBrand = prescriptionDao.topBrandRow(since, f.district, f.territory, f.specialty, f.mrId),
                    activeDoctors = prescriptionDao.activeDoctorCount(since, f.district, f.territory, f.specialty, f.mrId),
                    totalDoctors = prescriptionDao.allDoctorCount(f.district, f.territory, f.specialty, f.mrId),
                    ownCompanyName = ownCompany,
                )

                mostPrescribed = AnalyticsMetrics.mostPrescribed(
                    prescriptionDao.mostPrescribedRows(since = since, limit = 10, district = f.district, territory = f.territory, specialty = f.specialty, mrId = f.mrId),
                )
                companyShare = AnalyticsMetrics.companyShare(
                    prescriptionDao.companyShareRows(since = since, district = f.district, territory = f.territory, specialty = f.specialty, mrId = f.mrId),
                )
                genericMatrix = AnalyticsMetrics.genericBrandMatrix(
                    prescriptionDao.genericMatrixRows(f.district, f.territory, f.specialty, f.mrId),
                )
                leaderTotal = prescriptionDao.doctorLeaderTotal(since = since, district = f.district, territory = f.territory, specialty = f.specialty, mrId = f.mrId)
                if (leaderOffset >= leaderTotal) leaderOffset = 0
                loadLeaderPageSync(since, ownLike)

                liveTotal = prescriptionDao.medicineCount()
                liveScans = prescriptionDao.liveScanRows(limit = livePageSize)
                recentPrescriptions = prescriptionDao.observeRecent(limit = 8).first()
            }.onFailure { e ->
                error = e.message ?: "Could not load analytics"
            }
            loaded = true
        }
    }

    private suspend fun loadLeaderPageSync(since: Long, ownLike: String) {
        val f = filters
        leaders = AnalyticsMetrics.doctorLeaders(
            rows = prescriptionDao.doctorLeaderRows(
                since = since,
                ownLike = ownLike,
                limit = pageSize,
                offset = leaderOffset,
                district = f.district,
                territory = f.territory,
                specialty = f.specialty,
                mrId = f.mrId,
            ),
            total = leaderTotal,
            limit = pageSize,
            offset = leaderOffset,
        ).doctors
    }

    private fun loadLeaderPage() {
        viewModelScope.launch {
            runCatching {
                val span = filters.days?.let { it * 24L * 60 * 60 * 1000 }
                    ?: System.currentTimeMillis()
                val since = System.currentTimeMillis() - span
                val ownCompany = app.graph.profileDao.current()?.company.orEmpty()
                val ownToken = ownCompany.ifBlank { AnalyticsMetrics.DEFAULT_OWN_COMPANY }
                    .split(" ").first()
                loadLeaderPageSync(since, "%$ownToken%")
            }.onFailure { error = it.message ?: "Could not page the leaderboard" }
        }
    }

    private fun startOfToday(): Long =
        java.time.LocalDate.now()
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
}

class AnalyticsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        AnalyticsViewModel(application) as T
}

/**
 * The web uses `limit=10` for the company drill-down and `limit=15` for brand
 * doctors. One value covers both so a company's three lists stay the same length
 * and the modal does not reflow between its sections.
 */
private const val DRILL_LIMIT = 15

/** The web's `limit=5000` default on `/api/export/recent-medicines.csv`. */
private const val EXPORT_LIMIT = 5000
