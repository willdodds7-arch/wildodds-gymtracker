package com.wildodds.gymtracker.data.friends

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wildodds.gymtracker.data.backend.RemoteResult
import java.util.concurrent.TimeUnit

/**
 * Friend-event delivery WITHOUT push infrastructure: this worker polls for events addressed to me
 * (motivation messages, flexes, session starts, shared programs) and raises local notifications.
 * Periodic every 15 min (WorkManager's floor) plus an immediate one-shot on app open — so
 * notifications are instant while the app is used and at-most-15-minutes late in background.
 */
class FriendEventsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

  override suspend fun doWork(): Result {
    val repo = FriendsRepository(applicationContext)
    val events = when (val r = repo.unnotifiedEvents()) {
      is RemoteResult.Success -> r.value
      is RemoteResult.Failure -> return Result.success() // offline / signed out — try next cycle
    }
    if (events.isEmpty()) return Result.success()

    // Resolve sender names in one shot (friends' profiles are readable).
    val senders = when (val f = repo.friends()) {
      is RemoteResult.Success -> f.value.associateBy({ it.id }, { it.username ?: "A friend" })
      is RemoteResult.Failure -> emptyMap()
    }

    events.forEach { e ->
      val who = senders[e.sender] ?: "A friend"
      val (title, text) = FriendNotifier.messageFor(e, who)
      FriendNotifier.notify(applicationContext, e.id, title, text)
    }
    repo.markNotified(events.map { it.id })
    // Piggyback: keep my own friend-visible snapshot fresh while we're online anyway.
    repo.publishSnapshot()
    return Result.success()
  }
}

object FriendNotifier {
  private const val CHANNEL_ID = "friends"

  /** Notification copy per event type — pure, unit-tested. */
  fun messageFor(e: FriendEvent, senderName: String): Pair<String, String> = when (e.type) {
    FriendEvent.TYPE_MOTIVATION -> "$senderName sent you motivation" to
      (e.body.ifBlank { "Time to get back in the gym!" } + "  — reply with a flex from your next session")
    FriendEvent.TYPE_FLEX -> "$senderName flexed for you 💪" to "Your motivation landed."
    FriendEvent.TYPE_SESSION_START -> {
      val session = e.body.ifBlank { "training" }
      "$senderName just started a $session session at the gym" to "Send them some motivation!"
    }
    FriendEvent.TYPE_PROGRAM -> "$senderName shared a program with you" to
      "Open Profile → Friends to add it to your library."
    else -> "$senderName" to e.body
  }

  fun hasPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

  private fun ensureChannel(context: Context) {
    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Friends", NotificationManager.IMPORTANCE_DEFAULT).apply {
          description = "Motivation from friends and friend activity"
        }
      )
    }
  }

  fun notify(context: Context, eventId: Long, title: String, text: String) {
    if (!hasPermission(context)) return
    ensureChannel(context)
    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pending = PendingIntent.getActivity(
      context, eventId.toInt(), launch ?: Intent(),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.ic_popup_reminder)
      .setContentTitle(title)
      .setContentText(text)
      .setStyle(NotificationCompat.BigTextStyle().bigText(text))
      .setPriority(NotificationCompat.PRIORITY_DEFAULT)
      .setAutoCancel(true)
      .setContentIntent(pending)
      .build()
    // Distinct id per event so a burst doesn't collapse to one notification.
    runCatching { NotificationManagerCompat.from(context).notify(8000 + (eventId % 1000).toInt(), notification) }
  }
}

object FriendEventsScheduler {
  private const val PERIODIC = "friend_events_poll"
  private const val ONESHOT = "friend_events_poll_now"

  private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

  /** Periodic background poll (15 min is WorkManager's minimum interval). */
  fun schedule(context: Context) {
    val request = PeriodicWorkRequestBuilder<FriendEventsWorker>(15, TimeUnit.MINUTES)
      .setConstraints(online).build()
    WorkManager.getInstance(context)
      .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
  }

  /** Immediate check — run on app open so in-use delivery feels instant. */
  fun pollNow(context: Context) {
    val request = OneTimeWorkRequestBuilder<FriendEventsWorker>().setConstraints(online).build()
    WorkManager.getInstance(context)
      .enqueueUniqueWork(ONESHOT, ExistingWorkPolicy.REPLACE, request)
  }
}
