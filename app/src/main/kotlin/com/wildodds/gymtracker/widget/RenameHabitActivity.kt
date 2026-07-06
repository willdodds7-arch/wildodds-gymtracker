package com.wildodds.gymtracker.widget

import android.app.Activity
import android.app.AlertDialog
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.view.WindowManager
import android.widget.EditText

class RenameHabitActivity : Activity() {

  override fun onCreate(savedInstanceState: Bundle?) {
  super.onCreate(savedInstanceState)

  val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
  if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

  val currentName = HabitWidgetProvider.getHabitName(this, widgetId)

  val input = EditText(this).apply {
  setText(currentName)
  selectAll()
  setPadding(48, 24, 48, 8)
  }
  input.requestFocus()

  AlertDialog.Builder(this)
  .setTitle("Rename Habit")
  .setView(input)
  .setPositiveButton("Save") { _, _ ->
  val newName = input.text.toString().trim().ifEmpty { currentName }
  HabitWidgetProvider.saveHabitName(this, widgetId, newName)
  finish()
  }
  .setNegativeButton("Cancel") { _, _ -> finish() }
  .setOnCancelListener { finish() }
  .show()
  .window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
  }
}
