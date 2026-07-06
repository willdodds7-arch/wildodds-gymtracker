package com.wildodds.gymtracker.data.wearable

import android.content.Context

/**
 * The single place that decides which [WearableSessionData] the app uses — the project has no DI
 * framework, so construction is explicit here (replacing the Phase 2 hard-coded
 * [NoOpWearableSessionData]).
 *
 * Wired in: [com.wildodds.gymtracker.ui.session.SessionViewModel] calls [create] once to build its
 * `wearable` field. When Health Connect is installed/supported it returns a real
 * [HealthConnectWearableSessionData] (which itself still returns null until the user grants
 * permission); otherwise it falls back to the no-op source so the app behaves exactly as before.
 */
object WearableProvider {
  fun create(context: Context): WearableSessionData {
    val gateway = RealHealthConnectGateway(context.applicationContext)
    return if (gateway.availability() == WearableAvailability.AVAILABLE) {
      HealthConnectWearableSessionData(gateway)
    } else {
      NoOpWearableSessionData
    }
  }
}
