package com.wildodds.gymtracker.data.cap3

/**
 * nSuns **CAP3** — Cyclical AMRAP Progression, 3-week.
 *
 * A 3-week cycle that repeats (week 1 → 2 → 3 → 1). Each week trains the three main lifts twice
 * across six training days; once per cycle each main lift takes a heavy Major-Effort (ME) test.
 * Sets, reps and percentages differ every week.
 *
 * CAP3 runs as an ordinary program: [Cap3ProgramBuilder] turns a config into real program rows and
 * the normal session screen runs them. What stays special is pricing — the main lifts carry a
 * [Cap3Tag] and are priced from the user's current training max at render time, so a training max
 * that moves mid-cycle is reflected everywhere immediately.
 *
 * This file holds the vocabulary; [Cap3Schemes] holds the week-by-week numbers taken from the
 * source spreadsheet and [Cap3Engine] does the arithmetic.
 *
 * ## Units
 * Reference maxes and training maxes are stored in **kilograms** so switching Kg↔Lb never mutates
 * the user's stored numbers — only the display unit and its rounding step change.
 */
enum class Cap3Units(val label: String, val step: Double, val perKg: Double) {
  KG("Kg", 2.5, 1.0),
  LB("Lb", 5.0, 2.2046226218);

  fun fromKg(kg: Double): Double = kg * perKg
  fun toKg(value: Double): Double = value / perKg

  companion object {
    fun byLabel(label: String?): Cap3Units = entries.firstOrNull { it.label == label } ?: KG
  }
}

/** The sheet's colour legend, as the four states a prescribed set can be in. */
enum class Cap3SetType {
  /** Beige/none — do exactly the listed reps. */
  FIXED,
  /** Yellow — as many reps as possible. Drives progression. */
  AMRAP,
  /** Green — hit at least the listed minimum, optional AMRAP beyond it. Rendered as "N+". */
  MIN_THEN_OPTIONAL_AMRAP,
  /** Red — only if feeling good; not required. */
  OPTIONAL;

  /** True when logging extra reps on this set can move a training max. */
  val isAmrapCapable: Boolean
    get() = this == AMRAP || this == MIN_THEN_OPTIONAL_AMRAP
}

/** Every working weight snaps to the unit's step; the sheet picks the direction per set. */
enum class Cap3Rounding { FLOOR, CEILING }

/** What a CAP3-tagged exercise is within its day. */
enum class Cap3Role {
  /** The primary lift: the percentage scheme, or the Major-Effort test. */
  T1,
  /** The secondary lift: top set + MRS (or a de-emphasis week's rep-range top set + backoff). */
  T2,
  /** The EMOM tail of the secondary lift, which gets its own page. */
  EMOM
}

/** The five dropdown lists from the setup sheet, verbatim and in sheet order. */
enum class Cap3LiftCategory(val label: String, val options: List<String>) {
  SQUAT("Squat", listOf(
    "Low Bar Squat", "High Bar Squat", "Front Squat", "SSB Squat", "Low Box Squat",
    "High Box Squat", "Paused Squat", "Belt Squat", "Split Squat", "Wide Stance Squat",
    "Narrow Stance Squat", "Duffalo Bar Squat", "Lunge", "SSB Lunge", "Hatfield Squat",
    "Other Squat"
  )),
  BENCH("Bench/Press", listOf(
    "Paused Bench", "Flat Bench", "Close Grip Bench", "Incline Bench", "DB Bench",
    "DB Incline Bench", "Slingshot Bench", "Spoto Bench", "Feet up Bench", "JM Press",
    "Duffalo Bar Bench", "Bench With Chains", "Other Bench"
  )),
  DEADLIFT("Deadlift", listOf(
    "Deadlift", "Sumo Deadlift", "Deficit Deadlift", "Sumo Deficit DL", "Rack Pull",
    "Sumo Rack Pull", "Block Pull", "Sumo Block Pull", "Paused Deadlift", "Romanian DL",
    "SLDL", "Axle Deadlift", "Deadlift With Chains", "Other Deadlift"
  )),
  OVERHEAD("Overhead", listOf(
    "Strict OHP", "Push Press", "DB OHP", "Seated Press", "Seated DB OHP",
    "1 Arm DB Press", "Z Press", "Other Press"
  )),
  ROW("Row/Pull", listOf(
    "Strict Barbell Row", "Barbell Cheat Row", "Reverse Grip BB Row", "1 Arm DB Row",
    "Chest Supported Row", "T-Bar Row", "Cable Row", "Other Row"
  ))
}

/**
 * The ten lift slots: two variation slots per category, each with its own reference max → TM.
 * The main lifts use variation 1 in their heavier "A" session and variation 2 in the "B" session.
 */
enum class Cap3Slot(
  val category: Cap3LiftCategory,
  val variation: Int,
  val defaultExercise: String
) {
  SQUAT_1(Cap3LiftCategory.SQUAT, 1, "Low Bar Squat"),
  SQUAT_2(Cap3LiftCategory.SQUAT, 2, "High Bar Squat"),
  BENCH_1(Cap3LiftCategory.BENCH, 1, "Paused Bench"),
  BENCH_2(Cap3LiftCategory.BENCH, 2, "Flat Bench"),
  DEADLIFT_1(Cap3LiftCategory.DEADLIFT, 1, "Deadlift"),
  DEADLIFT_2(Cap3LiftCategory.DEADLIFT, 2, "Sumo Deadlift"),
  OVERHEAD_1(Cap3LiftCategory.OVERHEAD, 1, "Strict OHP"),
  OVERHEAD_2(Cap3LiftCategory.OVERHEAD, 2, "Push Press"),
  ROW_1(Cap3LiftCategory.ROW, 1, "Strict Barbell Row"),
  ROW_2(Cap3LiftCategory.ROW, 2, "Cable Row");

  val label: String get() = "${category.label} $variation"

  companion object {
    fun byName(name: String?): Cap3Slot? = entries.firstOrNull { it.name == name }
  }
}

/**
 * The six training days of a week, in order.
 *
 * [defaultSecondary] pairs a Lift-2 slot with each day. The source fixture fixes only one of these
 * by name — Cable Row on the week-1 deadlift-B day, which is why Row variation 2 sits there. The
 * rest follow the day titles (pressing on "Chest & Biceps" and "Shoulders & Legs", pulling on
 * "Back, Abs & Triceps") and are a starting point, not gospel: the user can re-point any day at
 * another Overhead or Row variation in setup. Secondary *loads* do not depend on this choice — they
 * come from the slot's own training max — so re-pointing a day never changes the prescription.
 */
enum class Cap3DaySlot(
  val index: Int,
  val title: String,
  val primary: Cap3Slot,
  val defaultSecondary: Cap3Slot
) {
  BENCH_A(0, "Chest & Biceps", Cap3Slot.BENCH_1, Cap3Slot.OVERHEAD_1),
  DEADLIFT_A(1, "Back, Abs & Triceps", Cap3Slot.DEADLIFT_1, Cap3Slot.ROW_1),
  SQUAT_A(2, "Shoulders & Legs", Cap3Slot.SQUAT_1, Cap3Slot.OVERHEAD_2),
  BENCH_B(3, "Chest & Biceps", Cap3Slot.BENCH_2, Cap3Slot.OVERHEAD_1),
  DEADLIFT_B(4, "Back, Abs & Triceps", Cap3Slot.DEADLIFT_2, Cap3Slot.ROW_2),
  SQUAT_B(5, "Legs & Shoulders", Cap3Slot.SQUAT_2, Cap3Slot.OVERHEAD_2);

  companion object {
    fun byIndex(index: Int): Cap3DaySlot = entries.first { it.index == index }
    fun byName(name: String?): Cap3DaySlot? = entries.firstOrNull { it.name == name }
  }
}

const val CAP3_WEEKS = 3
const val CAP3_DAYS_PER_WEEK = 6

/** TM = referenceMax × 0.90. */
const val CAP3_TM_FACTOR = 0.90

/**
 * What an exercise row carries so it can be priced later: which week, which day, and what part of
 * the day it is. Serialised into `Exercise.cap3Tag` as "1:BENCH_A:T1".
 */
data class Cap3Tag(val week: Int, val daySlot: Cap3DaySlot, val role: Cap3Role) {
  override fun toString(): String = "$week:${daySlot.name}:${role.name}"

  companion object {
    fun parse(raw: String?): Cap3Tag? {
      val parts = raw?.split(':') ?: return null
      if (parts.size != 3) return null
      val week = parts[0].toIntOrNull()?.takeIf { it in 1..CAP3_WEEKS } ?: return null
      val slot = Cap3DaySlot.byName(parts[1]) ?: return null
      val role = Cap3Role.entries.firstOrNull { it.name == parts[2] } ?: return null
      return Cap3Tag(week, slot, role)
    }

    /**
     * Whether a day's exercise tags mark it as the week's major-effort test. Days from any other
     * program have no tags, so this is false for them.
     */
    fun isMajorEffortDay(tags: List<String>): Boolean = tags.any { raw ->
      val tag = parse(raw)
      tag != null && tag.role == Cap3Role.T1 && Cap3Schemes.isMajorEffort(tag.week, tag.daySlot)
    }
  }
}

/**
 * One configured lift. [referenceMaxKg] is what the user entered; [trainingMaxKg] starts at 90% of
 * it and is what progression moves. [bestEst1RmKg] is the estimated max an AMRAP has to beat to
 * earn a max-based jump.
 */
data class Cap3LiftConfig(
  val slot: Cap3Slot,
  val exercise: String = slot.defaultExercise,
  val referenceMaxKg: Double = 0.0,
  val trainingMaxKg: Double = referenceMaxKg * CAP3_TM_FACTOR,
  val bestEst1RmKg: Double = referenceMaxKg
) {
  val isConfigured: Boolean get() = referenceMaxKg > 0.0 && trainingMaxKg > 0.0
}

/** A user's CAP3 lift setup. Position in the cycle is the program's job, not this object's. */
data class Cap3Config(
  val units: Cap3Units = Cap3Units.KG,
  val lifts: Map<Cap3Slot, Cap3LiftConfig> = Cap3Slot.entries.associateWith { Cap3LiftConfig(it) },
  val secondaryOverrides: Map<Cap3DaySlot, Cap3Slot> = emptyMap()
) {
  fun lift(slot: Cap3Slot): Cap3LiftConfig = lifts[slot] ?: Cap3LiftConfig(slot)

  fun secondarySlot(day: Cap3DaySlot): Cap3Slot = secondaryOverrides[day] ?: day.defaultSecondary

  /** True once every slot a generated program would need has a max. */
  val isSetUp: Boolean
    get() = Cap3DaySlot.entries.all {
      lift(it.primary).isConfigured && lift(secondarySlot(it)).isConfigured
    }

  fun withTrainingMax(slot: Cap3Slot, trainingMaxKg: Double, bestEst1RmKg: Double? = null): Cap3Config {
    val current = lift(slot)
    return copy(lifts = lifts + (slot to current.copy(
      trainingMaxKg = trainingMaxKg,
      bestEst1RmKg = bestEst1RmKg ?: current.bestEst1RmKg
    )))
  }
}

// ── Rendered output ───────────────────────────────────────────────────────────

/** One prescribed set, with its weight already snapped to the display unit's step. */
data class Cap3Set(
  val label: String,
  val weight: Double,
  val repsTarget: Int,
  val repsDisplay: String,
  val type: Cap3SetType
) {
  val isAmrapCapable: Boolean get() = type.isAmrapCapable
}

/** The EMOM tail of a secondary lift: keep going at [weight] until you cannot hit [failBelowReps]. */
data class Cap3Emom(val weight: Double, val failBelowReps: Int)

data class Cap3LiftBlock(
  val role: Cap3Role,
  val slot: Cap3Slot,
  val exercise: String,
  val trainingMax: Double,
  val units: Cap3Units,
  val sets: List<Cap3Set>,
  val isMajorEffort: Boolean = false,
  val emom: Cap3Emom? = null
)

data class Cap3Day(
  val week: Int,
  val dayNumber: Int,
  val title: String,
  val daySlot: Cap3DaySlot,
  val isMajorEffort: Boolean,
  val lift1: Cap3LiftBlock?,
  val lift2: Cap3LiftBlock?
)
