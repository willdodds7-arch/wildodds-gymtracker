package com.wildodds.gymtracker.data.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Rule 1 (offline-first sync): the app must never assume connectivity. Every sync/backend call
 * should check this first rather than firing and handling a timeout — a dropped connection must
 * never block a workout.
 */
class NetworkMonitor(context: Context) {
  private val connectivityManager =
    context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  /** True once there's a network with validated internet access (not just "connected to Wi-Fi"). */
  val isOnline: Flow<Boolean> = callbackFlow {
    val request = NetworkRequest.Builder()
      .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
      .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
      .build()

    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) { trySend(true) }
      override fun onLost(network: Network) { trySend(currentlyOnline()) }
      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
      }
    }

    trySend(currentlyOnline())
    connectivityManager.registerNetworkCallback(request, callback)
    awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
  }.distinctUntilChanged()

  private fun currentlyOnline(): Boolean {
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
      capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
  }

  /** True if any Wi-Fi network is the active one — used by the "Sync over Wi-Fi only" setting. */
  fun isOnWifi(): Boolean {
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
    return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
  }
}
