package com.wildodds.gymtracker.data.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * org.json is an Android framework class, so these run under Robolectric like the rest of the
 * asset-backed tests in this project.
 */
@RunWith(RobolectricTestRunner::class)
class ProgramPlanJsonTest {

  /** A program exercising every field the format carries, so the round trip proves losslessness. */
  private fun fullProgram(): PlanProgram {
    val group = PlanGroup(label = "A", rounds = 4, restBetweenExercisesSeconds = 15, restAfterRoundSeconds = 120)
    return PlanProgram(
      name = "Push Pull Legs",
      description = "Hypertrophy-focused 5-day split",
      goal = ProgramGoal.HYPERTROPHY,
      experience = ExperienceLevel.ADVANCED,
      durationWeeks = 12,
      trackRpe = true,
      trackOneRm = true,
      folder = "Blocks",
      coverImage = "3",
      progression = ProgressionSettings(
        method = ProgressionMethod.DOUBLE, repRangeLow = 6, repRangeHigh = 10,
        loadIncrement = 5f, requiredSuccessfulSets = 4, rirTarget = 1,
        autoRecommend = true, deloadEveryWeeks = 5
      ),
      workouts = listOf(
        PlanWorkout(
          name = "Push A",
          kind = WorkoutKind.TRAINING,
          weekday = 1,
          note = "Start with the press",
          isOptional = false,
          groups = listOf(group),
          exercises = listOf(
            PlanExercise(
              name = "Barbell Bench Press",
              notes = "Pause on the chest",
              alternatives = listOf("Dumbbell Bench Press", "Chest Press Machine"),
              sets = listOf(
                PlanSet(type = SetType.WARMUP, load = "20", reps = "10", restSeconds = 60),
                PlanSet(type = SetType.WORKING, load = "60", reps = "8-12", restSeconds = 150,
                  rpe = "8", tempo = "3-1-1", notes = "keep elbows tucked")
              )
            ),
            PlanExercise(
              name = "Dumbbell Curl",
              supersetGroupId = group.id,
              isOptional = true,
              sets = listOf(PlanSet(type = SetType.FAILURE, reps = "AMRAP"))
            )
          )
        ),
        PlanWorkout(name = "Rest", kind = WorkoutKind.REST),
        PlanWorkout(name = "Deload", kind = WorkoutKind.DELOAD, weekday = 6)
      )
    )
  }

  /** Compare everything except the regenerated internal ids. */
  private fun assertSameContent(a: PlanProgram, b: PlanProgram) {
    assertEquals(a.name, b.name)
    assertEquals(a.description, b.description)
    assertEquals(a.goal, b.goal)
    assertEquals(a.experience, b.experience)
    assertEquals(a.durationWeeks, b.durationWeeks)
    assertEquals(a.trackRpe, b.trackRpe)
    assertEquals(a.trackOneRm, b.trackOneRm)
    assertEquals(a.folder, b.folder)
    assertEquals(a.coverImage, b.coverImage)
    assertEquals(a.progression, b.progression)
    assertEquals(a.workouts.size, b.workouts.size)

    a.workouts.zip(b.workouts).forEach { (wa, wb) ->
      assertEquals(wa.name, wb.name)
      assertEquals(wa.kind, wb.kind)
      assertEquals(wa.weekday, wb.weekday)
      assertEquals(wa.note, wb.note)
      assertEquals(wa.isOptional, wb.isOptional)
      assertEquals(wa.groups.size, wb.groups.size)
      wa.groups.zip(wb.groups).forEach { (ga, gb) ->
        assertEquals(ga.label, gb.label)
        assertEquals(ga.rounds, gb.rounds)
        assertEquals(ga.restBetweenExercisesSeconds, gb.restBetweenExercisesSeconds)
        assertEquals(ga.restAfterRoundSeconds, gb.restAfterRoundSeconds)
      }
      assertEquals(wa.exercises.size, wb.exercises.size)
      wa.exercises.zip(wb.exercises).forEach { (ea, eb) ->
        assertEquals(ea.name, eb.name)
        assertEquals(ea.notes, eb.notes)
        assertEquals(ea.isOptional, eb.isOptional)
        assertEquals(ea.alternatives, eb.alternatives)
        assertEquals(ea.sets, eb.sets.map { it.copy(id = "") }.zip(ea.sets).map { (nb, na) -> nb.copy(id = na.id) })
      }
    }
  }

  @Test
  fun `round trip preserves every field`() {
    val original = fullProgram()
    val result = ProgramPlanJson.decode(ProgramPlanJson.encode(original))
    assertTrue("decode failed: $result", result is ProgramPlanJson.Result.Ok)
    assertSameContent(original, (result as ProgramPlanJson.Result.Ok).program)
  }

  @Test
  fun `superset membership survives the round trip`() {
    val decoded = (ProgramPlanJson.decode(ProgramPlanJson.encode(fullProgram()))
      as ProgramPlanJson.Result.Ok).program
    val push = decoded.workouts.first()
    val curl = push.exercises.first { it.name == "Dumbbell Curl" }

    // The id itself is regenerated, but it must still point at a real group in the same workout.
    assertTrue(curl.supersetGroupId != null)
    assertTrue(push.groups.any { it.id == curl.supersetGroupId })
    assertEquals(4, push.groups.first { it.id == curl.supersetGroupId }.rounds)
  }

  @Test
  fun `encoded json is indented for a human to read and edit`() {
    val json = ProgramPlanJson.encode(fullProgram())
    assertTrue(json.contains("\n"))
    assertTrue(json.contains("\"schemaVersion\""))
    assertTrue(json.contains("\"kind\": \"wildodds.program\""))
  }

  @Test
  fun `garbage is rejected with a readable reason rather than throwing`() {
    val r = ProgramPlanJson.decode("not json at all {{{")
    assertTrue(r is ProgramPlanJson.Result.Invalid)
    assertTrue((r as ProgramPlanJson.Result.Invalid).reason.contains("valid JSON"))
  }

  @Test
  fun `a newer schema version is refused with an explanation`() {
    val json = """{"kind":"wildodds.program","schemaVersion":99,"workouts":[{"name":"A"}]}"""
    val r = ProgramPlanJson.decode(json)
    assertTrue(r is ProgramPlanJson.Result.Invalid)
    assertTrue((r as ProgramPlanJson.Result.Invalid).reason.contains("newer version"))
  }

  @Test
  fun `some other apps json is refused`() {
    val r = ProgramPlanJson.decode("""{"kind":"someone.else","workouts":[]}""")
    assertTrue(r is ProgramPlanJson.Result.Invalid)
  }

  @Test
  fun `a program with no workouts is refused`() {
    val r = ProgramPlanJson.decode("""{"kind":"wildodds.program","schemaVersion":1,"workouts":[]}""")
    assertTrue(r is ProgramPlanJson.Result.Invalid)
  }

  @Test
  fun `unknown enum values fall back and are reported rather than failing the import`() {
    val json = """
      {"kind":"wildodds.program","schemaVersion":1,"name":"P","goal":"TIME_TRAVEL",
       "workouts":[{"name":"A","kind":"TRAINING","exercises":[]}]}
    """.trimIndent()
    val r = ProgramPlanJson.decode(json)
    assertTrue(r is ProgramPlanJson.Result.Ok)
    r as ProgramPlanJson.Result.Ok
    assertEquals(ProgramGoal.HYPERTROPHY, r.program.goal)
    assertTrue(r.notes.any { it.contains("TIME_TRAVEL") })
  }

  @Test
  fun `a file with no version stamp still imports and says so`() {
    val json = """{"kind":"wildodds.program","name":"P","workouts":[{"name":"A"}]}"""
    val r = ProgramPlanJson.decode(json)
    assertTrue(r is ProgramPlanJson.Result.Ok)
    assertTrue((r as ProgramPlanJson.Result.Ok).notes.any { it.contains("version") })
  }

  @Test
  fun `a nameless imported program gets the default name`() {
    val json = """{"kind":"wildodds.program","schemaVersion":1,"workouts":[{"name":"A"}]}"""
    val r = ProgramPlanJson.decode(json) as ProgramPlanJson.Result.Ok
    assertEquals(PlanProgram.DEFAULT_NAME, r.program.name)
  }

  @Test
  fun `export filename is a safe readable slug`() {
    assertEquals("push-pull-legs.json",
      ProgramPlanJson.fileNameFor(PlanProgram(name = "Push / Pull / Legs!")))
    // A blank name resolves to the default program name first, so the file is named after that.
    assertEquals("my-program.json", ProgramPlanJson.fileNameFor(PlanProgram(name = "   ")))
  }
}
