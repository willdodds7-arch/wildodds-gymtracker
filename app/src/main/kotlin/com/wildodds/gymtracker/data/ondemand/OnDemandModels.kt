package com.wildodds.gymtracker.data.ondemand

import com.wildodds.gymtracker.data.MuscleGroup
import com.wildodds.gymtracker.data.intelligence.Equipment

/** Where an on-demand session came from. */
enum class SessionSource { TEMPLATE, CATALOGUE, GENERATED }

data class OnDemandExercise(val name: String, val sets: Int, val repsTarget: String)

/** A ready-to-run standalone session offered in the on-demand library. */
data class OnDemandSession(
  val id: String,
  val title: String,
  val source: SessionSource,
  val muscleFocus: Set<MuscleGroup>,
  val durationMin: Int,
  val equipment: Set<Equipment>,
  val exercises: List<OnDemandExercise>,
  /** Why this is recommended for the user's current block (null = just browsable). */
  val reason: String? = null
)

/** Quiet filter chips. */
data class OnDemandFilter(
  val maxDurationMin: Int? = null,
  val muscle: MuscleGroup? = null,
  val equipment: Equipment? = null
)
