package com.wildodds.gymtracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One PRESCRIBED set of an exercise — the plan, not the log.
 *
 * Why this exists: `Exercise` describes every set uniformly (`sets: Int` + `repsTarget: String`),
 * which cannot express "one warm-up at 20 kg then three working sets at 60 kg for 8–12". This table
 * carries that detail per set.
 *
 * Strictly ADDITIVE. `Exercise.sets`/`repsTarget` remain the source of truth for every existing
 * screen, importer and the CAP3 engine, and the builder keeps writing them (set count, and the
 * modal rep target) so nothing that reads them changes behaviour. An exercise with no rows here
 * simply has no per-set detail, which is exactly the state of every program that predates this.
 *
 * Not the same as [SetLog]: that records what the user actually did.
 */
@Entity(
  tableName = "planned_sets",
  foreignKeys = [ForeignKey(
    entity = Exercise::class,
    parentColumns = ["id"],
    childColumns = ["exerciseId"],
    onDelete = ForeignKey.CASCADE
  )],
  indices = [Index("exerciseId")]
)
data class PlannedSet(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val exerciseId: Long,
  /** 1-based position within the exercise. */
  val setNumber: Int,
  /** Name of a `com.wildodds.gymtracker.data.builder.SetType`; unknown values read as WORKING. */
  val type: String = "WORKING",
  /** Free text: "60", "bodyweight", "" — never validated, never blocks a save. */
  val load: String = "",
  /** "8", "8-12", "AMRAP", "30s". */
  val reps: String = "",
  val restSeconds: Int = 90,
  val rpe: String = "",
  val tempo: String = "",
  val notes: String = ""
)

/**
 * A user-defined swap for an exercise (§3.3). Stored by name so it survives the exercise library
 * changing underneath it, and so an alternative can be something not in the library at all.
 */
@Entity(
  tableName = "exercise_alternatives",
  foreignKeys = [ForeignKey(
    entity = Exercise::class,
    parentColumns = ["id"],
    childColumns = ["exerciseId"],
    onDelete = ForeignKey.CASCADE
  )],
  indices = [Index("exerciseId")]
)
data class ExerciseAlternative(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val exerciseId: Long,
  val name: String,
  val orderIndex: Int = 0,
  /** Free text: "if the rack is busy", "shoulder feels off". */
  @ColumnInfo(defaultValue = "") val note: String = ""
)
