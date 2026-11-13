package com.medlenx.lab.data.repo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import java.lang.SecurityException

/** Observes the validated default network, not simply a Wi-Fi connection. */
class NetworkConnectivityObserver(context: Context) {
    private val connectivity = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    /** A point-in-time gate; validation cannot guarantee a particular host is reachable. */
    fun isConnected(): Boolean {
        return try {
            val manager = connectivity ?: return false
            val network = manager.activeNetwork ?: return false
            val caps = manager.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: SecurityException) {
            false
        }
    }

    /** One callback per collector, unregistered when collection stops. */
    fun observe(): Flow<Boolean> = callbackFlow {
        val manager = connectivity
        if (manager == null) {
            trySend(false)
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { trySend(isConnected()) }
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(isConnected())
            }
            override fun onLost(network: Network) { trySend(isConnected()) }
            override fun onUnavailable() { trySend(false) }
        }
        try {
            manager.registerDefaultNetworkCallback(callback)
        } catch (_: RuntimeException) {
            trySend(false)
            close()
            return@callbackFlow
        }
        // Register first, then sample so a transition during setup isn't missed.
        trySend(isConnected())
        awaitClose { runCatching { manager.unregisterNetworkCallback(callback) } }
    }.buffer(Channel.CONFLATED).distinctUntilChanged()
}
