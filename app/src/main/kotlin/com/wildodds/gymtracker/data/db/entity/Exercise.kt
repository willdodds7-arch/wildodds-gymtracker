package com.wildodds.gymtracker.data.db.entity

import androidx.room.ColumnInfo
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
  val unilateralMode: Int = 0,
  // Non-empty on the main lifts of an nSuns CAP3 program: "week:daySlot:role", e.g.
  // "1:BENCH_A:T1". The session screen reads it to render the prescribed sets from the user's
  // current training max rather than from stored weights, so a training-max change is reflected
  // immediately. Accessories leave it blank and behave like any other exercise.
  @ColumnInfo(defaultValue = "") val cap3Tag: String = "",
  // ── Sync metadata (Phase 3, online-first) ────────────────────────────────────
  // syncId: globally-unique row identity across devices ('' until the insert trigger fills it).
  // updatedAt: last local modification (epoch ms), maintained by SQLite triggers — the
  // last-write-wins key for sync. See SyncTriggers + MIGRATION_20_21.
  @ColumnInfo(defaultValue = "") val syncId: String = "",
  @ColumnInfo(defaultValue = "0") val updatedAt: Long = 0,

  // ── Reader v2 prescription detail (v26) ─────────────────────────────────────
  // Everything below is what the SOURCE said, as opposed to what the app renders. All of it is
  // nullable and defaults to "unknown", and every one of these used to be flattened into `notes`.
  //
  // NULL genuinely means "the source didn't say" throughout. In particular [setsStated] is the
  // honest set count: [sets] must stay non-null because every screen reads it, so an exercise whose
  // source never stated a count is committed with `setsStated = null` and raises a REVIEW flag
  // before commit rather than silently becoming one set.
  @ColumnInfo(defaultValue = "NULL") val setsStated: Int? = null,
  @ColumnInfo(defaultValue = "NULL") val restSeconds: Int? = null,
  @ColumnInfo(defaultValue = "NULL") val restRangeSecondsMax: Int? = null,
  /** Validated 4-slot tempo, e.g. "31X0" or "3-1-2-0". NULL when unstated or malformed. */
  @ColumnInfo(defaultValue = "NULL") val tempo: String? = null,
  /** The letter in "A1) Bench" — the superset/circuit this exercise belongs to. */
  @ColumnInfo(defaultValue = "NULL") val supersetGroup: String? = null,
  /** The digit in "A1) Bench" — position within the group. NULL when the source didn't number it. */
  @ColumnInfo(defaultValue = "NULL") val supersetOrder: Int? = null,
  /** Name of a [com.wildodds.gymtracker.data.importer.LoadKind]; unknown values read as NONE. */
  @ColumnInfo(defaultValue = "NULL") val loadKind: String? = null,
  @ColumnInfo(defaultValue = "NULL") val loadValue: Double? = null,
  /** Upper bound of a stated load range ("@ 60-70kg"). NULL when the load was a single value. */
  @ColumnInfo(defaultValue = "NULL") val loadValueMax: Double? = null,
  /** Name of a [com.wildodds.gymtracker.data.importer.LoadUnit]; unknown values read as NONE. */
  @ColumnInfo(defaultValue = "NULL") val loadUnit: String? = null,
  /**
   * Genuinely-different sets, as a typed JSON column — so "5/3/1" survives as three real set rows
   * instead of collapsing to one rep target. Empty when every set was the same.
   */
  @ColumnInfo(defaultValue = "") val perSet: List<StoredSetPrescription> = emptyList(),
  @ColumnInfo(defaultValue = "0") val isFinalSetAmrap: Boolean = false,
  /** Canonical modifiers — see [com.wildodds.gymtracker.data.importer.Modifier]. */
  @ColumnInfo(defaultValue = "") val modifiers: List<String> = emptyList(),
  /**
   * The verbatim source line this exercise was read from. **Always** retained, at every confidence
   * level. This is the audit trail, and the thing that makes a future parser fix retro-fixable:
   * "re-parse this import with the current parser" has nothing to work from without it.
   */
  @ColumnInfo(defaultValue = "NULL") val rawSourceLine: String? = null
)
