package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "set_logs",
  foreignKeys = [ForeignKey(
  entity = WorkoutLog::class,
  parentColumns = ["id"],
  childColumns = ["workoutLogId"],
  onDelete = ForeignKey.CASCADE
  )],
  indices = [Index("workoutLogId")]
)
data class SetLog(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val workoutLogId: Long,
  val setNumber: Int,
  val weightKg: Float? = null,
  val reps: Int? = null,
  val rpe: Float? = null,  // Rate of Perceived Exertion (1-10)
  val pct1rm: Float? = null,  // Percentage of 1-rep max (0-100)
  // Unilateral exercises: weightKg/reps hold the LEFT side, these hold the RIGHT
  val weightRightKg: Float? = null,
  val repsRight: Int? = null
)
