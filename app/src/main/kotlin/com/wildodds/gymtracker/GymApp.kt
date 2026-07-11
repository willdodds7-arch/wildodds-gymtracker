package com.wildodds.gymtracker

import android.app.Application
import androidx.multidex.MultiDex
import android.content.Context
import com.wildodds.gymtracker.data.sync.SyncScheduler

class GymApp : Application() {
  override fun attachBaseContext(base: Context) {
  super.attachBaseContext(base)
  MultiDex.install(this)
  }

  override fun onCreate() {
  super.onCreate()
  // Offline-first sync (Phase 3): a periodic background reconcile plus one attempt on every
  // app open. Both no-op instantly when signed out or offline — never blocks anything.
  runCatching {
  SyncScheduler.ensurePeriodic(this)
  SyncScheduler.syncNow(this)
  }
  }
}
