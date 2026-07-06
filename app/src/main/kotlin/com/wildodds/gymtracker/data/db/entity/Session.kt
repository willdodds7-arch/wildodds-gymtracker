package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "sessions",
  foreignKeys = [ForeignKey(
  entity = Program::class,
  parentColumns = ["id"],
  childColumns = ["programId"],
  onDelete = ForeignKey.CASCADE
  )],
  indices = [Index("programId")]
)
data class Session(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val programId: Long,
  val weekNumber: Int,
  val dayNumber: Int,
  val name: String,
  val muscleGroups: String,
  // Which phase this session belongs to. Single-block programs use phase 1 for all.
  val phaseNumber: Int = 1
)
