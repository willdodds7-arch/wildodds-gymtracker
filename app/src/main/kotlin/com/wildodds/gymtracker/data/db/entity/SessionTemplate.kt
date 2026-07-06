package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A reusable workout "session" that lives in the Session Library independently of
 * any program — e.g. "Arm Day", "Push A". Created in the program builder, imported
 * from existing program days, or built standalone. Can be loaded into a program day.
 */
@Entity(tableName = "session_templates")
data class SessionTemplate(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val muscleGroups: String = "",
  val notes: String = "",
  val createdAt: Long = System.currentTimeMillis(),
  // Where it came from: "Custom" for user-built, otherwise the source program name.
  val source: String = "Custom"
)

@Entity(
  tableName = "session_template_exercises",
  foreignKeys = [ForeignKey(
  entity = SessionTemplate::class,
  parentColumns = ["id"],
  childColumns = ["templateId"],
  onDelete = ForeignKey.CASCADE
  )],
  indices = [Index("templateId")]
)
data class SessionTemplateExercise(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val templateId: Long,
  val name: String,
  val sets: Int,
  val repsTarget: String,
  val notes: String = "",
  val orderIndex: Int,
  val rpeTarget: String = "",
  val pct1rmTarget: String = ""
)
