package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One phase (block) of a multi-phase program. A program runs Phase 1 for its
 * [durationWeeks] weeks, then Phase 2, etc. Each phase has its own Week 1..N of
 * [Session]s (sessions carry a matching phaseNumber).
 *
 * Programs with zero or one ProgramPhase rows behave exactly like the old
 * single-block programs — the Home/Library interface is unchanged for them.
 */
@Entity(
  tableName = "program_phases",
  foreignKeys = [ForeignKey(
  entity = Program::class,
  parentColumns = ["id"],
  childColumns = ["programId"],
  onDelete = ForeignKey.CASCADE
  )],
  indices = [Index("programId")]
)
data class ProgramPhase(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val programId: Long,
  val phaseNumber: Int,        // 1-based
  val name: String,            // e.g. "Accumulation", "Intensification"
  val durationWeeks: Int,      // number of weeks this phase runs
  val focus: String = ""       // optional one-line focus / description
)
