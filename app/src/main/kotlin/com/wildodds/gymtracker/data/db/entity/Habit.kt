package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "habits")
data class Habit(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val emoji: String = "",
  val createdAt: Long = System.currentTimeMillis()
)
