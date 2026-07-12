package com.wildodds.gymtracker.data.analytics

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wildodds.gymtracker.data.backend.AuthRepository
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Batched, fire-and-forget analytics upload. Re-checks consent at drain time (belt-and-braces
 * with AnalyticsGate's insert-time check): if consent isn't granted, it clears the outbox and
 * succeeds without uploading. No session ⇒ nothing to attribute rows to ⇒ no-op success.
 */
class AnalyticsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val app = applicationContext
    val dao = AppDatabase.getInstance(app).analyticsOutboxDao()

    val consent = runCatching { ThemePreferences(app).analyticsConsent.first() }.getOrDefault("unset")
    if (consent != "granted") {
      runCatching { dao.clear() } // opt-out safety net
      return Result.success()
    }
    val signedIn = runCatching { AuthRepository().currentUserId != null }.getOrDefault(false)
    if (!signedIn) return Result.success()

    val outbox = AnalyticsOutbox(dao, SupabaseAnalyticsUploader())
    return when (outbox.drain()) {
      is AnalyticsOutbox.Result.Success -> Result.success()
      is AnalyticsOutbox.Result.Retry -> if (runAttemptCount < 4) Result.retry() else Result.success()
    }
  }
}

object AnalyticsScheduler {
  private const val UPLOAD_WORK = "analytics_upload"

  private val connected = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  /** Coalesced upload — APPEND_OR_REPLACE so a burst of events schedules a single drain. */
  fun uploadNow(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      UPLOAD_WORK,
      ExistingWorkPolicy.APPEND_OR_REPLACE,
      OneTimeWorkRequestBuilder<AnalyticsWorker>()
        .setConstraints(connected)
        .setInitialDelay(10, TimeUnit.SECONDS) // small debounce so bursts batch together
        .build()
    )
  }
}
