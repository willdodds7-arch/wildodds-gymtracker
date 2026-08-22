package com.wildodds.gymtracker.data.db.entity

import androidx.room.ColumnInfo
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
  val phaseNumber: Int = 1,
  /**
   * Name of a [com.wildodds.gymtracker.data.importer.SessionType]; unknown values read as TRAINING.
   *
   * A program that says "Wed — off" is a 5-day week containing a rest day, not a 4-day week. Before
   * v26 the schema had no way to say that, so every import quietly dropped the rest days and came
   * back as a different program from the one the coach wrote.
   */
  @ColumnInfo(defaultValue = "TRAINING") val sessionType: String = "TRAINING",
  // ── Sync metadata (Phase 3, online-first) ────────────────────────────────────
  // syncId: globally-unique row identity across devices ('' until the insert trigger fills it).
  // updatedAt: last local modification (epoch ms), maintained by SQLite triggers — the
  // last-write-wins key for sync. See SyncTriggers + MIGRATION_20_21.
  @ColumnInfo(defaultValue = "") val syncId: String = "",
  @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
)
