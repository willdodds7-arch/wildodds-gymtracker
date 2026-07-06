package com.wildodds.gymtracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.widget.RemoteViews
import com.wildodds.gymtracker.R
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.HabitCompletion
import com.wildodds.gymtracker.receiver.MidnightAlarmScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class HabitWidgetProvider : AppWidgetProvider() {

  override fun onEnabled(context: Context) {
  try { MidnightAlarmScheduler.schedule(context) } catch (_: Exception) {}
  }

  override fun onDisabled(context: Context) {
  MidnightAlarmScheduler.cancel(context)
  }

  override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
  for (id in appWidgetIds) updateWidget(context, appWidgetManager, id)
  }

  override fun onDeleted(context: Context, appWidgetIds: IntArray) {
  val editor = prefs(context).edit()
  for (id in appWidgetIds) {
  editor.remove(KEY_HABIT_NAME + id)
  editor.remove(KEY_STREAK + id)
  editor.remove(KEY_CHECKED + id)
  editor.remove(KEY_LAST_DATE + id)
  }
  editor.apply()
  }

  override fun onReceive(context: Context, intent: Intent) {
  super.onReceive(context, intent)
  if (intent.action == ACTION_TOGGLE_CHECK) {
  val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
  if (id != AppWidgetManager.INVALID_APPWIDGET_ID) toggleCheck(context, id)
  }
  }

  companion object {
  const val ACTION_TOGGLE_CHECK = "com.wildodds.gymtracker.ACTION_TOGGLE_HABIT_CHECK"

  private const val PREFS_NAME  = "GymTrackerHabitWidgetPrefs"
  const val KEY_HABIT_NAME = "habit_name_"
  const val KEY_STREAK  = "streak_"
  const val KEY_CHECKED  = "checked_"
  const val KEY_LAST_DATE  = "last_date_"

  private val dateFmt = DateTimeFormatter.ISO_LOCAL_DATE

  fun prefs(context: Context): SharedPreferences =
  context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  fun getHabitName(context: Context, widgetId: Int): String =
  prefs(context).getString(KEY_HABIT_NAME + widgetId, "My Habit") ?: "My Habit"

  fun getStreak(context: Context, widgetId: Int): Int =
  prefs(context).getInt(KEY_STREAK + widgetId, 0)

  fun isCheckedToday(context: Context, widgetId: Int): Boolean {
  val p = prefs(context)
  val lastDate = p.getString(KEY_LAST_DATE + widgetId, "") ?: ""
  return lastDate == LocalDate.now().format(dateFmt) && p.getBoolean(KEY_CHECKED + widgetId, false)
  }

  fun toggleCheck(context: Context, widgetId: Int) {
  val today  = LocalDate.now().format(dateFmt)
  val wasChecked = isCheckedToday(context, widgetId)
  prefs(context).edit()
  .putBoolean(KEY_CHECKED + widgetId, !wasChecked)
  .putString(KEY_LAST_DATE + widgetId, today)
  .apply()
  updateWidget(context, AppWidgetManager.getInstance(context), widgetId)

  // Sync to Room DB: find the matching habit by name and toggle its completion
  val habitName = getHabitName(context, widgetId)
  val todayStr  = LocalDate.now().toString()
  CoroutineScope(Dispatchers.IO).launch {
  try {
  val dao  = AppDatabase.getInstance(context).habitDao()
  val habit = dao.allHabitsOnce().find { it.name.equals(habitName, ignoreCase = true)  } ?: return@launch
  val done  = dao.isCompleted(habit.id, todayStr)
  if (done) {
  dao.markIncomplete(habit.id, todayStr)
  } else {
  dao.markComplete(HabitCompletion(habitId = habit.id, completedDate = todayStr))
  }
  } catch (_: Exception) {}
  }
  }

  fun saveHabitName(context: Context, widgetId: Int, name: String) {
  prefs(context).edit().putString(KEY_HABIT_NAME + widgetId, name).apply()
  updateWidget(context, AppWidgetManager.getInstance(context), widgetId)
  }

  fun updateWidget(context: Context, appWidgetManager: AppWidgetManager, widgetId: Int) {
  val checked = isCheckedToday(context, widgetId)
  val views = RemoteViews(context.packageName, R.layout.widget_habit_row)

  views.setTextViewText(R.id.tv_habit_name, getHabitName(context, widgetId))
  views.setTextViewText(R.id.tv_streak, "Streak: ${getStreak(context, widgetId)} days")

  views.setImageViewResource(
  R.id.btn_checkbox,
  if (checked) R.drawable.bg_status_checked else R.drawable.bg_status_unchecked
  )

  // Tap checkbox → toggle
  views.setOnClickPendingIntent(
  R.id.btn_checkbox,
  PendingIntent.getBroadcast(
  context, widgetId,
  Intent(context, HabitWidgetProvider::class.java).apply {
  action = ACTION_TOGGLE_CHECK
  putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
  },
  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )
  )

  // Tap habit name → rename
  views.setOnClickPendingIntent(
  R.id.tv_habit_name,
  PendingIntent.getActivity(
  context, widgetId + 100_000,
  Intent(context, RenameHabitActivity::class.java).apply {
  putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
  flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
  },
  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )
  )

  // Tap streak → edit streak
  views.setOnClickPendingIntent(
  R.id.tv_streak,
  PendingIntent.getActivity(
  context, widgetId + 200_000,
  Intent(context, SetStreakActivity::class.java).apply {
  putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
  flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
  },
  PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
  )
  )

  appWidgetManager.updateAppWidget(widgetId, views)
  }
  }
}
