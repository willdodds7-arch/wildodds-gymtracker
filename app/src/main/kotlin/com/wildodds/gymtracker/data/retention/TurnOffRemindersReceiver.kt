package com.wildodds.gymtracker.data.retention

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.ui.settings.SettingsRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The "Turn off reminders" button on every reminder — the app's equivalent of an unsubscribe link.
 * One tap switches reminders off for good (same as the Settings toggle), stops the schedule and
 * clears the notification, without opening the app.
 */
class TurnOffRemindersReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
    val pending = goAsync()
    val app = context.applicationContext
    ReengagementScheduler.cancel(app)
    NotificationManagerCompat.from(app).cancel(ReengagementNotifier.NOTIFICATION_ID)
    CoroutineScope(Dispatchers.IO).launch {
      try {
        ThemePreferences(app).setFlag(SettingsRegistry.REENGAGEMENT, false)
      } finally {
        pending.finish()
      }
    }
  }

  companion object {
    const val ACTION = "com.wildodds.gymtracker.ACTION_TURN_OFF_REMINDERS"
  }
}
