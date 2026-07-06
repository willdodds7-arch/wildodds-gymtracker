package com.wildodds.gymtracker.receiver

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.wildodds.gymtracker.widget.HabitWidgetProvider
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class MidnightResetReceiver : BroadcastReceiver() {

  override fun onReceive(context: Context, intent: Intent) {
  val yesterday = LocalDate.now().minusDays(1).format(DateTimeFormatter.ISO_LOCAL_DATE)
  val prefs = HabitWidgetProvider.prefs(context)

  val appWidgetManager = AppWidgetManager.getInstance(context)
  val widgetIds = appWidgetManager.getAppWidgetIds(
  ComponentName(context, HabitWidgetProvider::class.java)
  )

  if (widgetIds.isEmpty()) {
  MidnightAlarmScheduler.schedule(context)
  return
  }

  val editor = prefs.edit()
  for (id in widgetIds) {
  val lastDate  = prefs.getString(HabitWidgetProvider.KEY_LAST_DATE + id, "") ?: ""
  val wasChecked = lastDate == yesterday && prefs.getBoolean(HabitWidgetProvider.KEY_CHECKED + id, false)
  val streak  = prefs.getInt(HabitWidgetProvider.KEY_STREAK + id, 0)
  editor.putInt(HabitWidgetProvider.KEY_STREAK + id, if (wasChecked) streak + 1 else 0)
  editor.putBoolean(HabitWidgetProvider.KEY_CHECKED + id, false)
  }
  editor.apply()

  for (id in widgetIds) HabitWidgetProvider.updateWidget(context, appWidgetManager, id)

  MidnightAlarmScheduler.schedule(context)
  }
}
