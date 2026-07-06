package com.wildodds.gymtracker.data.retention

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Schedules / cancels the periodic re-engagement check. No network constraint (notifications are
 * local). Wrapped so an uninitialised WorkManager (unit tests) is a no-op, never a crash.
 */
object ReengagementScheduler {

  private const val WORK_NAME = "wildodds_reengagement"
  private const val INTERVAL_HOURS = 12L

  fun schedule(context: Context) {
    runCatching {
      val request = PeriodicWorkRequestBuilder<ReengagementWorker>(INTERVAL_HOURS, TimeUnit.HOURS).build()
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
  }

  fun cancel(context: Context) {
    runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME) }
  }
}
