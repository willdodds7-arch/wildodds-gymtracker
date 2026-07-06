package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "exercises",
  foreignKeys = [ForeignKey(
  entity = Session::class,
  parentColumns = ["id"],
  childColumns = ["sessionId"],
  onDelete = ForeignKey.CASCADE
  )],
  indices = [Index("sessionId")]
)
data class Exercise(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long,
  val name: String,
  val sets: Int,
  val repsTarget: String,
  val notes: String = "",
  val orderIndex: Int,
  val rpeTarget: String = "",
  val pct1rmTarget: String = "",
  val supersetGroupId: Int? = null,
  // 0 = normal, 1 = single weight + L/R reps, 2 = L/R weight + L/R reps
  val unilateralMode: Int = 0
)
