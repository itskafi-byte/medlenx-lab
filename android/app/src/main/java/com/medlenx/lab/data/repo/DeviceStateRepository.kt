package com.medlenx.lab.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
    context: Context,
    private val queueDao: QueueDao,
    private val profileDao: ProfileDao,
    private val prescriptionDao: PrescriptionDao,
) {
    var online by mutableStateOf(true)
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

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /**
     * Starts listening for connectivity changes. Account-specific collectors are
     * started separately by [bindUser] after authentication.
     *
     * Call once, from an application-lifetime scope. Until this runs, [online] stays at
     * its initial `true` and [queuedScans] stays 0 - the header chips would be decorative
     * and nothing would ever notice the network coming back.
     */
    fun start() {
        refreshOnline()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity?.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                online = true
            }

            override fun onLost(network: Network) {
                online = false
            }
        })
    }

    fun setCompany(name: String?) {
        companyName = name?.takeIf { it.isNotBlank() }
    }

    private fun refreshOnline() {
        val network = connectivity?.activeNetwork ?: run {
            online = false
            return
        }
        val caps = connectivity.getNetworkCapabilities(network)
        online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
