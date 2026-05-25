package com.max.agent.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner-gated network *policy flag* — NOT a kernel-level firewall.
 *
 * This class flips an in-process boolean and (un)registers a
 * [ConnectivityManager.NetworkCallback]. Modules that *respect* the flag (e.g.
 * [com.max.agent.selffix.WebTroubleshooter]) call [isInternetAllowed] before
 * opening sockets. Code that ignores the flag will still hit the network.
 *
 * True app-level enforcement requires a VpnService-based outbound block; that
 * is future work.
 */
class NetworkGuard(private val context: Context) {

    enum class NetworkState {
        DISABLED,
        ENABLED_BY_OWNER
    }

    private val _state = MutableStateFlow(NetworkState.DISABLED)
    val state: StateFlow<NetworkState> = _state

    private val cm: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun ownerRequestInternet() {
        _state.value = NetworkState.ENABLED_BY_OWNER
        registerNetworkCallback()
    }

    fun recallInternet() {
        _state.value = NetworkState.DISABLED
        unregisterNetworkCallback()
    }
    
    // Restored UI connection point
    fun ownerDisableInternet() {
        recallInternet()
    }

    fun isInternetAllowed(): Boolean {
        return _state.value == NetworkState.ENABLED_BY_OWNER
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Internet connected — allowed only if state is ENABLED_BY_OWNER
            }
            override fun onLost(network: Network) {
                // Connection lost
            }
        }
        networkCallback = callback
        cm.registerNetworkCallback(request, callback)
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cm.unregisterNetworkCallback(it) }
        networkCallback = null
    }
}
