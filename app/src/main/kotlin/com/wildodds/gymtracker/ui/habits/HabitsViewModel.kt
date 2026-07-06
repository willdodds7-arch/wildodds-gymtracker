package com.wildodds.gymtracker.ui.habits

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.Habit
import com.wildodds.gymtracker.data.db.entity.HabitCompletion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate

class HabitsViewModel(app: Application) : AndroidViewModel(app) {

  private val habitDao = AppDatabase.getInstance(app).habitDao()

  val today: String get() = LocalDate.now().toString()

  val habits: StateFlow<List<Habit>> = habitDao.allHabits()
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

  val todayCompletions: StateFlow<Set<Long>> = habitDao.completionsForDate(today)
  .map { list -> list.map { it.habitId }.toSet() }
  .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

  fun addHabit(name: String, emoji: String = "") {
  if (name.isBlank()) return
  viewModelScope.launch(Dispatchers.IO) {
  habitDao.insertHabit(Habit(name = name.trim(), emoji = emoji.trim()))
  }
  }

  fun deleteHabit(habit: Habit) {
  viewModelScope.launch(Dispatchers.IO) {
  habitDao.deleteAllCompletionsForHabit(habit.id)
  habitDao.deleteHabit(habit)
  }
  }

  fun toggleHabit(habitId: Long) {
  viewModelScope.launch(Dispatchers.IO) {
  val isCompleted = habitDao.isCompleted(habitId, today)
  if (isCompleted) {
  habitDao.markIncomplete(habitId, today)
  } else {
  habitDao.markComplete(HabitCompletion(habitId = habitId, completedDate = today))
  }
  }
  }
}
