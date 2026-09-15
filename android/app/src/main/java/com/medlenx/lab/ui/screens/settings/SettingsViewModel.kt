package com.medlenx.lab.ui.screens.settings

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.medlenx.lab.data.local.BrandTargetEntity
import com.medlenx.lab.data.local.OfficerProfileEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Backs the Enterprise Settings & Officer Profile screen.
 *
 * Everything here is local: the profile row, the monthly brand targets and the
 * captured-count progress are all Room state, so saving works with no network.
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MedLenXApp
    private val profileDao = app.graph.profileDao

    val roles = listOf("MPO", "RSO", "RSM", "Territory Manager", "Product Manager")

    var company by mutableStateOf("")
        private set
    var employeeId by mutableStateOf("")
        private set
    var fullName by mutableStateOf("")
        private set
    var role by mutableStateOf(roles.first())
        private set
    var division by mutableStateOf("")
        private set
    var territory by mutableStateOf("")
        private set
    var portfolio by mutableStateOf("")
        private set

    /** Editable target rows; `captured` is filled from this month's scans. */
    var targets by mutableStateOf<List<TargetDraft>>(emptyList())
        private set

    /** Company autocomplete source — the distinct companies in the bundled catalogue. */
    private var allCompanies by mutableStateOf<List<String>>(emptyList())
    var companyQuery by mutableStateOf("")
        private set

    val companyMatches: List<String>
        get() {
            val q = companyQuery.trim()
            val pool = allCompanies.ifEmpty { emptyList() }
            return if (q.isEmpty()) pool.take(8)
            else pool.filter { it.contains(q, ignoreCase = true) }.take(8)
        }

    var saved by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun updateEmployeeId(v: String) { employeeId = v }
    fun updateFullName(v: String) { fullName = v }
    fun updateDivision(v: String) { division = v }
    fun updateTerritory(v: String) { territory = v }
    fun updatePortfolio(v: String) { portfolio = v }

    fun onCompanyQuery(q: String) {
        companyQuery = q
    }

    fun selectCompany(name: String) {
        company = name
        companyQuery = ""
    }

    fun updateRole(r: String) {
        role = if (r in roles) r else roles.first()
    }

    fun addBrand() {
        targets = targets + TargetDraft(brand = "", monthlyTarget = 0, captured = 0)
    }

    fun updateBrand(index: Int, brand: String, monthlyTarget: Int) {
        targets = targets.mapIndexed { i, t ->
            if (i == index) t.copy(brand = brand, monthlyTarget = monthlyTarget) else t
        }
    }

    fun removeBrand(index: Int) {
        targets = targets.filterIndexed { i, _ -> i != index }
    }

    fun load() {
        viewModelScope.launch {
            error = null
            runCatching {
                allCompanies = app.graph.database.medexDao().companies()
                profileDao.current()?.let { p ->
                    company = p.company
                    employeeId = p.employeeId
                    fullName = p.fullName
                    if (p.role in roles) role = p.role
                    division = p.division
                    territory = p.territory
                    portfolio = p.portfolio
                }
                val captured = app.graph.database.prescriptionDao()
                    .brandCapturedRows(since = startOfMonth())
                    .associateBy({ it.brand.lowercase() }, { it.captured })
                targets = profileDao.observeTargets().first().map { t ->
                    TargetDraft(
                        brand = t.brand,
                        monthlyTarget = t.target,
                        captured = captured[t.brand.lowercase()] ?: 0,
                    )
                }
            }.onFailure { error = it.message ?: "Could not load settings" }
        }
    }

    fun save() {
        viewModelScope.launch {
            error = null
            saved = false
            runCatching {
                val existing = profileDao.current()
                profileDao.save(
                    OfficerProfileEntity(
                        id = existing?.id ?: 1,
                        company = company,
                        employeeId = employeeId,
                        fullName = fullName,
                        role = role,
                        division = division,
                        territory = territory,
                        portfolio = portfolio,
                    ),
                )
                val rows = targets
                    .filter { it.brand.isNotBlank() }
                    .map { BrandTargetEntity(brand = it.brand, target = it.monthlyTarget) }
                profileDao.clearTargets()
                profileDao.saveTargets(rows)
            }.onFailure {
                error = it.message ?: "Could not save the officer card"
                return@launch
            }
            saved = true
            load()
        }
    }

    private fun startOfMonth(): Long =
        java.time.LocalDate.now().withDayOfMonth(1)
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()

    init {
        load()
    }
}

data class TargetDraft(val brand: String, val monthlyTarget: Int, val captured: Int) {
    val percent: Int
        get() = if (monthlyTarget <= 0) 0 else (captured * 100 / monthlyTarget).coerceAtMost(999)
}

class SettingsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        SettingsViewModel(application) as T
}
