package com.wildodds.gymtracker.data.importer.segment

import com.wildodds.gymtracker.data.importer.SessionType
import com.wildodds.gymtracker.data.importer.TextProgramImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The structure detector, against the document shapes the Phase 4b list names.
 *
 * These are the cases a greedy first-keyword-wins loop cannot get right, because each one needs
 * evidence from a line other than the one being decided: a title that starts with a split keyword,
 * a rest day written as a day, a week header that covers four weeks, a session header that looks
 * exactly like an exercise.
 */
class SegmenterTest {

  private fun roles(raw: String): List<Pair<LineRole, String>> =
    TextProgramImporter.segment(raw).lines.map { it.role to it.features.text }

  private fun draft(raw: String) = TextProgramImporter.import("", raw)

  private fun sessions(raw: String) = draft(raw).weeks.flatMap { it.sessions }

  // ── weeks ────────────────────────────────────────────────────────────────────

  @Test fun `a week range covers every week in it`() {
    val d = draft("""
      Weeks 1-4
      Day 1 - Squat
      Back Squat 3x5
    """.trimIndent())
    assertEquals(listOf(1, 2, 3, 4), d.weeks.map { it.index })
    assertTrue("every week gets the session", d.weeks.all { it.sessions.size == 1 })
  }

  @Test fun `a week list covers exactly the weeks it names`() {
    val d = draft("""
      Weeks 1,3,5
      Day 1
      Back Squat 3x5
    """.trimIndent())
    assertEquals(listOf(1, 3, 5), d.weeks.map { it.index })
  }

  @Test fun `W1D3 names a week and a day at once`() {
    val d = draft("""
      W1D3
      Back Squat 3x5
    """.trimIndent())
    assertEquals(listOf(1), d.weeks.map { it.index })
    assertEquals(3, d.weeks.single().sessions.single().dayNumber)
  }

  @Test fun `a phase header sits above weeks without becoming one`() {
    val r = roles("""
      Block A
      Week 1
      Day 1
      Back Squat 3x5
    """.trimIndent())
    assertEquals(LineRole.PHASE_HEADER, r[0].first)
    assertEquals(LineRole.WEEK_HEADER, r[1].first)
  }

  @Test fun `a deload week is marked by its keyword`() {
    val d = draft("""
      Week 4 - Deload
      Day 1
      Back Squat 3x5 @50%
    """.trimIndent())
    assertTrue(d.weeks.single().isDeload)
  }

  // ── sessions ─────────────────────────────────────────────────────────────────

  @Test fun `a weekday session takes its descriptive tail as the name and keeps the weekday`() {
    // Four sessions called Mon/Tue/Thu/Fri tell the user nothing about what they are training.
    val s = sessions("""
      Monday - Upper Body (Heavy)
      Bench Press 3x5
    """.trimIndent()).single()
    assertEquals("Upper Body (Heavy)", s.label)
    assertEquals("monday", s.weekdayHint)
  }

  @Test fun `Day A and Day B are sessions`() {
    val s = sessions("""
      Day A
      Bench Press 3x5
      Day B
      Back Squat 3x5
    """.trimIndent())
    assertEquals(2, s.size)
    assertEquals(listOf(1, 2), s.map { it.dayNumber })
  }

  @Test fun `Session 1 with a parenthesised focus keeps the focus`() {
    val s = sessions("""
      Session 1 (Push)
      Bench Press 3x5
    """.trimIndent()).single()
    assertTrue("label was ${s.label}", s.label.contains("Push") || s.label.contains("1"))
  }

  @Test fun `an exercise line is never swallowed by a session keyword`() {
    // "Pull Up 3x10" must not read as a "Pull" day plus an exercise called "Up".
    val d = draft("""
      Day 1
      Pull Up 3x10
      Push Press 3x5
      Leg Press 3x12
    """.trimIndent())
    val s = d.weeks.single().sessions.single()
    assertEquals(listOf("Pull Up", "Push Press", "Leg Press"), s.slots.map { it.exercise.original })
  }

  @Test fun `a document title that starts with a split keyword stays a title`() {
    val r = roles("""
      Squat & Overhead Press - 3 Days per Week

      Day 1 - Heavy Squat
      Back Squat 3x5
    """.trimIndent())
    assertEquals(LineRole.PROGRAM_TITLE, r.first { it.second.isNotBlank() }.first)
    assertEquals("only the real day is a session", 1, sessions("""
      Squat & Overhead Press - 3 Days per Week

      Day 1 - Heavy Squat
      Back Squat 3x5
    """.trimIndent()).size)
  }

  // ── rest days ────────────────────────────────────────────────────────────────

  @Test fun `Wednesday off is a rest day, not a missing day`() {
    val s = sessions("""
      Monday - Push
      Bench Press 3x8
      Tuesday - Pull
      Barbell Row 3x8
      Wednesday - REST
      Thursday - Legs
      Back Squat 3x5
    """.trimIndent())
    assertEquals(4, s.size)
    assertEquals(
      listOf(SessionType.TRAINING, SessionType.TRAINING, SessionType.REST, SessionType.TRAINING),
      s.map { it.type })
    assertEquals("the rest day holds day 3", listOf(1, 2, 3, 4), s.map { it.dayNumber })
  }

  @Test fun `active recovery is its own session type`() {
    val s = sessions("""
      Sunday - Active Recovery
      Monday - Push
      Bench Press 3x8
    """.trimIndent())
    assertEquals(SessionType.ACTIVE_RECOVERY, s.first().type)
  }

  @Test fun `a rest day has no exercises and that is not an error`() {
    val s = sessions("""
      Day 1 - Push
      Bench Press 3x8
      Day 2 - Rest
    """.trimIndent())
    assertTrue(s.last().slots.isEmpty())
    assertEquals(SessionType.REST, s.last().type)
  }

  // ── sections and notes ───────────────────────────────────────────────────────

  @Test fun `section labels are recorded on their exercises, not turned into sessions`() {
    val d = draft("""
      Day 1
      Warm-up
      Bike 5 min
      Main
      Back Squat 3x5
      Accessories
      Leg Press 3x12
    """.trimIndent())
    val s = d.weeks.single().sessions.single()
    assertEquals("no phantom sessions", 1, d.weeks.single().sessions.size)
    assertEquals(3, s.slots.size)
    assertTrue("warmup section lost", s.slots[0].notes.contains("Warmup", true))
    assertTrue("accessory section lost", s.slots[2].notes.contains("Accessory", true))
  }

  @Test fun `explanatory prose is dropped rather than becoming an exercise`() {
    val d = draft("""
      Day 1
      Back Squat 3x5
      Rest 2-3 minutes between working sets.
      Bench Press 3x8
    """.trimIndent())
    val names = d.weeks.single().sessions.single().slots.map { it.exercise.original }
    assertEquals(listOf("Back Squat", "Bench Press"), names)
  }

  @Test fun `a note before any session becomes a program note, not an exercise`() {
    val d = draft("""
      This block focuses on building the squat through higher frequency.
      Day 1
      Back Squat 3x5
    """.trimIndent())
    assertTrue(d.notes.any { it.contains("higher frequency") })
    assertEquals(1, d.weeks.single().sessions.single().slots.size)
  }

  @Test fun `an indented set detail attaches to the exercise above it`() {
    val d = draft("""
      Day 1
      Back Squat
        set 1: 5 @ 70%
        set 2: 5 @ 80%
    """.trimIndent())
    val slot = d.weeks.single().sessions.single().slots.single()
    assertTrue("detail lost: ${slot.notes}", slot.notes.contains("70%"))
    assertTrue(slot.notes.contains("80%"))
  }

  // ── A/B alternating and rolling programs ─────────────────────────────────────

  @Test fun `an A B alternating template reads as two sessions`() {
    val s = sessions("""
      Workout A
      Back Squat 3x5
      Bench Press 3x5
      Workout B
      Deadlift 1x5
      Overhead Press 3x5
    """.trimIndent())
    assertEquals(2, s.size)
    assertEquals(listOf(2, 2), s.map { it.slots.size })
  }

  @Test fun `an undated rolling program with no week header is one week`() {
    val d = draft("""
      Push
      Bench Press 3x8
      Pull
      Barbell Row 3x8
      Legs
      Back Squat 3x5
    """.trimIndent())
    assertEquals(1, d.weeks.size)
    assertEquals(3, d.weeks.single().sessions.size)
  }

  // ── determinism ──────────────────────────────────────────────────────────────

  @Test fun `the same document segments identically every time`() {
    val doc = """
      4-Week Strength Block
      Week 1
      Day 1 - Squat
      Back Squat 3x5 @ 80%
      Leg Press 3x10
      Week 2
      Day 1 - Squat
      Back Squat 3x3 @ 90%
    """.trimIndent()
    val first = roles(doc)
    repeat(5) { assertEquals(first, roles(doc)) }
  }

  @Test fun `segmentation does not depend on the device locale`() {
    val doc = "Week 1\nDay 1 - Incline Press\nIncline DB Press 3x10\n"
    val defaults = java.util.Locale.getDefault()
    val english = roles(doc)
    try {
      java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
      assertEquals(english, roles(doc))
    } finally {
      java.util.Locale.setDefault(defaults)
    }
  }

  @Test fun `an empty document produces an empty program rather than throwing`() {
    val d = draft("")
    assertEquals(1, d.weeks.size)
    assertTrue(d.weeks.single().sessions.isEmpty())
  }

  @Test fun `a shopping list produces no exercises`() {
    val d = draft("""
      Shopping list
      - eggs
      - milk
      - oats
      - chicken thighs
    """.trimIndent())
    val slots = d.weeks.flatMap { w -> w.sessions.flatMap { it.slots } }
    assertTrue("a shopping list is not a program: ${slots.map { it.exercise.original }}",
      slots.none { it.sets.firstOrNull()?.count != null })
  }
}
