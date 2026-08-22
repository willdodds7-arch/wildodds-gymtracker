package com.wildodds.gymtracker.data.cap3

import com.wildodds.gymtracker.data.parser.ParsedExercise
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.parser.ParsedSession

/**
 * Turns a CAP3 lift setup into an ordinary program: 3 weeks × 6 training days, imported through the
 * same path as every other program, so it shows on Home, runs in the normal session screen and
 * logs to the normal history.
 *
 * Each day is: T1, T2, the T2 EMOM on its own page (when that week runs one), then accessories.
 * Only the three main-lift rows carry a [Cap3Tag]; the accessories are plain exercises the user can
 * edit, swap, delete or add to like any other program's.
 */
object Cap3ProgramBuilder {

  const val PROGRAM_NAME = "nSuns CAP3"

  /**
   * Default accessory work per day. Deliberately ordinary rows — CAP3 prescribes the two main lifts
   * and leaves the rest to the lifter, so these are a sensible starting point, not part of the
   * method.
   */
  private val accessories: Map<Cap3DaySlot, List<Pair<String, Pair<Int, String>>>> = mapOf(
    Cap3DaySlot.BENCH_A to listOf(
      "Incline DB Press" to (3 to "8-12"),
      "Cable Fly" to (3 to "12-15"),
      "Barbell Curl" to (3 to "8-12"),
      "Hammer Curl" to (3 to "12-15")
    ),
    Cap3DaySlot.DEADLIFT_A to listOf(
      "Lat Pulldown" to (3 to "10-12"),
      "Face Pulls" to (3 to "15-20"),
      "Tricep Pushdown" to (3 to "12-15"),
      "Hanging Leg Raise" to (3 to "10-15")
    ),
    Cap3DaySlot.SQUAT_A to listOf(
      "Lateral Raises" to (4 to "12-15"),
      "Leg Curl" to (3 to "10-12"),
      "Leg Extension" to (3 to "12-15"),
      "Calf Raise" to (4 to "10-15")
    ),
    Cap3DaySlot.BENCH_B to listOf(
      "Dips" to (3 to "8-12"),
      "Pec Deck" to (3 to "12-15"),
      "Preacher Curl" to (3 to "10-12"),
      "Cable Curl" to (3 to "12-15")
    ),
    Cap3DaySlot.DEADLIFT_B to listOf(
      "Chest Supported Row" to (3 to "10-12"),
      "Rear Delt Fly" to (3 to "15-20"),
      "Skull Crushers" to (3 to "10-12"),
      "Cable Crunch" to (3 to "12-15")
    ),
    Cap3DaySlot.SQUAT_B to listOf(
      "Bulgarian Split Squat" to (3 to "8-12"),
      "Romanian DL" to (3 to "8-12"),
      "Lateral Raises" to (4 to "12-15"),
      "Calf Raise" to (4 to "10-15")
    )
  )

  fun build(config: Cap3Config): ParsedProgram {
    val sessions = (1..CAP3_WEEKS).flatMap { week ->
      Cap3DaySlot.entries.map { slot -> session(config, week, slot) }
    }
    return ParsedProgram(
      name = PROGRAM_NAME,
      totalWeeks = CAP3_WEEKS,
      sessions = sessions,
      coverImage = "3",
      category = "Strength",
      description = "nSuns CAP3 — a 3-week AMRAP cycle. Each week trains squat, bench and deadlift " +
        "twice, and one of them takes a heavy major-effort test. Your main-lift weights come from " +
        "your training maxes and update themselves when an AMRAP earns it.",
      coach = "nSuns",
      daysPerWeek = CAP3_DAYS_PER_WEEK,
      split = "Upper/Lower",
      style = "Strength"
    )
  }

  private fun session(config: Cap3Config, week: Int, slot: Cap3DaySlot): ParsedSession {
    val exercises = mutableListOf<ParsedExercise>()
    var order = 0

    fun add(e: ParsedExercise) { exercises += e.copy(orderIndex = order++) }

    val primary = config.lift(slot.primary)
    val secondary = config.lift(config.secondarySlot(slot))
    val isMajorEffort = Cap3Schemes.isMajorEffort(week, slot)

    // T1. Set count and reps come from the scheme so the row reads sensibly in lists and history;
    // the actual weights are priced from the training max when the session is opened.
    val t1Scheme = Cap3Schemes.primaryScheme(week, slot)
    add(ParsedExercise(
      name = primary.exercise,
      sets = t1Scheme?.sumOf { it.sets } ?: Cap3MajorEffort.options.size,
      repsTarget = t1Scheme?.let { groups ->
        val reps = groups.map { it.reps }.distinct()
        if (reps.size == 1) "${reps.first()}" else "${reps.min()}-${reps.max()}"
      } ?: "6/4/2",
      notes = if (isMajorEffort) "Major-effort test — pick one option and take it for reps." else "",
      orderIndex = 0,
      cap3Tag = Cap3Tag(week, slot, Cap3Role.T1).toString()
    ))

    // T2.
    val t2Scheme = Cap3Schemes.secondaryScheme(week, slot)
    add(ParsedExercise(
      name = secondary.exercise,
      sets = when (t2Scheme) {
        is Cap3SecondaryScheme.Amrap -> 2
        is Cap3SecondaryScheme.Backoff -> 1 + t2Scheme.backoffSets
      },
      repsTarget = when (t2Scheme) {
        is Cap3SecondaryScheme.Amrap -> "${t2Scheme.mrsReps}-${t2Scheme.topReps}"
        is Cap3SecondaryScheme.Backoff -> t2Scheme.repRange
      },
      notes = "",
      orderIndex = 0,
      cap3Tag = Cap3Tag(week, slot, Cap3Role.T2).toString()
    ))

    // The EMOM gets its own page, on the weeks that run one.
    if (Cap3Engine.hasEmom(week, slot)) {
      val emomScheme = t2Scheme as Cap3SecondaryScheme.Amrap
      add(ParsedExercise(
        name = "${secondary.exercise} — EMOM",
        // An EMOM runs until you fail, so there is no true set count. Starting at one gives the
        // page a row to fill and keeps the day's exercise list reading sensibly.
        sets = 1,
        repsTarget = "${emomScheme.emomFailReps}+",
        notes = "One minute rest between sets. Stop once a set drops below ${emomScheme.emomFailReps} reps.",
        orderIndex = 0,
        cap3Tag = Cap3Tag(week, slot, Cap3Role.EMOM).toString()
      ))
    }

    accessories.getValue(slot).forEach { (name, prescription) ->
      val (sets, reps) = prescription
      add(ParsedExercise(name = name, sets = sets, repsTarget = reps, notes = "", orderIndex = 0))
    }

    return ParsedSession(
      weekNumber = week,
      dayNumber = slot.index + 1,
      name = slot.title,
      muscleGroups = muscleGroups(slot),
      exercises = exercises
    )
  }

  private fun muscleGroups(slot: Cap3DaySlot): String = when (slot) {
    Cap3DaySlot.BENCH_A, Cap3DaySlot.BENCH_B -> "Chest / Shoulders / Biceps"
    Cap3DaySlot.DEADLIFT_A, Cap3DaySlot.DEADLIFT_B -> "Back / Hamstrings / Triceps / Abs"
    Cap3DaySlot.SQUAT_A, Cap3DaySlot.SQUAT_B -> "Quads / Glutes / Shoulders"
  }
}
