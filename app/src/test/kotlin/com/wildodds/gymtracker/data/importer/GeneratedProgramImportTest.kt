package com.wildodds.gymtracker.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Import fixtures written the way an assistant actually answers "write me a program": markdown
 * headings, bold day names, numbered or bulleted exercise lists, en-dashes, "4 sets × 5 reps"
 * long-form notation, percentages, and prose notes mixed in among the exercises.
 *
 * This is the format most people will paste, because most people will have asked a chatbot. If the
 * importer only copes with terse `3x8` gym-notation it fails on the common case.
 */
class GeneratedProgramImportTest {

  private fun parse(raw: String) = UniversalProgramImporter.import("Test", raw)

  private fun sessionLabels(raw: String) =
    parse(raw).draft.weeks.flatMap { it.sessions }.map { it.label }

  private fun exerciseNames(raw: String) =
    parse(raw).draft.weeks.flatMap { w -> w.sessions.flatMap { s -> s.slots.map { it.exercise.original } } }

  private fun slots(raw: String) =
    parse(raw).draft.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }

  // ── Program 1 ──────────────────────────────────────────────────────────────
  // "Create a gym program for squats and overhead press for 3x per week sessions"

  private val squatPress = """
    # Squat & Overhead Press — 3 Days per Week

    A simple strength block built around the back squat and overhead press.

    ## Day 1 – Heavy Squat

    1. Back Squat — 4 sets × 5 reps @ 80% 1RM
    2. Overhead Press — 3 sets × 8 reps @ 70%
    3. Romanian Deadlift — 3 × 10
    4. Plank — 3 × 45s

    Rest 2-3 minutes between working sets.

    ## Day 2 – Press Focus

    1. Overhead Press — 5 × 5 @ 75%
    2. Front Squat — 3 × 8
    3. Barbell Row — 4 × 10
    4. Face Pull — 3 × 15

    ## Day 3 – Volume Squat

    1. Back Squat — 5 × 8 @ 65%
    2. Push Press — 4 × 6
    3. Bulgarian Split Squat — 3 × 12
    4. Hanging Leg Raise — 3 × 15
  """.trimIndent()

  @Test
  fun `markdown day headings become sessions with their real names`() {
    val labels = sessionLabels(squatPress)
    assertEquals("expected 3 sessions, got $labels", 3, labels.size)
    assertTrue("lost the day name: $labels", labels[0].contains("Heavy Squat"))
    assertTrue("lost the day name: $labels", labels[1].contains("Press Focus"))
    assertTrue("lost the day name: $labels", labels[2].contains("Volume Squat"))
  }

  @Test
  fun `markdown headings and list numbering are stripped from exercise names`() {
    val names = exerciseNames(squatPress)
    assertTrue("markdown leaked into names: $names",
      names.none { it.contains("#") || it.contains("*") || it.startsWith("1.") })
    assertTrue("expected Back Squat in $names", names.any { it.equals("Back Squat", true) })
    assertTrue("expected Overhead Press in $names", names.any { it.equals("Overhead Press", true) })
  }

  @Test
  fun `long-form 4 sets x 5 reps is understood`() {
    val squat = slots(squatPress).first { it.exercise.original.equals("Back Squat", true) }
    assertEquals(4, squat.sets.first().count)
    assertEquals(5, squat.sets.first().reps?.min)
  }

  @Test
  fun `percentages survive`() {
    val squat = slots(squatPress).first { it.exercise.original.equals("Back Squat", true) }
    assertEquals(LoadKind.PCT_1RM, squat.sets.first().load?.kind)
    assertEquals(80.0, squat.sets.first().load?.value!!, 0.01)
  }

  @Test
  fun `prose lines are not mistaken for exercises`() {
    val names = exerciseNames(squatPress)
    assertTrue("prose became an exercise: $names",
      names.none { it.contains("Rest 2-3 minutes", true) || it.contains("simple strength block", true) })
  }

  @Test
  fun `timed work keeps its unit`() {
    val plank = slots(squatPress).first { it.exercise.original.contains("Plank", true) }
    assertEquals(3, plank.sets.first().count)
    assertEquals(RepKind.TIME_SEC, plank.sets.first().reps?.kind)
  }

  // ── Program 2 ──────────────────────────────────────────────────────────────
  // "Give me an upper/lower split, 4 days a week, with RPE"

  private val upperLower = """
    **Upper/Lower Split — 4 Days**

    ### Monday — Upper Body (Heavy)
    * Barbell Bench Press: 4x6 @ RPE 8
    * Weighted Pull-up: 4x6
    * Overhead Press: 3x8 @ RPE 7
    * Barbell Row: 3x8
    * Barbell Curl: 3x12

    ### Tuesday — Lower Body (Heavy)
    * Back Squat: 4x5 @ RPE 8
    * Romanian Deadlift: 3x8
    * Leg Press: 3x12
    * Calf Raise: 4x15

    ### Thursday — Upper Body (Volume)
    * Incline Dumbbell Press: 4x10
    * Lat Pulldown: 4x12
    * Lateral Raise: 3x15
    * Rope Pushdown: 3x12

    ### Friday — Lower Body (Volume)
    * Front Squat: 4x8
    * Leg Curl: 3x12
    * Walking Lunge: 3x20
    * Plank: 3x60s
  """.trimIndent()

  @Test
  fun `weekday headings with a descriptive suffix keep the whole name`() {
    val labels = sessionLabels(upperLower)
    assertEquals("expected 4 sessions, got $labels", 4, labels.size)
    // "Monday — Upper Body (Heavy)" must not collapse to bare "Monday": four sessions all called
    // Monday/Tuesday/Thursday/Friday tells the user nothing about what they are training.
    assertTrue("day name lost its content: $labels", labels[0].contains("Upper", true))
    assertTrue("day name lost its content: $labels", labels[1].contains("Lower", true))
  }

  @Test
  fun `bulleted exercises with colons parse cleanly`() {
    val names = exerciseNames(upperLower)
    assertTrue("bullet leaked: $names", names.none { it.startsWith("*") || it.startsWith("-") })
    assertTrue("colon leaked: $names", names.none { it.endsWith(":") })
    assertTrue(names.any { it.equals("Barbell Bench Press", true) })
  }

  @Test
  fun `RPE survives`() {
    val bench = slots(upperLower).first { it.exercise.original.equals("Barbell Bench Press", true) }
    assertEquals(LoadKind.RPE, bench.sets.first().load?.kind)
    assertEquals(8.0, bench.sets.first().load?.value!!, 0.01)
    assertEquals(4, bench.sets.first().count)
    assertEquals(6, bench.sets.first().reps?.min)
  }

  @Test
  fun `all four sessions keep all their exercises`() {
    val sessions = parse(upperLower).draft.weeks.flatMap { it.sessions }
    assertEquals(listOf(5, 4, 4, 4), sessions.map { it.slots.size })
  }

  // ── Program 3 ──────────────────────────────────────────────────────────────
  // "5/3/1 style, 4 weeks, percentages of training max" — multi-week + a table

  private val fiveThreeOne = """
    # 4-Week Strength Block

    ## Week 1

    **Day 1 — Squat**
    | Exercise | Sets | Reps | Intensity |
    |---|---|---|---|
    | Back Squat | 3 | 5 | 65%, 75%, 85% TM |
    | Leg Press | 3 | 10 | — |
    | Ab Wheel | 3 | 12 | — |

    **Day 2 — Bench**
    - Barbell Bench Press — 3x5 @ 85% TM
    - Barbell Row — 4x8
    - Tricep Pushdown — 3x15

    ## Week 2

    **Day 1 — Squat**
    - Back Squat — 3x3 @ 90% TM
    - Leg Press — 3x10
    - Ab Wheel — 3x12

    **Day 2 — Bench**
    - Barbell Bench Press — 3x3 @ 90% TM
    - Barbell Row — 4x8
    - Tricep Pushdown — 3x15
  """.trimIndent()

  @Test
  fun `multi-week programs keep their weeks`() {
    val weeks = parse(fiveThreeOne).draft.weeks
    assertEquals("expected 2 weeks, got ${weeks.map { it.index }}", 2, weeks.size)
    assertEquals(2, weeks[0].sessions.size)
    assertEquals(2, weeks[1].sessions.size)
  }

  @Test
  fun `markdown tables are read as exercises`() {
    val week1 = parse(fiveThreeOne).draft.weeks.first()
    val names = week1.sessions.flatMap { s -> s.slots.map { it.exercise.original } }
    assertTrue("table rows dropped: $names", names.any { it.equals("Back Squat", true) })
    assertTrue("table rows dropped: $names", names.any { it.equals("Leg Press", true) })
    // The header row and the |---| separator must never become exercises.
    assertTrue("table chrome became an exercise: $names",
      names.none { it.contains("Exercise", true) || it.contains("---") || it.contains("Sets", true) })
  }

  @Test
  fun `table sets and reps columns are read`() {
    val squat = slots(fiveThreeOne).first { it.exercise.original.equals("Back Squat", true) }
    assertEquals(3, squat.sets.first().count)
    assertEquals(5, squat.sets.first().reps?.min)
  }

  @Test
  fun `percent of training max is distinguished from percent of 1RM`() {
    val bench = slots(fiveThreeOne).first { it.exercise.original.equals("Barbell Bench Press", true) }
    assertEquals(LoadKind.PCT_TM, bench.sets.first().load?.kind)
  }

  @Test
  fun `bold day headings inside a week become sessions`() {
    val labels = parse(fiveThreeOne).draft.weeks.first().sessions.map { it.label }
    assertTrue("bold markers leaked: $labels", labels.none { it.contains("*") })
    assertTrue("day name lost: $labels", labels.any { it.contains("Squat", true) })
    assertTrue("day name lost: $labels", labels.any { it.contains("Bench", true) })
  }

  // ── Confidence ─────────────────────────────────────────────────────────────

  @Test
  fun `a cleanly generated program imports with high confidence and no unresolved slots`() {
    listOf(squatPress, upperLower, fiveThreeOne).forEachIndexed { i, program ->
      val result = parse(program)
      assertTrue(
        "program ${i + 1} parsed at only ${result.overallConfidence}",
        result.overallConfidence >= 0.7
      )
      val unknown = result.draft.weeks
        .flatMap { w -> w.sessions.flatMap { it.slots } }
        .filter { it.sets.firstOrNull()?.kind == SetKind.UNKNOWN }
      assertTrue("program ${i + 1} left unparsed slots: ${unknown.map { it.rawText }}", unknown.isEmpty())
    }
  }
}
