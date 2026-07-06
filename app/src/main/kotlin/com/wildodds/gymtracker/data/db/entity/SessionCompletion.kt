package com.wildodds.gymtracker.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "session_completions",
  foreignKeys = [ForeignKey(
  entity = Session::class,
  parentColumns = ["id"],
  childColumns = ["sessionId"],
  onDelete = ForeignKey.CASCADE
  )],
  indices = [Index("sessionId")]
)
data class SessionCompletion(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val sessionId: Long,
  val weekNumber: Int,
  val completedAt: Long = System.currentTimeMillis(),
  // ── Post-session summary (Phase 2A) ──────────────────────────────────────────
  // Extended onto SessionCompletion rather than a new SessionSummary entity: the summary is
  // strictly 1:1 with a completion (it exists iff the session was completed) and shares its exact
  // lifecycle — created on Complete, wiped on reset-day / program-delete. Reusing this row means
  // every existing cascade + delete path (deleteCompletion, deleteForProgram, deleteAll) cleans up
  // the summary automatically; a separate entity would duplicate the FK and all of those deletes.
  // All nullable — completions made before this feature (and ones still awaiting a strain pick)
  // simply have no summary data.
  val durationSeconds: Long? = null,
  val strainRating: Int? = null,       // 1 (easy) .. 5 (maximal)
  val avgHeartRate: Int? = null,
  val peakHeartRate: Int? = null,
  val hrSeries: String? = null,         // compact JSON, e.g. "[72,80,95]"
  // Phase 3F — final in-session fatigue score (0..100) for later trend use.
  val fatigueScore: Int? = null
)
