package com.wildodds.gymtracker.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The set-notation parser is the crux of import — it must read what lifters actually write, and it
 *  must never invent a value the source didn't state. */
class SetNotationParserTest {

  private fun first(line: String) = SetNotationParser.parseExerciseLine(line, 0)!!.sets.first()
  private fun slot(line: String) = SetNotationParser.parseExerciseLine(line, 0)!!

  @Test fun straightSets() {
    val p = first("Barbell Bench Press 3x8")
    assertEquals(3, p.count); assertEquals(RepKind.COUNT, p.reps!!.kind); assertEquals(8, p.reps!!.min)
    assertEquals("Barbell Bench Press", slot("Barbell Bench Press 3x8").exercise.original)
  }

  @Test fun repRange() {
    val p = first("Squat 3x8-12")
    assertEquals(RepKind.RANGE, p.reps!!.kind); assertEquals(8, p.reps!!.min); assertEquals(12, p.reps!!.max)
  }

  @Test fun altSeparators() {
    assertEquals(4, first("Row 4X10").count)
    assertEquals(4, first("Row 4*10").count)
    assertEquals(3, first("Row 3 sets of 12").count)
  }

  @Test fun perSetVariance() {
    val p = first("Deadlift 5/3/1")
    assertEquals(3, p.count); assertEquals(3, p.perSet.size)
    assertEquals(listOf(5, 3, 1), p.perSet.map { it.reps!!.min })
  }

  @Test fun perSetCommaForm() {
    // Real programs (e.g. Ratelle) write per-set reps comma-separated.
    val p = first("Front Squat 6, 8, 10")
    assertEquals(3, p.count)
    assertEquals(listOf(6, 8, 10), p.perSet.map { it.reps!!.min })
    // Two comma numbers must NOT be treated as a per-set list ("rest 2 min" style noise).
    assertEquals(3, first("Bench 3x5 rest 2 min").count)
  }

  @Test fun rpeLoad() {
    val p = first("Squat 3x5 @RPE8")
    assertEquals(LoadKind.RPE, p.load!!.kind); assertEquals("8", p.load!!.text)
  }

  @Test fun percentAndTrainingMax() {
    assertEquals(LoadKind.PCT_1RM, first("Squat 5x5 @75%").load!!.kind)
    val tm = first("OHP 3x5 @80% TM")
    assertEquals(LoadKind.PCT_TM, tm.load!!.kind); assertEquals(80.0, tm.load!!.value!!, 0.01)
  }

  @Test fun absoluteWeight() {
    val p = first("Bench 5x5 @100kg")
    assertEquals(LoadKind.ABSOLUTE, p.load!!.kind); assertEquals(WeightUnit.KG, p.load!!.unit)
  }

  @Test fun amrapFinalSet() {
    val p = first("Bench 1x5+ @85% TM")
    assertTrue(p.isFinalSetAmrap); assertEquals(RepKind.AMRAP_MIN, p.reps!!.kind); assertEquals(5, p.reps!!.min)
    assertEquals(LoadKind.PCT_TM, p.load!!.kind)
  }

  @Test fun timeAndDistance() {
    assertEquals(RepKind.TIME_SEC, first("Plank 3x30s").reps!!.kind)
    assertEquals(30, first("Plank 3x30s").reps!!.min)
    assertEquals(RepKind.DISTANCE_M, first("Sled Push 5x400m").reps!!.kind)
    assertEquals(400, first("Sled Push 5x400m").reps!!.min)
  }

  @Test fun restFixedAndRange() {
    assertEquals(RestKind.FIXED, slot("Bench 3x5 rest 3 min").rest.kind)
    assertEquals(180, slot("Bench 3x5 rest 3 min").rest.lowSec)
    val r = slot("Bench 3x5 rest 2-3 min").rest
    assertEquals(RestKind.RANGE, r.kind); assertEquals(120, r.lowSec); assertEquals(180, r.highSec)
  }

  @Test fun superset() {
    val s = slot("A1) Incline DB Press 3x10")
    assertEquals("A", s.supersetGroup); assertEquals("Incline DB Press", s.exercise.original)
  }

  @Test fun tempoNotConfusedWithSetsReps() {
    // "10x8" must be 10 sets of 8, not a tempo.
    val p = first("Squat 10x8")
    assertEquals(10, p.count); assertEquals("", p.tempo)
    // A real tempo (uppercase X) is captured.
    assertEquals("31X0", first("Squat 3x5 31X0").tempo)
  }

  @Test fun noFabrication_unknownLineKeepsVerbatimAndNullsCount() {
    val s = slot("Some Weird Conditioning Finisher")
    val p = s.sets.first()
    assertEquals(SetKind.UNKNOWN, p.kind)
    assertNull("count must be null, never guessed", p.count)
    assertNull("reps must be null, never guessed", p.reps)
    assertEquals("Some Weird Conditioning Finisher", s.rawText)
    assertTrue("low confidence", s.confidence < 0.5)
  }

  @Test fun blankReturnsNull() {
    assertNull(SetNotationParser.parseExerciseLine("   ", 0))
  }
}
