package com.wildodds.gymtracker.data.db.entity

import kotlinx.serialization.Serializable

/**
 * One genuinely-distinct prescribed set, as stored on [Exercise.perSet].
 *
 * Why a JSON column and not rows in `planned_sets`: `planned_sets` is the *builder's* editable
 * model, owned by the user and reshaped freely. This is the *importer's* record of what the source
 * actually said, set by set, and it must survive unchanged so that "re-parse this import with the
 * current parser" can diff against it. `5/3/1` lands here as three sets with three rep counts, and
 * `10,8,6` as three — instead of being averaged into a single `repsTarget` string.
 *
 * Every field is nullable or defaulted, and **null means the source didn't say**. A set with no
 * stated reps has `reps = ""`, not `"8-12"`.
 */
@Serializable
data class StoredSetPrescription(
  /** 1-based position within the exercise. */
  val setNumber: Int,
  /** Rendered for display: "8", "8-12", "AMRAP", "30s", "400m". Empty when unstated. */
  val reps: String = "",
  /** Name of a [com.wildodds.gymtracker.data.importer.RepKind]; unknown values read as TEXT. */
  val repKind: String = "TEXT",
  val repMin: Int? = null,
  val repMax: Int? = null,
  /** Name of a [com.wildodds.gymtracker.data.importer.LoadKind]; unknown values read as NONE. */
  val loadKind: String = "NONE",
  val loadValue: Double? = null,
  val loadValueMax: Double? = null,
  /** Name of a [com.wildodds.gymtracker.data.importer.LoadUnit]; unknown values read as NONE. */
  val loadUnit: String = "NONE",
  /** Verbatim tempo, e.g. "31X0". Empty when unstated. */
  val tempo: String = "",
  val restSeconds: Int? = null,
  val restRangeSecondsMax: Int? = null,
  val isAmrap: Boolean = false,
  /** Canonical modifiers — see [com.wildodds.gymtracker.data.importer.Modifier]. */
  val modifiers: List<String> = emptyList(),
  /** The verbatim fragment of the source this set came from. */
  val raw: String = ""
)
