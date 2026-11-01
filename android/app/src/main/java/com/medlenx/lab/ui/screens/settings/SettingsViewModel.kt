package com.medlenx.lab.ui.screens.settings

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
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
import com.medlenx.lab.data.local.PrescriptionEntity
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.medlenx.lab.data.model.BdLocations
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CancellationException
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

    var pendingUploads by mutableStateOf<Int?>(null)
        private set
    var signOutBusy by mutableStateOf(false)
        private set
    var signOutError by mutableStateOf<String?>(null)
        private set

    var trappedCount by mutableStateOf(0)
        private set
    var purgeConfirmationCount by mutableStateOf<Int?>(null)
        private set
    var purgeBusy by mutableStateOf(false)
        private set
    var purgeMessage by mutableStateOf<String?>(null)
        private set
    private var purgeSnapshot: List<PrescriptionEntity> = emptyList()

    fun preparePurge() {
        if (purgeBusy || signOutBusy) return
        purgeBusy = true
        purgeMessage = null
        viewModelScope.launch {
            try {
                check(currentUid.isNotBlank() && FirebaseAuth.getInstance().currentUser?.uid == currentUid)
                purgeSnapshot = app.graph.database.prescriptionDao().trappedAudits(currentUid)
                if (purgeSnapshot.isNotEmpty()) purgeConfirmationCount = purgeSnapshot.size
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { purgeMessage = "Could not check trapped local data." }
            finally { purgeBusy = false }
        }
    }

    fun cancelPurge() { purgeConfirmationCount = null; purgeSnapshot = emptyList() }

    fun confirmPurge() {
        if (purgeBusy || signOutBusy || purgeConfirmationCount == null) return
        val confirmedIds = purgeSnapshot.map { it.id }.toSet()
        cancelPurge()
        purgeBusy = true
        purgeMessage = null
        viewModelScope.launch {
            var deleted: List<PrescriptionEntity>? = null
            try {
                val db = app.graph.database
                deleted = db.withTransaction {
                    check(FirebaseAuth.getInstance().currentUser?.uid == currentUid && currentUid.isNotBlank())
                    val rows = db.prescriptionDao().trappedAudits(currentUid).filter { it.id in confirmedIds }
                    rows.chunked(500).forEach { chunk ->
                        val ids = chunk.map { it.id }
                        db.prescriptionDao().deleteMedicinesForAudits(ids)
                        db.prescriptionDao().deleteVisitsForAudits(ids)
                        check(db.prescriptionDao().deleteTrappedAudits(currentUid, ids) == ids.size)
                    }
                    rows
                }
                // Once committed, finish cleanup even if the Settings screen is closed.
                val rows = deleted
                val result = withContext(NonCancellable + Dispatchers.IO) { db.withTransaction {
                    // Hold Room's writer lock while checking references and deleting,
                    // so another database writer cannot add a reference in between.
                    val paths = db.prescriptionDao().allReferencedImagePaths() + db.queueDao().allReferencedImagePaths()
                    val references = paths.filter { it.isNotBlank() }.map { File(it).canonicalFile }.toSet()
                    var removed = 0
                    var retained = 0
                    rows.mapNotNull { row ->
                        if (row.imagePath.isBlank()) null else row to row.imagePath
                    }.distinctBy { it.second }.forEach { (row, path) ->
                        try {
                            val raw = File(path)
                            val file = raw.canonicalFile
                            val legacy = row.userId == "legacy_user"
                            val parents = if (legacy) listOf(
                                File(app.filesDir, "prescriptions/legacy_user").canonicalFile,
                                File(app.filesDir, "rx").canonicalFile,
                            ) else listOf(UserCaptureFiles.stored(app, currentUid).canonicalFile)
                            if (!raw.isAbsolute || file.parentFile !in parents || file in references) {
                                retained++
                            } else if (file.exists()) {
                                if (file.isFile && file.delete()) removed++ else retained++
                            }
                        } catch (_: Exception) { retained++ }
                    }
                    removed to retained
                } }
                purgeMessage = "Deleted ${rows.size} local audits and ${result.first} photos." +
                    if (result.second > 0) " ${result.second} photo paths were retained or could not be deleted (shared, unsafe, or inaccessible)." else ""
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                purgeMessage = if (deleted == null) "Could not clear trapped local data; no audits were deleted."
                    else "Deleted ${deleted.size} local audits, but photo cleanup could not be completed."
            } finally { purgeBusy = false }
        }
    }

    /** Fail closed if the count is not loaded or changes before the click. */
    fun signOut() {
        if (signOutBusy || purgeBusy || pendingUploads == null || pendingUploads != 0) return
        signOutBusy = true
        signOutError = null
        viewModelScope.launch {
            try {
                app.graph.database.withTransaction {
                    check(FirebaseAuth.getInstance().currentUser?.uid == currentUid && currentUid.isNotBlank())
                    val count = app.graph.database.prescriptionDao().pendingUploadCount(currentUid)
                    if (count > 0) {
                        signOutError = "Sync in progress. Please wait for $count pending scans to upload before signing out."
                    } else {
                        // Auth may have changed while the database query was running.
                        check(FirebaseAuth.getInstance().currentUser?.uid == currentUid)
                        // Local audits and photos are deliberately retained.
                        FirebaseAuth.getInstance().signOut()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                signOutError = "Could not verify pending uploads. Sign-out was not completed."
            } finally {
                signOutBusy = false
            }
        }
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
        viewModelScope.launch {
            app.graph.database.prescriptionDao().observePendingUploads(currentUid).collect { pendingUploads = it }
        }
        viewModelScope.launch {
            app.graph.database.prescriptionDao().observeTrappedCount(currentUid).collect { trappedCount = it }
        }
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
