package com.max.agent.network

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owner-gated network control with real OS-level enforcement.
 *
 * Two layers:
 *  1. A policy flag ([isInternetAllowed]) that cooperative modules check
 *     before opening sockets (e.g. [com.max.agent.selffix.WebTroubleshooter]).
 *  2. Hard enforcement via [MaxVpnService] — a no-route VpnService that
 *     captures the device's default route into a sink and drops every packet.
 *     While engaged, NO app on the device (Max included) can reach the network,
 *     regardless of whether it respects the policy flag.
 *
 * Enabling enforcement requires a one-time owner consent dialog
 * ([VpnService.prepare]); the caller drives that via [enforcementConsentIntent].
 * If consent has not been granted yet, the policy flag still applies and
 * cooperative modules stay offline, but the kernel-level block is inactive
 * until consent completes.
 */
class NetworkGuard(private val context: Context) {

    enum class NetworkState {
        DISABLED,
        ENABLED_BY_OWNER
    }

    /** Surfaces whether the hard VpnService block is actually running. */
    enum class Enforcement { OFF, ENGAGED, NEEDS_CONSENT }

    private val _state = MutableStateFlow(NetworkState.DISABLED)
    val state: StateFlow<NetworkState> = _state

    private val _enforcement = MutableStateFlow(Enforcement.OFF)
    val enforcement: StateFlow<Enforcement> = _enforcement

    /** True only while a network with validated internet is currently available. */
    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online

    private val cm: ConnectivityManager
        get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Set by the Activity so enforcement can launch the one-time VPN consent dialog. */
    var consentRequester: ((Intent) -> Unit)? = null

    fun ownerRequestInternet() {
        _state.value = NetworkState.ENABLED_BY_OWNER
        registerNetworkCallback()
        disengageEnforcement()
    }

    fun recallInternet() {
        _state.value = NetworkState.DISABLED
        unregisterNetworkCallback()
        engageEnforcement()
    }

    // Restored UI connection point
    fun ownerDisableInternet() {
        recallInternet()
    }

    fun isInternetAllowed(): Boolean {
        return _state.value == NetworkState.ENABLED_BY_OWNER
    }

    /**
     * Returns an intent the UI must launch with `startActivityForResult` to obtain
     * the owner's VpnService consent, or `null` if consent is already granted.
     * When the activity result is RESULT_OK, call [onEnforcementConsentGranted].
     */
    fun enforcementConsentIntent(): Intent? = VpnService.prepare(context)

    fun onEnforcementConsentGranted() {
        if (_state.value == NetworkState.DISABLED) engageEnforcement()
    }

    private fun engageEnforcement() {
        val prepareIntent = VpnService.prepare(context)
        if (prepareIntent != null) {
            // Consent not yet granted — cooperative block is in effect; prompt the owner.
            _enforcement.value = Enforcement.NEEDS_CONSENT
            consentRequester?.invoke(prepareIntent)
            return
        }
        runCatching {
            context.startService(
                Intent(context, MaxVpnService::class.java).setAction(MaxVpnService.ACTION_BLOCK)
            )
            _enforcement.value = Enforcement.ENGAGED
        }.onFailure {
            android.util.Log.e("NetworkGuard", "Failed to engage VPN enforcement: ${it.message}", it)
            _enforcement.value = Enforcement.OFF
        }
    }

    private fun disengageEnforcement() {
        runCatching {
            context.startService(
                Intent(context, MaxVpnService::class.java).setAction(MaxVpnService.ACTION_ALLOW)
            )
        }.onFailure {
            android.util.Log.w("NetworkGuard", "Failed to disengage VPN enforcement: ${it.message}")
        }
        _enforcement.value = Enforcement.OFF
    }

    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val caps = cm.getNetworkCapabilities(network)
                _online.value = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }

            override fun onLost(network: Network) {
                _online.value = false
            }

            override fun onUnavailable() {
                _online.value = false
            }
        }
        networkCallback = callback
        runCatching { cm.registerNetworkCallback(request, callback) }
            .onFailure { android.util.Log.e("NetworkGuard", "registerNetworkCallback failed: ${it.message}", it) }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
                .onFailure { android.util.Log.w("NetworkGuard", "unregister failed: ${it.message}") }
        }
        networkCallback = null
        _online.value = false
    }
}
