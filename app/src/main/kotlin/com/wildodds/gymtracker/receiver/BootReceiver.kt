package com.wildodds.gymtracker.receiver

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.wildodds.gymtracker.widget.HabitWidgetProvider

class BootReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
  if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
  intent.action != Intent.ACTION_MY_PACKAGE_REPLACED
  ) return

  val ids = AppWidgetManager.getInstance(context)
  .getAppWidgetIds(ComponentName(context, HabitWidgetProvider::class.java))

  if (ids.isNotEmpty()) {
  MidnightAlarmScheduler.schedule(context)
  val manager = AppWidgetManager.getInstance(context)
  for (id in ids) HabitWidgetProvider.updateWidget(context, manager, id)
  }
  }
}
