package st.kiwifarms.sneedroid.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Watches whether the device's active network is a VPN tunnel, using only on-device
 * [ConnectivityManager] state — no network calls, and the public IP is never obtained or sent
 * anywhere. This is the privacy-preserving signal behind the IP killswitch: "VPN tunnel present"
 * is treated as protected, its absence as an exposed/residential connection.
 *
 * Caveat: this detects Android VPN-API tunnels (WireGuard, OpenVPN, Mullvad, Orbot's VPN mode,
 * etc.). A router-level / whole-network VPN is invisible here (the phone just sees plain Wi-Fi),
 * so such a connection reads as unprotected.
 */
class ConnectivityMonitor(context: Context) {

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val _vpnActive = MutableStateFlow(currentlyVpn())
    val vpnActive: StateFlow<Boolean> = _vpnActive.asStateFlow()

    private fun currentlyVpn(): Boolean {
        val net = cm?.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    /** Recompute now (also used by a manual "check" button in settings). */
    fun refresh() { _vpnActive.value = currentlyVpn() }

    init {
        // The default network flips to/from the VPN as it connects/drops; recompute on any change.
        cm?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()
            override fun onLost(network: Network) = refresh()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        })
    }
}

/**
 * Combines the killswitch setting with the live VPN state into a single [Killswitch.blocked] flow
 * the chat connection consults: blocked when the killswitch is on **and** no VPN tunnel is present.
 */
class KillswitchGate(
    settings: SettingsRepository,
    connectivity: ConnectivityMonitor,
) : Killswitch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _blocked = MutableStateFlow(false)
    override val blocked: StateFlow<Boolean> = _blocked.asStateFlow()

    init {
        scope.launch {
            combine(settings.settings, connectivity.vpnActive) { s, vpn ->
                s.ipKillswitch && !vpn
            }.collect { _blocked.value = it }
        }
    }
}
