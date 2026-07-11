package com.wildodds.gymtracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "programs")
data class Program(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,
  val totalWeeks: Int,
  val createdAt: Long = System.currentTimeMillis(),
  // Stores either "1".."5" for built-in gradients, a content:// URI for gallery picks,
  // or null to fall back to the default gradient.
  val coverImage: String? = null,
  val isFlexible: Boolean = false,  // "build as you go" - sessions start with no exercises
  val category: String = "",  // e.g. "Hypertrophy", "Strength", "Powerlifting", "GPP"
  val isActive: Boolean = false,  // the program currently shown on the home screen
  val isUserCreated: Boolean = false, // created via the builder - kept when default programs are loaded
  val trackRpe: Boolean = false,  // show RPE column per set
  val trackOneRm: Boolean = false, // show % of 1RM column per set
  val isPaused: Boolean = false,  // paused mid-program to allow switching
  // The phase currently being run (1-based). Always 1 for single-block programs.
  val currentPhase: Int = 1,
  // ── Browse metadata (v18) ──────────────────────────────────────────────────
  val description: String = "",   // a short blurb shown on the program detail screen
  val coach: String = "",         // the coach / author name (filterable, links to a coach page)
  val coachBio: String = "",      // the coach's description, shown on their page
  val daysPerWeek: Int = 0,       // tag: training days per week (0 = unset / derive from sessions)
  val split: String = "",         // tag: e.g. "Full Body", "Upper/Lower", "PPL"
  val style: String = "",         // tag: e.g. "Hypertrophy", "Strength", "Powerlifting"
  // Vestigial: the Running tab that read this flag was removed. Left in place (always false,
  // dead column) rather than dropped — SQLite's ALTER TABLE DROP COLUMN needs SQLite 3.35+
  // (~Android 12L), newer than this app's minSdk 26 devices can guarantee. See AppDatabase
  // MIGRATION_19_20.
  val isRunning: Boolean = false,
  // ── Sync metadata (Phase 3, online-first) ────────────────────────────────────
  // syncId: globally-unique row identity across devices ('' until the insert trigger fills it).
  // updatedAt: last local modification (epoch ms), maintained by SQLite triggers — the
  // last-write-wins key for sync. See SyncTriggers + MIGRATION_20_21.
  @ColumnInfo(defaultValue = "") val syncId: String = "",
  @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0
)
