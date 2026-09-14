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
import com.medlenx.lab.data.local.DoctorTierRow
import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.local.StewardshipRow
import com.medlenx.lab.data.repo.TeamMetrics
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

    fun setTierFilter(tier: String) {
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
