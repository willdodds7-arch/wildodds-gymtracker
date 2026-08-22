package com.wildodds.gymtracker.data.cap3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The CAP3 formulas and week mechanics, away from the day-by-day fixture. */
class Cap3EngineTest {

  private val config = Cap3FixtureTest.fixtureConfig()

  private fun t1(week: Int, slot: Cap3DaySlot) =
    Cap3Engine.block(config, Cap3Tag(week, slot, Cap3Role.T1))!!

  // ── Core formulas ───────────────────────────────────────────────────────────

  @Test
  fun epley_truncatesToAWholeUnit() {
    // 70 × (0.0333 × 8 + 1) = 88.648 → 88
    assertEquals(88.0, Cap3Engine.estimatedOneRepMax(70.0, 8), 1e-9)
    // The sheet's coefficient is the literal 0.0333, which is not normalised at one rep:
    // 100 × 1.0333 = 103.33 → 103, not 100. Kept verbatim so the numbers match the source.
    assertEquals(103.0, Cap3Engine.estimatedOneRepMax(100.0, 1), 1e-9)
  }

  @Test
  fun trainingMaxIsNinetyPercentOfTheReferenceMax() {
    assertEquals(90.0, Cap3Engine.trainingMaxFrom(100.0), 1e-9)
    assertEquals(180.0, Cap3Engine.trainingMaxFrom(200.0), 1e-9)
  }

  @Test
  fun snapRespectsDirectionAndStep() {
    assertEquals(62.5, Cap3Engine.snap(64.8, 2.5, Cap3Rounding.FLOOR), 1e-9)
    assertEquals(65.0, Cap3Engine.snap(64.8, 2.5, Cap3Rounding.CEILING), 1e-9)
    // An exact multiple must not creep a step in either direction.
    assertEquals(202.5, Cap3Engine.snap(202.5, 2.5, Cap3Rounding.FLOOR), 1e-9)
    assertEquals(202.5, Cap3Engine.snap(202.5, 2.5, Cap3Rounding.CEILING), 1e-9)
    assertEquals(145.0, Cap3Engine.snap(142.86, 5.0, Cap3Rounding.CEILING), 1e-9)
  }

  @Test
  fun secondaryWeightSolvesTheEpleyCurveForLoad() {
    // TM 90 at 12 reps: 90 / (0.995 + 0.0333 × 12) = 64.53 → 65 up, 62.5 down.
    assertEquals(65.0, Cap3Engine.secondaryWeight(90.0, 12, 2.5, Cap3Rounding.CEILING), 1e-9)
    assertEquals(62.5, Cap3Engine.secondaryWeight(90.0, 12, 2.5, Cap3Rounding.FLOOR), 1e-9)
  }

  @Test
  fun secondaryVolumeIsWeightTimesRepsSummed() {
    // 650 + 480 + 180
    assertEquals(1310.0, Cap3Engine.secondaryVolume(listOf(65.0 to 10, 60.0 to 8, 60.0 to 3)), 1e-9)
  }

  // ── Major-Effort options ────────────────────────────────────────────────────

  @Test
  fun majorEffortOptionsAreCeilingsOfTheSameThreePercentages() {
    for ((week, expected) in mapOf(
      1 to listOf(215.0, 227.5, 240.0),   // deadlift, TM 270
      2 to listOf(145.0, 152.5, 160.0),   // squat, TM 180
      3 to listOf(72.5, 77.5, 80.0)       // bench, TM 90
    )) {
      val lift1 = t1(week, Cap3Schemes.majorEffortSlot.getValue(week))
      assertEquals("week $week", expected, lift1.sets.map { it.weight })
      assertEquals("week $week", listOf(6, 4, 2), lift1.sets.map { it.repsTarget })
      assertEquals(
        Cap3MajorEffort.options.map { (pct, _) -> Cap3Engine.snap(lift1.trainingMax * pct, 2.5, Cap3Rounding.CEILING) },
        lift1.sets.map { it.weight }
      )
    }
  }

  // ── Weeks ───────────────────────────────────────────────────────────────────

  @Test
  fun eachWeekLoadsItsOwnScheme() {
    // Bench's heavier session: 7 sets of 4 in week 1, 8 sets of 3 in week 2, the ME test in week 3.
    val w1 = t1(1, Cap3DaySlot.BENCH_A)
    val w2 = t1(2, Cap3DaySlot.BENCH_A)
    val w3 = t1(3, Cap3DaySlot.BENCH_A)
    assertEquals(listOf(7, 4), listOf(w1.sets.size, w1.sets.first().repsTarget))
    assertEquals(listOf(8, 3), listOf(w2.sets.size, w2.sets.first().repsTarget))
    assertTrue(w3.isMajorEffort)
  }

  @Test
  fun eachMainLiftTrainsTwicePerWeekWithItsOwnVariationSlot() {
    for (week in 1..CAP3_WEEKS) {
      val days = Cap3Engine.week(config, week)
      assertEquals(6, days.size)
      val byCategory = days.groupBy { it.lift1!!.slot.category }
      listOf(Cap3LiftCategory.SQUAT, Cap3LiftCategory.BENCH, Cap3LiftCategory.DEADLIFT).forEach { category ->
        val forCategory = byCategory[category].orEmpty()
        assertEquals("week $week $category", 2, forCategory.size)
        assertEquals("week $week $category", setOf(1, 2), forCategory.map { it.lift1!!.slot.variation }.toSet())
      }
    }
  }

  // ── Tags ────────────────────────────────────────────────────────────────────

  @Test
  fun tagsRoundTripAndRejectRubbish() {
    val tag = Cap3Tag(2, Cap3DaySlot.SQUAT_B, Cap3Role.EMOM)
    assertEquals("2:SQUAT_B:EMOM", tag.toString())
    assertEquals(tag, Cap3Tag.parse(tag.toString()))
    listOf(null, "", "not a tag", "1:NOPE:T1", "9:SQUAT_B:T1", "1:SQUAT_B:T9", "1:SQUAT_B").forEach {
      assertNull(it, Cap3Tag.parse(it))
    }
  }

  @Test
  fun anUnconfiguredLiftHasNoBlockToPrice() {
    val empty = Cap3Config()
    Cap3Role.entries.forEach {
      assertNull(Cap3Engine.block(empty, Cap3Tag(1, Cap3DaySlot.BENCH_A, it)))
    }
  }

  // ── Live recomputation ──────────────────────────────────────────────────────

  @Test
  fun changingATrainingMaxRecomputesEveryWeightForThatLift() {
    val before = t1(1, Cap3DaySlot.BENCH_A)
    val bumped = config.withTrainingMax(Cap3Slot.BENCH_1, 100.0)
    val after = Cap3Engine.block(bumped, Cap3Tag(1, Cap3DaySlot.BENCH_A, Cap3Role.T1))!!
    // 100 × 0.72 = 72 → 72.5 up; 100 × 0.81 = 81 → 80 down.
    assertEquals(72.5, after.sets.first().weight, 1e-9)
    assertEquals(80.0, after.sets.last().weight, 1e-9)
    assertNotEquals(before.sets.map { it.weight }, after.sets.map { it.weight })
    // Other lifts are untouched.
    assertEquals(
      t1(1, Cap3DaySlot.DEADLIFT_A).sets.map { it.weight },
      Cap3Engine.block(bumped, Cap3Tag(1, Cap3DaySlot.DEADLIFT_A, Cap3Role.T1))!!.sets.map { it.weight }
    )
  }

  @Test
  fun referenceMaxSurvivesTrainingMaxProgression() {
    val original = config.lift(Cap3Slot.SQUAT_1).referenceMaxKg
    val progressed = config
      .withTrainingMax(Cap3Slot.SQUAT_1, 190.0)
      .withTrainingMax(Cap3Slot.SQUAT_1, 200.0, bestEst1RmKg = 222.0)
    assertEquals(original, progressed.lift(Cap3Slot.SQUAT_1).referenceMaxKg, 1e-9)
    assertEquals(200.0, progressed.lift(Cap3Slot.SQUAT_1).trainingMaxKg, 1e-9)
    // The training max is allowed to grow past the reference max it started from.
    assertTrue(
      Cap3Config(lifts = mapOf(Cap3Slot.SQUAT_1 to Cap3LiftConfig(Cap3Slot.SQUAT_1, referenceMaxKg = 100.0)))
        .withTrainingMax(Cap3Slot.SQUAT_1, 110.0)
        .lift(Cap3Slot.SQUAT_1).let { it.trainingMaxKg > it.referenceMaxKg }
    )
  }

  // ── Units ───────────────────────────────────────────────────────────────────

  @Test
  fun switchingUnitsReRoundsEveryWeightToTheNewStep() {
    fun allWeights(cfg: Cap3Config) = (1..CAP3_WEEKS).flatMap { week ->
      Cap3Engine.week(cfg, week).flatMap { it.lift1!!.sets + it.lift2!!.sets }.map { it.weight }
    }
    allWeights(config).forEach { assertEquals("$it", 0.0, it % 2.5, 1e-9) }
    val lb = config.copy(units = Cap3Units.LB)
    allWeights(lb).forEach { assertEquals("$it", 0.0, it % 5.0, 1e-9) }
    // 90 kg → 198.4 lb; the week 1 bench opener is CEILING(198.4 × 0.72) = 145 lb.
    assertEquals(145.0, Cap3Engine.block(lb, Cap3Tag(1, Cap3DaySlot.BENCH_A, Cap3Role.T1))!!.sets.first().weight, 1e-9)
    assertEquals(2.5, Cap3Units.KG.step, 1e-9)
    assertEquals(5.0, Cap3Units.LB.step, 1e-9)
  }

  @Test
  fun unitConversionLeavesTheStoredKilogramsAlone() {
    val lb = config.copy(units = Cap3Units.LB)
    assertEquals(90.0, lb.lift(Cap3Slot.BENCH_1).trainingMaxKg, 1e-9)
    assertEquals(198.416, lb.lift(Cap3Slot.BENCH_1).trainingMaxKg * Cap3Units.LB.perKg, 0.01)
  }

  // ── Set types ───────────────────────────────────────────────────────────────

  @Test
  fun setTypesAreDistinctAndOnlyAmrapCapableSetsCarryAPlus() {
    val types = Cap3SetType.entries.toSet()
    assertEquals(4, types.size)
    assertEquals(
      setOf(Cap3SetType.AMRAP, Cap3SetType.MIN_THEN_OPTIONAL_AMRAP),
      types.filter { it.isAmrapCapable }.toSet()
    )
    for (week in 1..CAP3_WEEKS) {
      Cap3Engine.week(config, week).forEach { day ->
        (day.lift1!!.sets + day.lift2!!.sets).forEach { set ->
          if (set.repsDisplay.endsWith("+")) assertTrue("week $week ${day.daySlot}", set.isAmrapCapable)
        }
      }
    }
  }

  // ── Lift catalogue ──────────────────────────────────────────────────────────

  @Test
  fun everySlotDefaultsToAnExerciseFromItsOwnDropdown() {
    Cap3Slot.entries.forEach { slot ->
      assertTrue(slot.name, slot.category.options.contains(slot.defaultExercise))
    }
    assertEquals(16, Cap3LiftCategory.SQUAT.options.size)
    assertEquals(13, Cap3LiftCategory.BENCH.options.size)
    assertEquals(14, Cap3LiftCategory.DEADLIFT.options.size)
    assertEquals(8, Cap3LiftCategory.OVERHEAD.options.size)
    assertEquals(8, Cap3LiftCategory.ROW.options.size)
  }
}
