package com.medlenx.lab.data.repo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.medlenx.lab.data.local.ProfileDao
import com.medlenx.lab.data.local.PrescriptionDao
import com.medlenx.lab.data.local.QueueDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Snapshot of the header chips: connection state, offline queue depth and company.
 *
 * Drives the app bar, so it is observable Compose state rather than a plain value.
 */
class DeviceStateRepository(
    private val networkObserver: NetworkConnectivityObserver,
    private val queueDao: QueueDao,
    private val profileDao: ProfileDao,
    private val prescriptionDao: PrescriptionDao,
) {
    var online by mutableStateOf(false)
        private set

    var queuedScans by mutableIntStateOf(0)
        private set

    /** Firestore outbox, distinct from the local image-processing queue. */
    private var pendingStatus by mutableStateOf<Pair<String, Int>?>(null)

    var companyName by mutableStateOf<String?>(null)
        private set

    private var queueJob: Job? = null
    private var profileJob: Job? = null
    private var pendingJob: Job? = null
    @Volatile private var boundUid: String? = null

    /** Do not briefly show a previous account's count during an auth transition. */
    fun pendingUploadsFor(uid: String?): Int? = pendingStatus?.takeIf { uid != null && uid == boundUid && it.first == uid }?.second

    /** Reset account-specific badges immediately when the authenticated account changes. */
    fun bindUser(uid: String?, scope: CoroutineScope) {
        boundUid = uid
        queueJob?.cancel()
        profileJob?.cancel()
        pendingJob?.cancel()
        queuedScans = 0
        pendingStatus = null
        companyName = null
        if (!uid.isNullOrBlank()) {
            queueJob = scope.launch { queueDao.observeCount(uid).collect { if (boundUid == uid) queuedScans = it } }
            pendingJob = scope.launch { prescriptionDao.observePendingUploads(uid).collect { if (boundUid == uid) pendingStatus = uid to it } }
            profileJob = scope.launch { profileDao.observe(uid).collect { if (boundUid == uid) setCompany(it?.company) } }
        }
    }

    /** Observe validated connectivity for the application lifetime. */
    fun start(scope: CoroutineScope) {
        scope.launch { networkObserver.observe().collect { online = it } }
    }

    fun setCompany(name: String?) {
        companyName = name?.takeIf { it.isNotBlank() }
    }

}
