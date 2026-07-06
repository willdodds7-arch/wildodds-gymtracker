package com.wildodds.gymtracker

import android.app.Application
import androidx.multidex.MultiDex
import android.content.Context

class GymApp : Application() {
  override fun attachBaseContext(base: Context) {
  super.attachBaseContext(base)
  MultiDex.install(this)
  }
}
