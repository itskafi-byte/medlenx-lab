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
import com.medlenx.lab.data.model.HealthDays
import com.medlenx.lab.data.model.JobBoard
import com.medlenx.lab.data.model.PharmaJobs
import com.medlenx.lab.data.repo.PharmaHub
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

    private val catalogue = (application as MedLenXApp).graph.catalogue

    /** Fixed at construction so a Hub left open across midnight does not reshuffle. */
    val today: LocalDate = LocalDate.now()

    var healthDays by mutableStateOf<HealthDays?>(null)
        private set
    var jobs by mutableStateOf<PharmaJobs?>(null)
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
        }
    }

    fun calendar(): HealthCalendar = PharmaHub.healthDays(
        data = healthDays ?: HealthDays(),
        today = today,
        year = today.year,
    )

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
