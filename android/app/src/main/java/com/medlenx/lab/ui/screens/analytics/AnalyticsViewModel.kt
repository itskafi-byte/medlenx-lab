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
import com.medlenx.lab.data.repo.Intelligence
import com.medlenx.lab.data.repo.MostPrescribed
import com.medlenx.lab.data.repo.RxAudit
import com.medlenx.lab.ui.screens.rx.classBreakdownOf
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
 * Every aggregate runs through [FilterState] — the global filter bar's district /
 * territory / specialty / MR / source dimensions and its day window, matching the
 * web's `filterQuery()` and `_filter_sql` (`database.py:1408`). A null dimension
 * contributes no clause; the day window sets the `since` bound, and the
 * comparison period is the equally long window immediately before it
 * (`prevStart = since - span`, :334).
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
     * The Prescription Audit Summary for a tapped Recent Prescriptions row, or null
     * when the drawer is closed.
     *
     * The web build renders the row with a "tap for item breakdown" caption but never
     * passes its own `onSelect` prop, so the tap is inert there; the drawer it would
     * have opened is the one this loads. One pass assembles the whole payload the way
     * `GET /api/prescriptions/{id}` does (`main.py:928`), because the drawer's pill
     * counts, its clinical strip and its footer share all have to describe the same
     * list of items.
     */
    var breakdown by mutableStateOf<RxAuditDrawer?>(null)
        private set

    /** True while the drawer's payload is loading, so the sheet can show its spinner. */
    var breakdownLoading by mutableStateOf(false)
        private set

    /**
     * Why the drawer has nothing to show, or null when it does.
     *
     * Kept separate from [error] because the drawer renders it *inside itself*: the
     * sheet is already open by the time the load can fail, so pushing the message into
     * the screen's error line would leave the user looking at a closed drawer and no
     * explanation. The web does the same thing with a toast before closing
     * (`index.html:2784`).
     */
    var breakdownError by mutableStateOf<String?>(null)
        private set

    fun showBreakdown(row: RecentRxRow) {
        breakdownLoading = true
        breakdownError = null
        viewModelScope.launch {
            val loaded = runCatching { loadDrawer(row.id) }.getOrNull()
            breakdown = loaded
            breakdownLoading = false
            if (loaded == null) {
                breakdownError = "Could not load the audit summary for ${row.doctor}'s " +
                    "prescription. The items may not have been stored."
            }
        }
    }

    fun dismissBreakdown() {
        breakdown = null
        breakdownLoading = false
        breakdownError = null
    }

    /**
     * Assembles the drawer payload.
     *
     * Every field the drawer shows either comes from storage or is derived here, never
     * from the UI: [RxAudit.lineOf] normalises the confidence and folds `type`/`form`,
     * and the portfolio matches are computed now for rows saved before substitutions
     * existed - which is exactly what the web does when `sub` is missing from
     * `medicines_json` (`main.py:969`).
     *
     * The own-company test is the loose matcher, not the entity's stored `isOwn`: the
     * web recomputes `is_own` from the saved company (`main.py:947`) so that the row
     * badge and the footer share below it can never disagree.
     */
    private suspend fun loadDrawer(prescriptionId: Long): RxAuditDrawer? {
        val prescription = prescriptionDao.byId(prescriptionId) ?: return null
        val rows = runCatching { prescriptionDao.medicinesFor(prescriptionId) }
            .getOrDefault(emptyList())
        val ownCompany = app.graph.profileDao.current()?.company.orEmpty()
        val lines = rows.map { RxAudit.lineOf(it) }

        // Only fetched when there is something to pitch; a drawer opened on an
        // empty prescription should not pay for the 25K-row catalogue.
        val portfolios = if (ownCompany.isBlank()) {
            List(lines.size) { null }
        } else {
            val index = runCatching { app.graph.scanRepository.medexIndex() }.getOrNull()
            val regulatory = app.graph.regulatoryRepository.data()
            lines.mapIndexed { i, line ->
                val company = line.company.orEmpty()
                if (line.generic.isBlank() || RxAudit.sameCompanyLoose(company, ownCompany)) {
                    null
                } else {
                    runCatching {
                        Intelligence.genericSubstitution(
                            data = regulatory,
                            detectedBrand = line.brand,
                            detectedCompany = company,
                            detectedGeneric = line.generic,
                            detectedStrength = line.strength,
                            detectedType = line.type,
                            detectedImageUrl = rows.getOrNull(i)?.imageUrl,
                            ownCompany = ownCompany,
                            medexDb = index?.all.orEmpty(),
                        )
                    }.getOrNull()
                }
            }
        }

        return RxAuditDrawer(
            prescription = prescription,
            lines = lines,
            portfolios = portfolios,
            ownCompany = ownCompany,
            marketShare = RxAudit.buildMarketShare(lines, ownCompany),
            // The web groups on `therapeutic_class`, which the saved row carries from
            // the scan-time enrichment; a blank one folds into "Other" exactly as the
            // web's classBreakdown does.
            slices = classBreakdownOf(rows.map { it.therapeuticClass }),
            duplicateOf = prescription.duplicateOf?.let { original ->
                runCatching { prescriptionDao.byId(original) }.getOrNull()
            },
        )
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
                source = f.source,
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
                company, since, COMPANY_DRILL_LIMIT,
                f.district, f.territory, f.specialty, f.mrId, f.source,
            )
            Drilldown.Company(
                company = company,
                // Both totals, so the dialog can show the true count and still
                // explain where the web's smaller figure comes from.
                totalItems = prescriptionDao.companyItemCount(
                    company, since, f.district, f.territory, f.specialty, f.mrId, f.source,
                ),
                topGenericItems = generics.sumOf { it.count },
                generics = generics,
                brands = prescriptionDao.companyBrands(
                    company, since, COMPANY_DRILL_LIMIT,
                    f.district, f.territory, f.specialty, f.mrId, f.source,
                ),
                doctors = prescriptionDao.companyDoctors(
                    company, since, COMPANY_DRILL_LIMIT,
                    f.district, f.territory, f.specialty, f.mrId, f.source,
                ),
            )
        }
    }

    /**
     * The donut's "Others" slice was tapped.
     *
     * Nothing is queried: the bucket's members are already on the slice, put
     * there by `AnalyticsMetrics.companyShare` when it collapsed them. Opening it
     * is presentation, not retrieval, so it does not go through [openDrilldown]
     * and cannot fail.
     */
    fun openOthersDrilldown(members: List<String>) {
        drillJob?.cancel()
        drillJob = null
        drilldownLoading = false
        drilldown = Drilldown.Bucket(label = "Others", members = members)
    }

    /** A bar in chart A was tapped. */
    fun openBrandDrilldown(brand: String) {
        if (brand.isBlank()) return
        openDrilldown { f, since ->
            Drilldown.Brand(
                brand = brand,
                doctors = prescriptionDao.brandDoctors(
                    brand, since, BRAND_DOCTOR_LIMIT,
                    f.district, f.territory, f.specialty, f.mrId, f.source,
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

                val itemsTotal = prescriptionDao.itemCountSince(since, f.district, f.territory, f.specialty, f.mrId, f.source)
                kpis = AnalyticsMetrics.dashboardKpis(
                    totalToday = prescriptionDao.prescriptionCountSince(startOfToday(), f.district, f.territory, f.specialty, f.mrId, f.source),
                    totalWeek = prescriptionDao.prescriptionCountSince(now - 7L * 24 * 60 * 60 * 1000, f.district, f.territory, f.specialty, f.mrId, f.source),
                    totalMonth = prescriptionDao.prescriptionCountSince(now - 30L * 24 * 60 * 60 * 1000, f.district, f.territory, f.specialty, f.mrId, f.source),
                    totalAll = prescriptionDao.prescriptionCountAll(f.district, f.territory, f.specialty, f.mrId, f.source),
                    scansCur = prescriptionDao.prescriptionCountSince(since, f.district, f.territory, f.specialty, f.mrId, f.source),
                    scansPrev = prescriptionDao.prescriptionCountBetween(prevStart, since, f.district, f.territory, f.specialty, f.mrId, f.source),
                    itemsTotal = itemsTotal,
                    ownCount = prescriptionDao.ownItemCountSince(since, ownLike, f.district, f.territory, f.specialty, f.mrId, f.source),
                    prevItems = prescriptionDao.itemCountBetween(prevStart, since, f.district, f.territory, f.specialty, f.mrId, f.source),
                    prevOwn = prescriptionDao.ownItemCountBetween(prevStart, since, ownLike, f.district, f.territory, f.specialty, f.mrId, f.source),
                    topBrand = prescriptionDao.topBrandRow(since, f.district, f.territory, f.specialty, f.mrId, f.source),
                    activeDoctors = prescriptionDao.activeDoctorCount(since, f.district, f.territory, f.specialty, f.mrId, f.source),
                    totalDoctors = prescriptionDao.allDoctorCount(f.district, f.territory, f.specialty, f.mrId, f.source),
                    ownCompanyName = ownCompany,
                )

                mostPrescribed = AnalyticsMetrics.mostPrescribed(
                    prescriptionDao.mostPrescribedRows(since = since, limit = 10, district = f.district, territory = f.territory, specialty = f.specialty, mrId = f.mrId, source = f.source),
                )
                companyShare = AnalyticsMetrics.companyShare(
                    prescriptionDao.companyShareRows(since = since, district = f.district, territory = f.territory, specialty = f.specialty, mrId = f.mrId, source = f.source),
                )
                genericMatrix = AnalyticsMetrics.genericBrandMatrix(
                    prescriptionDao.genericMatrixRows(f.district, f.territory, f.specialty, f.mrId, f.source),
                )
                leaderTotal = prescriptionDao.doctorLeaderTotal(since = since, district = f.district, territory = f.territory, specialty = f.specialty, mrId = f.mrId, source = f.source)
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
                source = f.source,
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
 * The web's two limits, kept distinct.
 *
 * `/api/dashboard/company-drilldown` defaults to `limit=10` and
 * `/api/dashboard/brand-doctors` to `limit=15`. They were briefly unified on 15
 * for layout reasons, which quietly changed what the company modal showed against
 * the web; they are the endpoint defaults again.
 */
private const val COMPANY_DRILL_LIMIT = 10
private const val BRAND_DOCTOR_LIMIT = 15

/** The web's `limit=5000` default on `/api/export/recent-medicines.csv`. */
private const val EXPORT_LIMIT = 5000
