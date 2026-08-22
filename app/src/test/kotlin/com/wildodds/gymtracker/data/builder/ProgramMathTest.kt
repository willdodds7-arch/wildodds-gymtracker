package com.wildodds.gymtracker.data.builder

import com.wildodds.gymtracker.data.MuscleGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** Pure volume/duration/warning maths — no Android, so these run straight on the JVM. */
class ProgramMathTest {

  private fun workingSets(n: Int, reps: String = "8-12", rest: Int = 90) =
    List(n) { PlanSet(type = SetType.WORKING, reps = reps, restSeconds = rest) }

  private fun exercise(name: String, sets: Int, reps: String = "8-12", rest: Int = 90) =
    PlanExercise(name = name, sets = workingSets(sets, reps, rest))

  // ── Duration ───────────────────────────────────────────────────────────────

  @Test
  fun `rest day takes no time`() {
    val rest = PlanWorkout(name = "Rest", kind = WorkoutKind.REST)
    assertEquals(0, ProgramMath.estimateWorkoutMinutes(rest))
  }

  @Test
  fun `workout with no exercises takes no time`() {
    assertEquals(0, ProgramMath.estimateWorkoutMinutes(PlanWorkout(name = "Push")))
  }

  @Test
  fun `duration counts work plus rest per set plus fixed overhead`() {
    // 3 sets x (45s work + 90s rest) = 405s, + 300s overhead = 705s = 11.75min -> 12
    val w = PlanWorkout(name = "Push", exercises = listOf(exercise("Bench Press", 3)))
    assertEquals(12, ProgramMath.estimateWorkoutMinutes(w))
  }

  @Test
  fun `longer rest makes a workout longer`() {
    val short = PlanWorkout(exercises = listOf(exercise("Bench Press", 4, rest = 60)))
    val long = PlanWorkout(exercises = listOf(exercise("Bench Press", 4, rest = 180)))
    assertTrue(
      ProgramMath.estimateWorkoutMinutes(long) > ProgramMath.estimateWorkoutMinutes(short)
    )
  }

  @Test
  fun `superset is quicker than the same work done separately`() {
    val a = exercise("Dumbbell Curl", 3, rest = 90)
    val b = exercise("Rope Pushdown", 3, rest = 90)
    val separate = PlanWorkout(exercises = listOf(a, b))

    val group = PlanGroup(rounds = 3, restBetweenExercisesSeconds = 0, restAfterRoundSeconds = 90)
    val paired = PlanWorkout(
      exercises = listOf(a.copy(supersetGroupId = group.id), b.copy(supersetGroupId = group.id)),
      groups = listOf(group)
    )

    assertTrue(
      "pairing should save time, not cost it",
      ProgramMath.estimateWorkoutMinutes(paired) < ProgramMath.estimateWorkoutMinutes(separate)
    )
  }

  @Test
  fun `weekly minutes sum every training day`() {
    val program = PlanProgram(
      workouts = listOf(
        PlanWorkout(name = "Push", exercises = listOf(exercise("Bench Press", 3))),
        PlanWorkout(name = "Rest", kind = WorkoutKind.REST),
        PlanWorkout(name = "Pull", exercises = listOf(exercise("Barbell Row", 3)))
      )
    )
    assertEquals(24, ProgramMath.estimateWeeklyMinutes(program))
  }

  @Test
  fun `formatMinutes reads the way a person would say it`() {
    assertEquals("45 min", ProgramMath.formatMinutes(45))
    assertEquals("1 h", ProgramMath.formatMinutes(60))
    assertEquals("4 h 20 min", ProgramMath.formatMinutes(260))
    assertEquals("0 min", ProgramMath.formatMinutes(0))
  }

  // ── Volume ─────────────────────────────────────────────────────────────────

  @Test
  fun `primary muscle counts fully and secondary at half`() {
    val program = PlanProgram(workouts = listOf(
      PlanWorkout(name = "Push", exercises = listOf(exercise("Bench Press", 4)))
    ))
    val volume = ProgramMath.weeklySetsByMuscle(program)
    assertEquals(4f, volume[MuscleGroup.CHEST]!!, 0.01f)
    // Triceps assist on a bench press, so they bank half credit.
    assertEquals(2f, volume[MuscleGroup.TRICEPS]!!, 0.01f)
  }

  @Test
  fun `warm-up sets do not count toward volume`() {
    val ex = PlanExercise(
      name = "Bench Press",
      sets = listOf(PlanSet(type = SetType.WARMUP, reps = "10")) + workingSets(3)
    )
    val program = PlanProgram(workouts = listOf(PlanWorkout(exercises = listOf(ex))))
    assertEquals(3f, ProgramMath.weeklySetsByMuscle(program)[MuscleGroup.CHEST]!!, 0.01f)
  }

  @Test
  fun `volume accumulates across workouts`() {
    val program = PlanProgram(workouts = listOf(
      PlanWorkout(name = "Push A", exercises = listOf(exercise("Bench Press", 3))),
      PlanWorkout(name = "Push B", exercises = listOf(exercise("Bench Press", 3)))
    ))
    assertEquals(6f, ProgramMath.weeklySetsByMuscle(program)[MuscleGroup.CHEST]!!, 0.01f)
  }

  @Test
  fun `superset volume follows the round count not the typed rows`() {
    val group = PlanGroup(rounds = 5)
    val ex = exercise("Bench Press", 2).copy(supersetGroupId = group.id)
    val w = PlanWorkout(exercises = listOf(ex), groups = listOf(group))
    assertEquals(5, ProgramMath.effectiveSetCount(w, ex))
  }

  @Test
  fun `rest days contribute no volume`() {
    val program = PlanProgram(workouts = listOf(
      PlanWorkout(name = "Rest", kind = WorkoutKind.REST,
        exercises = listOf(exercise("Bench Press", 5)))
    ))
    assertTrue(ProgramMath.weeklySetsByMuscle(program).isEmpty())
  }

  @Test
  fun `unknown exercise names are reported rather than silently zeroed`() {
    val program = PlanProgram(workouts = listOf(
      PlanWorkout(exercises = listOf(exercise("Zercher Widget Pull", 3)))
    ))
    assertTrue(ProgramMath.unrecognisedExercises(program).contains("Zercher Widget Pull"))
  }

  @Test
  fun `ranked volume is ordered biggest first`() {
    val program = PlanProgram(workouts = listOf(
      PlanWorkout(exercises = listOf(exercise("Bench Press", 6), exercise("Barbell Curl", 2)))
    ))
    val ranked = ProgramMath.rankedVolume(program)
    assertTrue(ranked.zipWithNext().all { (a, b) -> a.second >= b.second })
  }

  // ── Rep parsing ────────────────────────────────────────────────────────────

  @Test
  fun `repsForVolume reads the shapes users actually type`() {
    assertEquals(8, ProgramMath.repsForVolume("8"))
    assertEquals(10, ProgramMath.repsForVolume("8-12"))
    assertEquals(0, ProgramMath.repsForVolume(""))
    assertEquals(0, ProgramMath.repsForVolume("AMRAP"))
    assertEquals(0, ProgramMath.repsForVolume("30s"))
  }

  // ── Warnings ───────────────────────────────────────────────────────────────

  @Test
  fun `missing rep targets are warned about but never block`() {
    val ex = PlanExercise(name = "Bench Press", sets = listOf(PlanSet(reps = "")))
    val program = PlanProgram(name = "P", workouts = listOf(PlanWorkout(name = "Push", exercises = listOf(ex))))
    assertTrue(ProgramMath.warnings(program).any { it.contains("no rep target") })
  }

  @Test
  fun `empty workout is warned about by name`() {
    val program = PlanProgram(workouts = listOf(PlanWorkout(name = "Push A")))
    assertTrue(ProgramMath.warnings(program).any { it.contains("Push A") })
  }

  @Test
  fun `a complete program has nothing to warn about`() {
    val program = PlanProgram(
      name = "PPL",
      workouts = listOf(PlanWorkout(name = "Push", exercises = listOf(exercise("Bench Press", 3))))
    )
    assertTrue(ProgramMath.warnings(program).isEmpty())
  }

  @Test
  fun `a program with no training days says so`() {
    val program = PlanProgram(workouts = listOf(PlanWorkout(name = "Rest", kind = WorkoutKind.REST)))
    assertTrue(ProgramMath.warnings(program).any { it.contains("No training days") })
  }

  // ── Summary ────────────────────────────────────────────────────────────────

  @Test
  fun `summary headline counts only training workouts`() {
    val program = PlanProgram(workouts = listOf(
      PlanWorkout(name = "Push", exercises = listOf(exercise("Bench Press", 3))),
      PlanWorkout(name = "Rest", kind = WorkoutKind.REST)
    ))
    val s = ProgramMath.summarise(program)
    assertEquals(1, s.workoutCount)
    assertEquals(1, s.exerciseCount)
    assertTrue(s.headline.startsWith("1 workout · 1 exercise"))
  }

  @Test
  fun `training days per week excludes rest`() {
    val program = PlanProgram(workouts = listOf(
      PlanWorkout(name = "A"), PlanWorkout(name = "Rest", kind = WorkoutKind.REST),
      PlanWorkout(name = "B")
    ))
    assertEquals(2, ProgramMath.trainingDaysPerWeek(program))
  }

  @Test
  fun `equipment is derived from exercise names`() {
    val program = PlanProgram(workouts = listOf(
      PlanWorkout(exercises = listOf(exercise("Barbell Bench Press", 3), exercise("Dumbbell Curl", 3)))
    ))
    val eq = ProgramMath.equipmentUsed(program)
    assertTrue(eq.contains("Barbell"))
    assertTrue(eq.contains("Dumbbell"))
  }

  // ── Stress test (§9: 8 workouts x 12 exercises x 5 sets) ───────────────────

  @Test
  fun `summary of a large program stays correct and fast`() {
    val program = PlanProgram(
      name = "Stress",
      workouts = List(8) { w ->
        PlanWorkout(name = "W$w", exercises = List(12) { e -> exercise("Bench Press", 5) })
      }
    )
    val start = System.nanoTime()
    val s = ProgramMath.summarise(program)
    val millis = (System.nanoTime() - start) / 1_000_000

    assertEquals(8, s.workoutCount)
    assertEquals(96, s.exerciseCount)
    assertTrue("summary should be recomputable per keystroke, took ${millis}ms", millis < 250)
  }

  @Test
  fun `resolvedName falls back so a nameless program can always save`() {
    assertEquals(PlanProgram.DEFAULT_NAME, PlanProgram.resolvedName("   "))
    assertEquals("Push Pull Legs", PlanProgram.resolvedName("  Push Pull Legs "))
  }

  @Test
  fun `an empty exercise has no rep target`() {
    assertTrue(PlanExercise(name = "X", sets = listOf(PlanSet(reps = ""))).hasNoRepTarget)
    assertFalse(PlanExercise(name = "X", sets = listOf(PlanSet(reps = "8"))).hasNoRepTarget)
  }
}
