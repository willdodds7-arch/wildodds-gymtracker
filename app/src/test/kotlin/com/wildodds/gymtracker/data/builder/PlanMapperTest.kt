package com.wildodds.gymtracker.data.builder

import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.PlannedSet
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plan ↔ storage mapping. The important guarantees here are that a plan week gets materialised
 * across every week (matching how the rest of the app reads programs), and that the legacy
 * `sets`/`repsTarget` columns stay correct so nothing else in the app changes behaviour.
 */
class PlanMapperTest {

  private fun working(n: Int, reps: String = "8-12", rest: Int = 90) =
    List(n) { PlanSet(type = SetType.WORKING, reps = reps, restSeconds = rest) }

  private fun plan(vararg workouts: PlanWorkout, weeks: Int = 4) =
    PlanProgram(name = "Test", durationWeeks = weeks, workouts = workouts.toList())

  @Test
  fun `one plan week is materialised across every week`() {
    val p = plan(
      PlanWorkout(name = "Push", exercises = listOf(PlanExercise(name = "Bench Press", sets = working(3)))),
      PlanWorkout(name = "Pull", exercises = listOf(PlanExercise(name = "Barbell Row", sets = working(3)))),
      weeks = 4
    )
    val parsed = PlanMapper.toParsed(p)
    assertEquals(4, parsed.totalWeeks)
    assertEquals(8, parsed.sessions.size)
    assertEquals(setOf(1, 2, 3, 4), parsed.sessions.map { it.weekNumber }.toSet())
  }

  @Test
  fun `an ongoing program still materialises a finite number of weeks`() {
    val p = plan(PlanWorkout(name = "A", exercises = listOf(PlanExercise(name = "Squat", sets = working(3)))), weeks = 0)
    val parsed = PlanMapper.toParsed(p)
    assertEquals(PlanMapper.ONGOING_WEEKS, parsed.totalWeeks)
  }

  @Test
  fun `rest days hold their slot so later days keep their position`() {
    val p = plan(
      PlanWorkout(name = "Push", exercises = listOf(PlanExercise(name = "Bench Press", sets = working(3)))),
      PlanWorkout(name = "Rest", kind = WorkoutKind.REST),
      PlanWorkout(name = "Pull", exercises = listOf(PlanExercise(name = "Barbell Row", sets = working(3)))),
      weeks = 1
    )
    val days = PlanMapper.toParsed(p).sessions.map { it.dayNumber }.sorted()
    // Push is day 1 and Pull is day 3 — the rest day is not written, but it isn't collapsed either.
    assertEquals(listOf(1, 3), days)
  }

  @Test
  fun `rest days are not written as sessions`() {
    val p = plan(PlanWorkout(name = "Rest", kind = WorkoutKind.REST), weeks = 2)
    assertTrue(PlanMapper.toParsed(p).sessions.isEmpty())
  }

  @Test
  fun `legacy set count is the working set count, ignoring warm-ups`() {
    val ex = PlanExercise(
      name = "Bench Press",
      sets = listOf(PlanSet(type = SetType.WARMUP, reps = "10")) + working(4)
    )
    val parsed = PlanMapper.toParsed(plan(PlanWorkout(name = "Push", exercises = listOf(ex)), weeks = 1))
    assertEquals(4, parsed.sessions.first().exercises.first().sets)
  }

  @Test
  fun `legacy rep target is the most common working target`() {
    val ex = PlanExercise(name = "Bench Press", sets = listOf(
      PlanSet(type = SetType.WORKING, reps = "5"),
      PlanSet(type = SetType.WORKING, reps = "8-12"),
      PlanSet(type = SetType.WORKING, reps = "8-12")
    ))
    assertEquals("8-12", PlanMapper.dominantRepTarget(ex))
  }

  @Test
  fun `a nameless program still saves under the default name`() {
    assertEquals(PlanProgram.DEFAULT_NAME, PlanMapper.toParsed(PlanProgram()).name)
  }

  @Test
  fun `muscle summary is derived so the user never types it`() {
    val w = PlanWorkout(name = "Push", exercises = listOf(
      PlanExercise(name = "Bench Press", sets = working(3)),
      PlanExercise(name = "Overhead Press", sets = working(3))
    ))
    assertTrue(PlanMapper.muscleSummary(w).contains("Chest"))
  }

  // ── Per-set detail is only stored when it says something new ───────────────

  @Test
  fun `a uniform exercise needs no per-set rows`() {
    val ex = PlanExercise(name = "Bench Press", sets = working(3))
    assertFalse(PlanMapper.hasPerSetDetail(ex))
    assertTrue(PlanMapper.plannedSetsFor(1L, ex).isEmpty())
  }

  @Test
  fun `a warm-up makes an exercise worth storing per set`() {
    val ex = PlanExercise(
      name = "Bench Press",
      sets = listOf(PlanSet(type = SetType.WARMUP, reps = "10")) + working(3)
    )
    assertTrue(PlanMapper.hasPerSetDetail(ex))
    assertEquals(4, PlanMapper.plannedSetsFor(1L, ex).size)
  }

  @Test
  fun `differing loads or rests make an exercise worth storing per set`() {
    val differentLoad = PlanExercise(name = "Squat", sets = listOf(
      PlanSet(reps = "8", load = "60"), PlanSet(reps = "8", load = "70")
    ))
    assertTrue(PlanMapper.hasPerSetDetail(differentLoad))

    val differentRest = PlanExercise(name = "Squat", sets = listOf(
      PlanSet(reps = "8", restSeconds = 90), PlanSet(reps = "8", restSeconds = 180)
    ))
    assertTrue(PlanMapper.hasPerSetDetail(differentRest))
  }

  @Test
  fun `planned set numbers are 1-based and in order`() {
    val ex = PlanExercise(name = "Bench Press", sets = listOf(
      PlanSet(type = SetType.WARMUP, reps = "10"), PlanSet(reps = "8"), PlanSet(reps = "8")
    ))
    assertEquals(listOf(1, 2, 3), PlanMapper.plannedSetsFor(7L, ex).map { it.setNumber })
    assertTrue(PlanMapper.plannedSetsFor(7L, ex).all { it.exerciseId == 7L })
  }

  // ── Storage → plan ────────────────────────────────────────────────────────

  @Test
  fun `a legacy program with no planned sets expands into uniform working sets`() {
    val program = Program(id = 1, name = "Old", totalWeeks = 4)
    val session = Session(id = 10, programId = 1, weekNumber = 1, dayNumber = 1,
      name = "Push", muscleGroups = "Chest")
    val exercise = Exercise(id = 100, sessionId = 10, name = "Bench Press",
      sets = 3, repsTarget = "8-12", orderIndex = 0)

    val plan = PlanMapper.fromStored(program, listOf(session), mapOf(10L to listOf(exercise)))
    val ex = plan.workouts.single().exercises.single()

    assertEquals(3, ex.sets.size)
    assertTrue(ex.sets.all { it.reps == "8-12" && it.type == SetType.WORKING })
  }

  @Test
  fun `planned sets win over the legacy columns when both exist`() {
    val program = Program(id = 1, name = "New", totalWeeks = 4)
    val session = Session(id = 10, programId = 1, weekNumber = 1, dayNumber = 1, name = "Push", muscleGroups = "")
    val exercise = Exercise(id = 100, sessionId = 10, name = "Bench Press",
      sets = 3, repsTarget = "8-12", orderIndex = 0)
    val planned = listOf(
      PlannedSet(exerciseId = 100, setNumber = 1, type = "WARMUP", load = "20", reps = "10", restSeconds = 60),
      PlannedSet(exerciseId = 100, setNumber = 2, type = "WORKING", load = "60", reps = "8", restSeconds = 150)
    )

    val plan = PlanMapper.fromStored(program, listOf(session), mapOf(10L to listOf(exercise)),
      mapOf(100L to planned))
    val ex = plan.workouts.single().exercises.single()

    assertEquals(2, ex.sets.size)
    assertEquals(SetType.WARMUP, ex.sets[0].type)
    assertEquals("20", ex.sets[0].load)
    assertEquals(150, ex.sets[1].restSeconds)
  }

  @Test
  fun `only week 1 becomes the editable definition`() {
    val program = Program(id = 1, name = "P", totalWeeks = 4)
    val week1 = listOf(
      Session(id = 10, programId = 1, weekNumber = 1, dayNumber = 1, name = "Push", muscleGroups = ""),
      Session(id = 11, programId = 1, weekNumber = 1, dayNumber = 2, name = "Pull", muscleGroups = "")
    )
    val plan = PlanMapper.fromStored(program, week1, emptyMap())
    assertEquals(2, plan.workouts.size)
    assertEquals(listOf("Push", "Pull"), plan.workouts.map { it.name })
  }

  @Test
  fun `exercises come back in their stored order`() {
    val program = Program(id = 1, name = "P", totalWeeks = 1)
    val session = Session(id = 10, programId = 1, weekNumber = 1, dayNumber = 1, name = "Push", muscleGroups = "")
    val exercises = listOf(
      Exercise(id = 3, sessionId = 10, name = "Third", sets = 3, repsTarget = "8", orderIndex = 2),
      Exercise(id = 1, sessionId = 10, name = "First", sets = 3, repsTarget = "8", orderIndex = 0),
      Exercise(id = 2, sessionId = 10, name = "Second", sets = 3, repsTarget = "8", orderIndex = 1)
    )
    val plan = PlanMapper.fromStored(program, listOf(session), mapOf(10L to exercises))
    assertEquals(listOf("First", "Second", "Third"), plan.workouts.single().exercises.map { it.name })
  }

  @Test
  fun `a large program maps without losing anything`() {
    val p = plan(
      *(1..8).map { w ->
        PlanWorkout(
          name = "W$w",
          exercises = (1..12).map { PlanExercise(name = "Bench Press", sets = working(5)) }
        )
      }.toTypedArray(),
      weeks = 4
    )
    val parsed = PlanMapper.toParsed(p)
    assertEquals(8 * 4, parsed.sessions.size)
    assertEquals(12, parsed.sessions.first().exercises.size)
  }
}
