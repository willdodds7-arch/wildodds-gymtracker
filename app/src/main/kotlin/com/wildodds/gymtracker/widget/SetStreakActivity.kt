package com.wildodds.gymtracker.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.EditText

class SetStreakActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)

  val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
  if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

  val currentStreak = HabitWidgetProvider.getStreak(this, widgetId)

  val input = EditText(this).apply {
  inputType = InputType.TYPE_CLASS_NUMBER
  setText(currentStreak.toString())
  selectAll()
  setPadding(48, 24, 48, 8)
  }
  input.requestFocus()

  AlertDialog.Builder(this)
  .setTitle("Set Streak")
  .setMessage("Enter your current streak (days):")
  .setView(input)
  .setPositiveButton("Save") { _, _ ->
  val newStreak = input.text.toString().trim().toIntOrNull()?.coerceAtLeast(0) ?: currentStreak
  HabitWidgetProvider.prefs(this).edit()
  .putInt(HabitWidgetProvider.KEY_STREAK + widgetId, newStreak)
  .apply()
  HabitWidgetProvider.updateWidget(this, AppWidgetManager.getInstance(this), widgetId)
  finish()
  }
  .setNegativeButton("Cancel") { _, _ -> finish() }
  .setOnCancelListener { finish() }
  .show()
  .window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
  }
}
