package com.wildodds.gymtracker.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wildodds.gymtracker.data.backend.AuthRepository
import com.wildodds.gymtracker.data.backend.NetworkMonitor
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Background sync. Skips silently (Result.success) when there's no session or the
 * "Sync over Wi-Fi only" setting vetoes the current network — those aren't failures,
 * just "not now". Real errors retry with WorkManager's backoff, capped.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val app = applicationContext

    val signedIn = runCatching { AuthRepository().currentUserId != null }.getOrDefault(false)
    if (!signedIn) return Result.success()

    val wifiOnly = ThemePreferences(app).flag(SettingsRegistry.SYNC_WIFI_ONLY, false).first()
    if (wifiOnly && !NetworkMonitor(app).isOnWifi()) return Result.success()

    val engine = SyncEngine(
      db = AppDatabase.getInstance(app),
      backend = SupabaseSyncBackend(),
      cursors = PrefsSyncCursorStore(app)
    )
    return when (engine.syncNow()) {
      is SyncEngine.Result.Success -> Result.success()
      is SyncEngine.Result.Failure -> if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
  }
}

object SyncScheduler {

  private const val PERIODIC_WORK = "sync_periodic"
  private const val ONESHOT_WORK = "sync_now"

  private val connected = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.CONNECTED)
    .build()

  /** Every ~6h while connected; called once at app start (idempotent KEEP). */
  fun ensurePeriodic(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
      PERIODIC_WORK,
      ExistingPeriodicWorkPolicy.KEEP,
      PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
        .setConstraints(connected)
        .build()
    )
  }

  /** One immediate sync (app-open, session completion, Settings "Sync now"). */
  fun syncNow(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
      ONESHOT_WORK,
      ExistingWorkPolicy.KEEP, // an already-queued/running sync covers this request
      OneTimeWorkRequestBuilder<SyncWorker>()
        .setConstraints(connected)
        .build()
    )
  }
}
