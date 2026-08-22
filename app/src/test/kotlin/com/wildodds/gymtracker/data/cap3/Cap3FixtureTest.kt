package com.wildodds.gymtracker.data.cap3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The master CAP3 test: the published 3-week validation fixture, day by day.
 *
 * Example training maxes — Squat 180, Bench 90, Deadlift 270, every secondary slot 90, kg (step
 * 2.5). Entering these must reproduce every number below exactly, which is what pins the per-set
 * FLOOR/CEILING choices in [Cap3Schemes] and the inverse-Epley tiers in [Cap3Engine].
 *
 * The fixture was published with rest days interleaved (its days 5, 10 and 15). CAP3 no longer
 * schedules rest, so days are addressed here by week and slot; the prescriptions are unchanged, and
 * each test names the fixture day it came from.
 */
class Cap3FixtureTest {

  private val config = fixtureConfig()

  // ── Week 1 (fixture ME = Deadlift) ──────────────────────────────────────────

  @Test fun week1_benchA() = assertDay(                                   // fixture day 1
    week = 1, slot = Cap3DaySlot.BENCH_A, title = "Chest & Biceps",
    lift1 = reps(4, 65.0, 65.0, 65.0, 65.0, 70.0, 70.0) + amrap(4, 72.5),
    top = 65.0 to 10, mrs = 60.0 to 8, emomFail = 5
  )

  @Test fun week1_deadliftA() = assertDay(                                // fixture day 2
    week = 1, slot = Cap3DaySlot.DEADLIFT_A, title = "Back, Abs & Triceps",
    lift1 = reps(5, 195.0, 195.0, 195.0, 195.0) +
            reps(4, 202.5, 202.5, 202.5, 202.5) + amrap(4, 210.0),
    top = 72.5 to 6, mrs = 67.5 to 4, emomFail = 3
  )

  @Test fun week1_squatA() = assertDay(                                   // fixture day 3
    week = 1, slot = Cap3DaySlot.SQUAT_A, title = "Shoulders & Legs",
    lift1 = reps(3, 140.0, 140.0, 140.0, 140.0, 147.5, 147.5, 147.5) + amrap(3, 152.5),
    top = 70.0 to 8, mrs = 62.5 to 6, emomFail = 4
  )

  @Test fun week1_benchB() = assertDay(                                   // fixture day 4
    week = 1, slot = Cap3DaySlot.BENCH_B, title = "Chest & Biceps",
    lift1 = reps(6, 60.0, 60.0, 60.0, 60.0, 60.0) + reps(5, 67.5, 67.5) + amrap(5, 70.0),
    top = 70.0 to 8, mrs = 62.5 to 6, emomFail = 4
  )

  @Test fun week1_deadliftB_isTheMajorEffortTest() {                      // fixture day 6
    val day = day(1, Cap3DaySlot.DEADLIFT_B)
    assertEquals("Back, Abs & Triceps", day.title)
    val lift1 = day.lift1!!
    assertTrue(lift1.isMajorEffort)
    assertEquals(listOf(215.0 to 6, 227.5 to 4, 240.0 to 2), lift1.sets.map { it.weight to it.repsTarget })
    lift1.sets.forEach { assertEquals(Cap3SetType.AMRAP, it.type) }
    // A de-emphasis week for this secondary: Cable Row 62.5 × 8-12, then 3 × 10 @ 55, no EMOM.
    val lift2 = day.lift2!!
    assertEquals("Cable Row", lift2.exercise)
    assertEquals(62.5, lift2.sets.first().weight, EPS)
    assertEquals("8-12", lift2.sets.first().repsDisplay)
    assertEquals(listOf(55.0 to 10, 55.0 to 10, 55.0 to 10), lift2.sets.drop(1).map { it.weight to it.repsTarget })
    assertEquals(false, Cap3Engine.hasEmom(1, Cap3DaySlot.DEADLIFT_B))
  }

  @Test fun week1_squatB() = assertDay(                                   // fixture day 7
    week = 1, slot = Cap3DaySlot.SQUAT_B, title = "Legs & Shoulders",
    lift1 = reps(5, 130.0, 130.0, 130.0, 130.0) +
            reps(4, 135.0, 135.0, 135.0, 135.0) + amrap(4, 142.5),
    top = 72.5 to 6, mrs = 67.5 to 5, emomFail = 3
  )

  // ── Week 2 (fixture ME = Squat) ─────────────────────────────────────────────

  @Test fun week2_benchA() = assertDay(                                   // fixture day 8
    week = 2, slot = Cap3DaySlot.BENCH_A, title = "Chest & Biceps",
    lift1 = reps(3, 67.5, 67.5, 67.5, 67.5, 70.0, 72.5, 72.5) + amrap(3, 75.0),
    top = 70.0 to 8, mrs = 62.5 to 6, emomFail = 4
  )

  @Test fun week2_deadliftA() = assertDay(                                // fixture day 9
    week = 2, slot = Cap3DaySlot.DEADLIFT_A, title = "Back, Abs & Triceps",
    lift1 = reps(8, 165.0, 165.0, 165.0, 165.0) + reps(6, 172.5, 172.5) + amrap(6, 182.5),
    top = 65.0 to 10, mrs = 60.0 to 6, emomFail = 5
  )

  @Test fun week2_squatA_isTheMajorEffortTest() {                         // fixture day 11
    val day = day(2, Cap3DaySlot.SQUAT_A)
    assertEquals("Shoulders & Legs", day.title)
    assertTrue(day.lift1!!.isMajorEffort)
    assertEquals(listOf(145.0 to 6, 152.5 to 4, 160.0 to 2), day.lift1!!.sets.map { it.weight to it.repsTarget })
    assertSecondary(day, top = 62.5 to 12, mrs = 55.0 to 10, emomFail = 6)
  }

  @Test fun week2_benchB_secondaryDeEmphasised() {                        // fixture day 12
    val day = day(2, Cap3DaySlot.BENCH_B)
    assertPrimary(day, reps(5, 65.0, 65.0, 65.0, 65.0) +
                       reps(4, 70.0, 70.0, 70.0, 70.0) + amrap(4, 75.0))
    val lift2 = day.lift2!!
    assertEquals(72.5, lift2.sets.first().weight, EPS)
    assertEquals("5-8", lift2.sets.first().repsDisplay)
    assertEquals(listOf(67.5 to 6, 67.5 to 6, 67.5 to 6), lift2.sets.drop(1).map { it.weight to it.repsTarget })
    assertEquals(false, Cap3Engine.hasEmom(2, Cap3DaySlot.BENCH_B))
  }

  @Test fun week2_deadliftB() = assertDay(                                // fixture day 13
    week = 2, slot = Cap3DaySlot.DEADLIFT_B, title = "Back, Abs & Triceps",
    lift1 = reps(4, 202.5, 202.5, 202.5, 202.5, 207.5, 207.5) + amrap(4, 212.5),
    top = 70.0 to 10, mrs = 62.5 to 8, emomFail = 5
  )

  @Test fun week2_squatB_secondaryDeEmphasised() {                        // fixture day 14
    val day = day(2, Cap3DaySlot.SQUAT_B)
    assertPrimary(day, reps(8, 110.0, 110.0, 110.0, 110.0) + reps(6, 115.0, 115.0) + amrap(6, 122.5))
    val lift2 = day.lift2!!
    assertEquals(70.0, lift2.sets.first().weight, EPS)
    assertEquals("6-10", lift2.sets.first().repsDisplay)
    assertEquals(listOf(62.5 to 5, 62.5 to 5, 62.5 to 5), lift2.sets.drop(1).map { it.weight to it.repsTarget })
  }

  // ── Week 3 (fixture ME = Bench) ─────────────────────────────────────────────

  @Test fun week3_benchA_isTheMajorEffortTest() {                         // fixture day 16
    val day = day(3, Cap3DaySlot.BENCH_A)
    assertEquals("Chest & Biceps", day.title)
    assertTrue(day.lift1!!.isMajorEffort)
    assertEquals(listOf(72.5 to 6, 77.5 to 4, 80.0 to 2), day.lift1!!.sets.map { it.weight to it.repsTarget })
    assertSecondary(day, top = 62.5 to 12, mrs = 55.0 to 10, emomFail = 6)
  }

  @Test fun week3_deadliftA() = assertDay(                                // fixture day 17
    week = 3, slot = Cap3DaySlot.DEADLIFT_A, title = "Back, Abs & Triceps",
    lift1 = reps(6, 182.5, 182.5, 182.5, 182.5, 182.5) + reps(5, 187.5, 187.5) + amrap(5, 195.0),
    top = 70.0 to 8, mrs = 62.5 to 5, emomFail = 4
  )

  @Test fun week3_squatA() = assertDay(                                   // fixture day 18
    week = 3, slot = Cap3DaySlot.SQUAT_A, title = "Shoulders & Legs",
    lift1 = reps(4, 135.0, 135.0, 135.0, 142.5, 142.5, 142.5) + amrap(4, 147.5),
    top = 65.0 to 10, mrs = 60.0 to 8, emomFail = 5
  )

  @Test fun week3_benchB() = assertDay(                                   // fixture day 19
    week = 3, slot = Cap3DaySlot.BENCH_B, title = "Chest & Biceps",
    lift1 = reps(8, 55.0, 55.0, 55.0, 55.0) + reps(6, 60.0, 60.0) + amrap(6, 65.0),
    top = 65.0 to 10, mrs = 60.0 to 10, emomFail = 5
  )

  @Test fun week3_deadliftB() = assertDay(                                // fixture day 20
    week = 3, slot = Cap3DaySlot.DEADLIFT_B, title = "Back, Abs & Triceps",
    lift1 = reps(3, 212.5, 212.5, 212.5, 212.5, 217.5, 217.5, 217.5) + amrap(3, 222.5),
    top = 65.0 to 8, mrs = 60.0 to 6, emomFail = 4
  )

  @Test fun week3_squatB() = assertDay(                                   // fixture day 21
    week = 3, slot = Cap3DaySlot.SQUAT_B, title = "Legs & Shoulders",
    lift1 = reps(6, 120.0, 120.0, 120.0, 120.0, 120.0) + reps(5, 125.0, 125.0) + amrap(5, 132.5),
    top = 65.0 to 10, mrs = 60.0 to 8, emomFail = 5
  )

  // ── Cycle shape ─────────────────────────────────────────────────────────────

  @Test fun everyWeekIsSixTrainingDaysWithNoRest() {
    for (week in 1..CAP3_WEEKS) {
      val days = Cap3Engine.week(config, week)
      assertEquals(CAP3_DAYS_PER_WEEK, days.size)
      assertEquals((1..6).toList(), days.map { it.dayNumber })
      days.forEach {
        assertNotNull("week $week day ${it.dayNumber} lift 1", it.lift1)
        assertNotNull("week $week day ${it.dayNumber} lift 2", it.lift2)
      }
    }
  }

  @Test fun majorEffortRotatesDeadliftSquatBench_oneDayPerWeek() {
    assertEquals(Cap3LiftCategory.DEADLIFT, Cap3Schemes.majorEffortLift(1))
    assertEquals(Cap3LiftCategory.SQUAT, Cap3Schemes.majorEffortLift(2))
    assertEquals(Cap3LiftCategory.BENCH, Cap3Schemes.majorEffortLift(3))
    for (week in 1..CAP3_WEEKS) {
      assertEquals("week $week", 1, Cap3Engine.week(config, week).count { it.isMajorEffort })
    }
  }

  @Test fun exactlyOneAmrapTopSetOnEveryNonMajorEffortDay() {
    for (week in 1..CAP3_WEEKS) {
      Cap3Engine.week(config, week).forEach { day ->
        val lift1 = day.lift1!!
        val amraps = lift1.sets.count { it.type == Cap3SetType.AMRAP }
        assertEquals("week $week day ${day.dayNumber}", if (lift1.isMajorEffort) 3 else 1, amraps)
        assertEquals("week $week day ${day.dayNumber}", amraps, lift1.sets.count { it.repsDisplay.endsWith("+") })
      }
    }
  }

  @Test fun theEmomIsASeparateBlockAndOnlyOnEmomWeeks() {
    for (week in 1..CAP3_WEEKS) {
      Cap3DaySlot.entries.forEach { slot ->
        val emom = Cap3Engine.block(config, Cap3Tag(week, slot, Cap3Role.EMOM))
        if (Cap3Engine.hasEmom(week, slot)) {
          assertNotNull("week $week $slot", emom)
          assertTrue(emom!!.sets.isEmpty())              // the EMOM page prescribes no fixed sets
          assertEquals(emom.emom!!.weight, day(week, slot).lift2!!.sets[1].weight, EPS)
        } else {
          assertNull("week $week $slot", emom)
        }
      }
    }
    // Three of the eighteen days are de-emphasis days, and they are the ones with a backoff.
    val withoutEmom = (1..CAP3_WEEKS).flatMap { w -> Cap3DaySlot.entries.filterNot { Cap3Engine.hasEmom(w, it) } }
    assertEquals(3, withoutEmom.size)
  }

  // ── Helpers ─────────────────────────────────────────────────────────────────

  private fun day(week: Int, slot: Cap3DaySlot): Cap3Day =
    Cap3Engine.week(config, week).first { it.daySlot == slot }

  private fun assertDay(
    week: Int,
    slot: Cap3DaySlot,
    title: String,
    lift1: List<Triple<Double, Int, Cap3SetType>>,
    top: Pair<Double, Int>,
    mrs: Pair<Double, Int>,
    emomFail: Int
  ) {
    val day = day(week, slot)
    assertEquals("week $week $slot title", title, day.title)
    assertPrimary(day, lift1)
    assertSecondary(day, top, mrs, emomFail)
  }

  private fun assertPrimary(day: Cap3Day, expected: List<Triple<Double, Int, Cap3SetType>>) {
    val actual = day.lift1!!.sets.map { Triple(it.weight, it.repsTarget, it.type) }
    assertEquals("week ${day.week} ${day.daySlot} lift 1", expected, actual)
  }

  private fun assertSecondary(day: Cap3Day, top: Pair<Double, Int>, mrs: Pair<Double, Int>, emomFail: Int) {
    val lift2 = day.lift2!!
    val where = "week ${day.week} ${day.daySlot}"
    assertEquals("$where top set", top, lift2.sets[0].weight to lift2.sets[0].repsTarget)
    assertEquals("$where MRS", mrs, lift2.sets[1].weight to lift2.sets[1].repsTarget)
    // Both green sets are minimum-then-optional-AMRAP, shown as "N+".
    assertEquals(Cap3SetType.MIN_THEN_OPTIONAL_AMRAP, lift2.sets[0].type)
    assertEquals(Cap3SetType.MIN_THEN_OPTIONAL_AMRAP, lift2.sets[1].type)
    assertEquals("${top.second}+", lift2.sets[0].repsDisplay)
    assertEquals("${mrs.second}+", lift2.sets[1].repsDisplay)
    // The EMOM continues at the MRS weight until the user drops below the fail threshold.
    val emom = Cap3Engine.block(config, Cap3Tag(day.week, day.daySlot, Cap3Role.EMOM))!!.emom
    assertEquals("$where EMOM", Cap3Emom(mrs.first, emomFail), emom)
  }

  private fun reps(reps: Int, vararg weights: Double): List<Triple<Double, Int, Cap3SetType>> =
    weights.map { Triple(it, reps, Cap3SetType.FIXED) }

  private fun amrap(reps: Int, weight: Double): List<Triple<Double, Int, Cap3SetType>> =
    listOf(Triple(weight, reps, Cap3SetType.AMRAP))

  companion object {
    const val EPS = 1e-9

    /** Squat 180, Bench 90, Deadlift 270, every secondary 90 — in kg, step 2.5. */
    fun fixtureConfig(units: Cap3Units = Cap3Units.KG): Cap3Config {
      val tms = mapOf(
        Cap3Slot.SQUAT_1 to 180.0, Cap3Slot.SQUAT_2 to 180.0,
        Cap3Slot.BENCH_1 to 90.0, Cap3Slot.BENCH_2 to 90.0,
        Cap3Slot.DEADLIFT_1 to 270.0, Cap3Slot.DEADLIFT_2 to 270.0,
        Cap3Slot.OVERHEAD_1 to 90.0, Cap3Slot.OVERHEAD_2 to 90.0,
        Cap3Slot.ROW_1 to 90.0, Cap3Slot.ROW_2 to 90.0
      )
      return Cap3Config(
        units = units,
        lifts = tms.mapValues { (slot, tm) ->
          Cap3LiftConfig(
            slot = slot,
            referenceMaxKg = tm / CAP3_TM_FACTOR,
            trainingMaxKg = tm,
            bestEst1RmKg = tm / CAP3_TM_FACTOR
          )
        }
      )
    }
  }
}
