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
import com.medlenx.lab.data.local.QueueDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Snapshot of the header chips: connection state, offline queue depth and company.
 *
 * Drives the app bar, so it is observable Compose state rather than a plain value.
 */
class DeviceStateRepository(
    context: Context,
    private val queueDao: QueueDao,
) {
    var online by mutableStateOf(true)
        private set

    var queuedScans by mutableIntStateOf(0)
        private set

    var companyName by mutableStateOf<String?>(null)
        private set

    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** Starts listening for connectivity changes and queue depth. Call once. */
    fun start(scope: CoroutineScope) {
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
        scope.launch {
            queueDao.observeCount().collect { queuedScans = it }
        }
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
