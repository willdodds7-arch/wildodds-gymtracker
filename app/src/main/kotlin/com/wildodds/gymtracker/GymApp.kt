package com.wildodds.gymtracker

import android.app.Application
import androidx.multidex.MultiDex
import android.content.Context
import com.wildodds.gymtracker.data.analytics.AnalyticsEvent
import com.wildodds.gymtracker.data.analytics.AnalyticsGate

class GymApp : Application() {
  override fun attachBaseContext(base: Context) {
    super.attachBaseContext(base)
    MultiDex.install(this)
  }

  override fun onCreate() {
    super.onCreate()
    AnalyticsGate.init(this)
    // Local-only build: no cloud sync and no social layer. Analytics stays consent-gated and
    // on-device (dropped entirely if the user hasn't opted in — AnalyticsGate enforces it).
    AnalyticsGate.log(AnalyticsEvent.AppOpen)
  }
}
