package com.wildodds.gymtracker.data.retention

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

/**
 * Posts the (rare, kind) re-engagement notification. Honours Android 13+ POST_NOTIFICATIONS — if the
 * permission isn't granted it simply does nothing (no crash, no nag). Tapping opens the app; the
 * "Turn off reminders" action switches them off without opening it.
 */
object ReengagementNotifier {

  private const val CHANNEL_ID = "reengagement"
  const val NOTIFICATION_ID = 7001

  fun hasPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
      ContextCompat.checkSelfPermission(context, "android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED

  private fun ensureChannel(context: Context) {
    val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
      mgr.createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
          description = "Gentle, occasional reminders to train"
        }
      )
    }
  }

  fun notify(context: Context, title: String, text: String) {
    if (!hasPermission(context)) return
    ensureChannel(context)

    val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
      ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val pending = PendingIntent.getActivity(
      context, 0, launch ?: Intent(),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    // One-tap opt-out on every reminder (the app's "unsubscribe" link).
    val turnOff = PendingIntent.getBroadcast(
      context, 1,
      Intent(context, TurnOffRemindersReceiver::class.java).setAction(TurnOffRemindersReceiver.ACTION),
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
      .addAction(0, "Turn off reminders", turnOff)
      .build()

    // Android 13+: the user can refuse notifications. A refused permission means no nudge, not a crash.
    if (android.os.Build.VERSION.SDK_INT >= 33 &&
      androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED) return
    runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
  }
}
