package com.medlenx.lab.ui.screens.settings

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.medlenx.lab.MedLenXApp
import com.google.firebase.auth.FirebaseAuth
import com.medlenx.lab.data.local.BrandTargetEntity
import com.medlenx.lab.data.local.DailyTargetSettings
import com.medlenx.lab.data.local.UserCaptureFiles
import com.medlenx.lab.data.local.OfficerProfileEntity
import com.medlenx.lab.data.model.BdLocations
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
    private val currentUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty()
    private val profileDao = app.graph.profileDao

    private val targetPrefs = application.getSharedPreferences(DailyTargetSettings.PREFERENCES + "_" + UserCaptureFiles.key(currentUid), Context.MODE_PRIVATE)
    var dailyTarget by mutableStateOf(targetPrefs.getInt(DailyTargetSettings.KEY, DailyTargetSettings.DEFAULT).toString())
        private set
    fun updateDailyTarget(value: String) { if (value.length <= 4 && value.all { it.isDigit() }) dailyTarget = value }

    val roles = listOf("MPO", "RSO", "RSM", "Territory Manager", "Product Manager")
    val roleLabels = mapOf(
        "MPO" to "MPO - Medical Promotion Officer",
        "RSO" to "RSO - Regional Sales Officer",
        "RSM" to "RSM - Regional Sales Manager",
        "Territory Manager" to "Territory Manager",
        "Product Manager" to "Product Manager",
    )
    val portfolios = listOf("Cardiology", "Gastroenterology", "Neuromedicine", "Diabetes", "Respiratory", "General Medicine")
    private var locations by mutableStateOf(BdLocations())
    val divisions: List<String> get() = locations.divisions.ifEmpty {
        listOf("Barishal", "Chattogram", "Dhaka", "Khulna", "Mymensingh", "Rajshahi", "Rangpur", "Sylhet")
    }
    val territories: List<String> get() = locations.districts.values
        .filter { it.division.equals(division, ignoreCase = true) }
        .flatMap { it.territories }.distinct().sorted()

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
            // An empty query must mean no dropdown. Returning the first handful of
            // companies (`pool.take(8)`) kept the suggestion list permanently open
            // under the field, listing eight unrelated names before any typing.
            if (q.isEmpty()) return emptyList()
            return allCompanies.filter { it.contains(q, ignoreCase = true) }.take(8)
        }

    var saved by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun updateEmployeeId(v: String) { employeeId = v }
    fun updateFullName(v: String) { fullName = v }
    fun updateDivision(v: String) {
        if (division != v) { division = v; territory = "" }
    }
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
                locations = app.graph.locationRepository.locations()
                allCompanies = app.graph.database.medexDao().companies()
                profileDao.current(currentUid)?.let { p ->
                    company = p.company
                    employeeId = p.employeeId
                    fullName = p.fullName
                    if (p.role in roles) role = p.role
                    division = p.division
                    territory = p.territory
                    portfolio = p.portfolio
                }
                val captured = app.graph.database.prescriptionDao()
                    .brandCapturedRows(currentUid, since = startOfMonth())
                    .associateBy({ it.brand.lowercase() }, { it.captured })
                targets = profileDao.observeTargets(currentUid).first().map { t ->
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
            val target = dailyTarget.toIntOrNull()
            if (target == null || target <= 0) {
                error = "Enter a daily scan target greater than zero."
                return@launch
            }
            runCatching {
                check(currentUid.isNotBlank() && FirebaseAuth.getInstance().currentUser?.uid == currentUid) { "Sign in to save your profile" }
                profileDao.save(
                    OfficerProfileEntity(
                        userId = currentUid,
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
                    .map { BrandTargetEntity(userId = currentUid, brand = it.brand, target = it.monthlyTarget) }
                profileDao.clearTargets(currentUid)
                profileDao.saveTargets(rows)
            }.onFailure {
                error = it.message ?: "Could not save the officer card"
                return@launch
            }
            targetPrefs.edit().putInt(DailyTargetSettings.KEY, target).apply()
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
