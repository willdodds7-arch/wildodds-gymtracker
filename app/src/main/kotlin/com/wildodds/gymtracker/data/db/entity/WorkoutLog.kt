package com.wildodds.gymtracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
  tableName = "workout_logs",
  foreignKeys = [
  ForeignKey(entity = Exercise::class, parentColumns = ["id"], childColumns = ["exerciseId"], onDelete = ForeignKey.CASCADE),
  ForeignKey(entity = Session::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)
  ],
  indices = [Index("exerciseId"), Index("sessionId")]
)
data class WorkoutLog(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val exerciseId: Long,
  val sessionId: Long,
  val weekNumber: Int,
  val completedAt: Long = System.currentTimeMillis(),
  val progressionChoice: String? = null,
  // ── Sync metadata (Phase 3, online-first) ────────────────────────────────────
  // syncId: globally-unique row identity across devices ('' until the insert trigger fills it).
  // updatedAt: last local modification (epoch ms), maintained by SQLite triggers — the
  // last-write-wins key for sync. See SyncTriggers + MIGRATION_20_21.
  @ColumnInfo(defaultValue = "") val syncId: String = "",
  @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
)
