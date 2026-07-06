package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "habit_completions",
  indices = [Index(value = ["habitId", "completedDate"], unique = true)]
)
data class HabitCompletion(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val habitId: Long,
  val completedDate: String // "YYYY-MM-DD"
)
