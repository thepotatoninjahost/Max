package com.max.agent.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.net.InetAddress

class NetworkStateMonitor(private val context: Context) {

    enum class Transport { WIFI, CELLULAR, ETHERNET, VPN, NONE }

    data class Snapshot(
        val isConnected: Boolean = false,
        val transport: Transport = Transport.NONE,
        val hasValidatedInternet: Boolean = false,
        val signalDbm: Int = 0,
        val ipAddress: String? = null
    ) {
        val transportLabel: String get() = transport.name.lowercase().replaceFirstChar { it.uppercase() }
        val signalBars: Int get() = when {
            signalDbm >= -55 -> 4
            signalDbm >= -70 -> 3
            signalDbm >= -85 -> 2
            signalDbm > -100 -> 1
            else -> 0
        }
    }

    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { refresh() }
        override fun onLost(network: Network) { _state.value = Snapshot() }
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { refresh(caps) }
    }

    fun startMonitoring() {
        val req = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { cm.registerNetworkCallback(req, callback) }
        refresh()
    }

    fun stopMonitoring() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    fun refresh(caps: NetworkCapabilities? = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }) {
        val transport = when {
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> Transport.WIFI
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> Transport.CELLULAR
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> Transport.ETHERNET
            caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true -> Transport.VPN
            else -> Transport.NONE
        }
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        // Signal: NetworkCapabilities.signalStrength is API 29+ for any transport.
        val signalDbm = caps?.signalStrength ?: 0
        // IP: pull from LinkProperties (no WifiManager dependency, works on all transports).
        val ipAddress = cm.activeNetwork?.let { cm.getLinkProperties(it) }
            ?.linkAddresses?.firstOrNull { it.address.address.size == 4 }
            ?.address?.hostAddress

        _state.value = Snapshot(
            isConnected = transport != Transport.NONE,
            transport = transport,
            hasValidatedInternet = validated,
            signalDbm = signalDbm,
            ipAddress = ipAddress
        )
    }

    suspend fun pingMs(host: String = "8.8.8.8", timeoutMs: Int = 3000): Long? =
        withContext(Dispatchers.IO) {
            runCatching {
                val start = System.currentTimeMillis()
                if (InetAddress.getByName(host).isReachable(timeoutMs)) System.currentTimeMillis() - start else null
            }.getOrNull()
        }
}
