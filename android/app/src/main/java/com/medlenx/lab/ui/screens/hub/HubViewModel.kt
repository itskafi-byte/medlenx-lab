package com.medlenx.lab.ui.screens.hub

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
import com.medlenx.lab.data.model.HealthCalendar
import com.medlenx.lab.data.local.ScannedItemRow
import com.medlenx.lab.data.model.MedexProduct
import com.medlenx.lab.data.model.NewsFeed
import com.medlenx.lab.data.model.RegulatoryData
import com.medlenx.lab.data.model.HealthDays
import com.medlenx.lab.data.model.JobBoard
import com.medlenx.lab.data.model.PharmaJobs
import com.medlenx.lab.data.repo.BrowseResult
import com.medlenx.lab.data.repo.PharmaHub
import com.medlenx.lab.data.repo.TripsPortfolio
import com.medlenx.lab.data.repo.TripsPortfolioResult
import com.medlenx.lab.data.repo.toProduct
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Backs the Pharma Intelligence Hub.
 *
 * Both datasets are bundled, so there is no loading spinner to race: the assets
 * are read once and the tabs render straight from them. The web app's live RSS
 * news feed has no equivalent here because the standalone build is offline-first.
 */
class HubViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MedLenXApp
    private val catalogue = app.graph.catalogue
    private val scanRepository = app.graph.scanRepository
    private val regulatory = app.graph.regulatoryRepository
    private val prescriptionDao = app.graph.database.prescriptionDao()
    private val newsRepository = app.graph.newsRepository

    /** Fixed at construction so a Hub left open across midnight does not reshuffle. */
    val today: LocalDate = LocalDate.now()

    /** Epoch millis separating the current TRIPS window from the previous one. */
    private var tripsBoundary by mutableStateOf(System.currentTimeMillis())
        private set

    var healthDays by mutableStateOf<HealthDays?>(null)
        private set
    var jobs by mutableStateOf<PharmaJobs?>(null)
        private set

    /**
     * The catalogue, read once from Room.
     *
     * Empty until the app's catalogue import finishes, so the drug index shows
     * its importing state rather than claiming the catalogue has zero rows.
     */
    var products by mutableStateOf<List<MedexProduct>>(emptyList())
        private set

    var browseCategory by mutableStateOf("top10")
        private set
    var indexQuery by mutableStateOf("")
        private set
    /** Search results; null means "show the browse slice". */
    var searchResults by mutableStateOf<List<MedexProduct>?>(null)
        private set

    /** TRIPS window in days: 30 / 90 / 180. */
    var tripsDays by mutableIntStateOf(90)
        private set
    private var scannedRows by mutableStateOf<List<ScannedItemRow>>(emptyList())
        private set
    private var tripsData by mutableStateOf<RegulatoryData?>(null)
        private set

    var news by mutableStateOf<NewsFeed?>(null)
        private set
    var newsLoading by mutableStateOf(false)
        private set

    /** Month being displayed in the health-day calendar (1-12). */
    var month by mutableIntStateOf(today.monthValue)
        private set

    // Job-board filters; blank means "all".
    var jobCategory by mutableStateOf("")
        private set
    var jobQuery by mutableStateOf("")
        private set
    var jobLocation by mutableStateOf("")
        private set

    init {
        viewModelScope.launch {
            healthDays = catalogue.readAsset("health_days.json", HealthDays.serializer())
            jobs = catalogue.readAsset("pharma_jobs.json", PharmaJobs.serializer())
            products = scanRepository.medexIndex().all
            tripsData = regulatory.data()
            loadScannedRows()
            refreshNews()
        }
    }

    fun calendar(): HealthCalendar = PharmaHub.healthDays(
        data = healthDays ?: HealthDays(),
        today = today,
        year = today.year,
    )

    fun browse(): BrowseResult = PharmaHub.medexBrowse(
        category = browseCategory,
        medexDb = products,
        limit = 24,
    )

    /** The rows the index tab lists: search hits when querying, else the slice. */
    fun indexRows(): List<MedexProduct> = searchResults ?: browse().results

    fun setBrowseCategory(value: String) {
        browseCategory = value
        searchResults = null
    }

    /** Blank query falls back to the curated slice rather than searching for "". */
    fun searchIndex(query: String) {
        indexQuery = query
        val q = query.trim()
        if (q.isEmpty()) {
            searchResults = null
            return
        }
        viewModelScope.launch {
            searchResults = scanRepository.searchCatalogue(q, 40).map { it.toProduct() }
        }
    }

    /**
     * Pulls the scan window for the current TRIPS period.
     *
     * Two windows are fetched in one query: rows at or after `boundary` count
     * towards the current period, the rest towards the previous one.
     */
    private suspend fun loadScannedRows() {
        val windowMs = tripsDays.toLong() * MILLIS_PER_DAY
        val now = System.currentTimeMillis()
        scannedRows = prescriptionDao.scannedSince(now - 2 * windowMs)
        tripsBoundary = now - windowMs
    }

    /**
     * Fetches the live RSS feed and merges it with the bundled seed.
     *
     * A failed fetch is not an error state: the seed still renders, and
     * [NewsFeed.liveCount] being zero is what tells the reader nothing live
     * came through.
     */
    fun refreshNews() {
        newsLoading = true
        viewModelScope.launch {
            news = newsRepository.load(live = true)
            newsLoading = false
        }
    }

    fun setTripsDays(days: Int) {
        tripsDays = days
        viewModelScope.launch { loadScannedRows() }
    }

    fun portfolio(): TripsPortfolioResult {
        val data = tripsData ?: RegulatoryData.Empty
        return TripsPortfolio.build(
            tripsIndex = data.tripsIndex,
            molecules = data.trips.molecules,
            rows = scannedRows,
            boundary = tripsBoundary,
            days = tripsDays,
            waiverExpiry = data.trips.waiverExpiry,
            ldcGraduation = data.trips.ldcGraduation,
            context = data.trips.context,
        )
    }

    fun board(): JobBoard = PharmaHub.pharmaJobs(
        jobs = jobs?.jobs ?: emptyList(),
        today = today,
        category = jobCategory,
        q = jobQuery,
        location = jobLocation,
    )

    /** Wraps within the calendar year rather than reaching into the next one. */
    fun stepMonth(delta: Int) {
        val next = month + delta
        month = when {
            next < 1 -> 12
            next > 12 -> 1
            else -> next
        }
    }

    fun setJobCategory(value: String) { jobCategory = value }
    fun setJobQuery(value: String) { jobQuery = value }
    fun setJobLocation(value: String) { jobLocation = value }
}

class HubViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(HubViewModel::class.java)) {
            return HubViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

private const val MILLIS_PER_DAY = 24L * 60L * 60L * 1000L
