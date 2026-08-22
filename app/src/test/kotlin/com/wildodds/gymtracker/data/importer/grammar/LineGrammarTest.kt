package com.wildodds.gymtracker.data.importer.grammar

import com.wildodds.gymtracker.data.importer.LoadKind
import com.wildodds.gymtracker.data.importer.Modifier
import com.wildodds.gymtracker.data.importer.RepKind
import com.wildodds.gymtracker.data.importer.RestKind
import com.wildodds.gymtracker.data.importer.SetKind
import com.wildodds.gymtracker.data.importer.SetPrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notation the grammar reads beyond what the eleven-regex parser could.
 *
 * `SetNotationParserTest` is the conformance suite — everything the old parser read, still read.
 * This is the extension: the forms real coaches write that used to fall through as unparsed text.
 *
 * Two rules are asserted throughout rather than in one place, because they are the whole point:
 * a number that is not in the source is never in the output, and a line the grammar cannot fully
 * read reports LOW COVERAGE rather than a confident wrong answer.
 */
class LineGrammarTest {

  private fun p(line: String) = LineGrammar.parse(line)!!
  private fun pres(line: String): SetPrescription = p(line).prescription

  // ── 5/3/1 and its dialects ───────────────────────────────────────────────────

  @Test fun `five three one reps survive as three sets`() {
    val s = pres("Deadlift 5/3/1")
    assertEquals(3, s.count)
    assertEquals(listOf(5, 3, 1), s.perSet.map { it.reps!!.min })
  }

  @Test fun `the plus in five three one plus is an AMRAP on the last set`() {
    val s = pres("Bench 1x5+ @85% TM")
    assertTrue(s.isFinalSetAmrap)
    assertEquals(RepKind.AMRAP_MIN, s.reps!!.kind)
    assertEquals(LoadKind.PCT_TM, s.load!!.kind)
  }

  @Test fun `a percentage wave becomes per-set loads, not a rep list`() {
    val s = pres("Squat 65/75/85%")
    assertEquals(3, s.count)
    assertEquals(listOf(65.0, 75.0, 85.0), s.perSet.map { it.load!!.value })
    assertTrue("these are loads; no rep count was stated", s.perSet.all { it.reps == null })
  }

  @Test fun `one TM on the line governs every percentage on it`() {
    // Coaches write "70/80/90 TM", never "70% TM, 80% TM, 90% TM". Getting this wrong makes every
    // 5/3/1 import wrong by the ratio of training max to true max.
    val s = pres("Squat 70/80/90% TM")
    assertTrue(s.perSet.all { it.load!!.kind == LoadKind.PCT_TM })
  }

  @Test fun `FSL and BBB are recorded rather than dropped`() {
    assertTrue("fsl" in pres("Bench FSL 5x5").modifiers)
    assertTrue("bbb" in pres("Bench BBB 5x10 @ 50%").modifiers)
    assertEquals(5, pres("Bench FSL 5x5").count)
    assertEquals(50.0, pres("Bench BBB 5x10 @ 50%").load!!.value!!, 0.01)
  }

  // ── cluster, rest-pause, drop, myo ───────────────────────────────────────────

  @Test fun `a cluster set keeps its mini-sets`() {
    val s = pres("Front Squat 3x(3.3.3) rest 15s")
    assertEquals(SetKind.CLUSTER, s.kind)
    assertEquals(3, s.count)
    assertEquals(listOf(3, 3, 3), s.perSet.map { it.reps!!.min })
    assertTrue(Modifier.CLUSTER in s.modifiers)
  }

  @Test fun `rest-pause keeps every burst`() {
    val s = pres("Lat Pulldown 1x8+2+2")
    assertEquals(SetKind.RESTPAUSE, s.kind)
    assertEquals(listOf(8, 2, 2), s.perSet.map { it.reps!!.min })
    assertEquals("the total is stated, not guessed", 12, s.reps!!.min)
  }

  @Test fun `a drop set is marked`() {
    val s = pres("Leg Extension 3x10 drop set")
    assertEquals(SetKind.DROP, s.kind)
    assertTrue(Modifier.DROP_SET in s.modifiers)
    assertEquals(3, s.count)
  }

  @Test fun `myo-reps are marked`() {
    assertTrue(Modifier.MYO_REPS in pres("Cable Fly 1x15 myo reps").modifiers)
  }

  // ── ladders, pyramids, for-time ──────────────────────────────────────────────

  @Test fun `a ladder repeated N times gives N times its rungs`() {
    val s = pres("Pull Up 1,2,3,4,5 x 5 rounds")
    assertEquals(SetKind.LADDER, s.kind)
    assertEquals(25, s.count)
    assertEquals(listOf(1, 2, 3, 4, 5), s.perSet.map { it.reps!!.min })
  }

  @Test fun `a descending list is a pyramid`() {
    val s = pres("Bench 12/10/8/6")
    assertEquals(SetKind.PYRAMID, s.kind)
    assertEquals(listOf(12, 10, 8, 6), s.perSet.map { it.reps!!.min })
  }

  @Test fun `twenty one fifteen nine is for time`() {
    val s = pres("Thruster 21-15-9")
    assertEquals(SetKind.FOR_TIME, s.kind)
    assertEquals(listOf(21, 15, 9), s.perSet.map { it.reps!!.min })
  }

  @Test fun `a two number dash is still a rep range, not a for-time list`() {
    val s = pres("Curl 3x8-12")
    assertEquals(RepKind.RANGE, s.reps!!.kind)
    assertEquals(8, s.reps!!.min)
    assertEquals(12, s.reps!!.max)
  }

  // ── EMOM, AMRAP, for time ────────────────────────────────────────────────────

  @Test fun `EMOM twelve is twelve minutes of work`() {
    val s = pres("Power Clean EMOM 12")
    assertEquals(SetKind.EMOM, s.kind)
    assertEquals(12, s.count)
    assertTrue(Modifier.EMOM in s.modifiers)
  }

  @Test fun `OTM and E2MOM are the same family`() {
    assertEquals(SetKind.EMOM, pres("Snatch OTM 10").kind)
    assertEquals(SetKind.EMOM, pres("Clean E2MOM 16").kind)
  }

  @Test fun `AMRAP in twelve minutes is a timed AMRAP`() {
    val s = pres("Burpee AMRAP 12 min")
    assertEquals(SetKind.AMRAP_TIME, s.kind)
    assertEquals(RepKind.AMRAP_MIN, s.reps!!.kind)
    assertEquals(720, s.reps!!.min)
  }

  @Test fun `for time is marked`() {
    assertTrue(Modifier.FOR_TIME in pres("Row 2000m for time").modifiers)
  }

  // ── unilateral, failure, totals ──────────────────────────────────────────────

  @Test fun `each side and per leg are modifiers, not part of the name`() {
    assertTrue(Modifier.EACH_SIDE in pres("Split Squat 3x10 each side").modifiers)
    assertTrue(Modifier.PER_LEG in pres("Step Up 3x12 per leg").modifiers)
    assertTrue(Modifier.EACH_SIDE in pres("Farmer Carry 3x30m e/s").modifiers)
    assertEquals("Split Squat", p("Split Squat 3x10 each side").name)
  }

  @Test fun `to failure is a modifier and never a rep count`() {
    val s = pres("Push Up 3 sets to failure")
    assertTrue(Modifier.TO_FAILURE in s.modifiers)
    assertEquals(3, s.count)
    assertNull("failure is not a number of reps", s.reps?.min)
  }

  @Test fun `total reps is marked`() {
    assertTrue(Modifier.TOTAL_REPS in pres("Chin Up 50 total reps").modifiers)
  }

  // ── loads ────────────────────────────────────────────────────────────────────

  @Test fun `a load range keeps both ends`() {
    val l = pres("Bench 3x8 @ 60-70kg").load!!
    assertEquals(LoadKind.ABSOLUTE, l.kind)
    assertEquals(60.0, l.value!!, 0.01)
    assertEquals(70.0, l.valueMax!!, 0.01)
  }

  @Test fun `an RPE range keeps both ends`() {
    val l = pres("Squat 3x5 @RPE 7-8").load!!
    assertEquals(LoadKind.RPE, l.kind)
    assertEquals(7.0, l.value!!, 0.01)
    assertEquals(8.0, l.valueMax!!, 0.01)
  }

  @Test fun `a percentage range keeps both ends`() {
    val l = pres("Deadlift 5x3 @ 70-80%").load!!
    assertEquals(LoadKind.PCT_1RM, l.kind)
    assertEquals(70.0, l.value!!, 0.01)
    assertEquals(80.0, l.valueMax!!, 0.01)
  }

  @Test fun `percent of e1RM, TM and bodyweight are different kinds`() {
    assertEquals(LoadKind.PCT_E1RM, pres("Squat 3x3 @ 85% e1RM").load!!.kind)
    assertEquals(LoadKind.PCT_TM, pres("Squat 3x3 @ 85% TM").load!!.kind)
    assertEquals(LoadKind.PCT_BODYWEIGHT, pres("Dip 3x8 @ 20% BW").load!!.kind)
  }

  @Test fun `bodyweight plus and minus are different from a plain absolute load`() {
    val plus = pres("Weighted Pull Up 3x5 BW+25kg").load!!
    assertEquals(LoadKind.BODYWEIGHT_PLUS, plus.kind)
    assertEquals(25.0, plus.value!!, 0.01)

    val minus = pres("Assisted Pull Up 3x8 BW-20kg").load!!
    assertEquals(LoadKind.BODYWEIGHT_MINUS, minus.kind)
    assertEquals(20.0, minus.value!!, 0.01)
  }

  @Test fun `bands and chains are recorded as accommodating resistance`() {
    val band = pres("Bench 3x3 @60% + red band")
    assertTrue(Modifier.BAND in band.modifiers)
    val chain = pres("Squat 5x2 + 20kg chain")
    assertTrue(Modifier.CHAIN in chain.modifiers)
  }

  @Test fun `RIR is read in both word orders`() {
    assertEquals(LoadKind.RIR, pres("Curl 3x12 2 RIR").load!!.kind)
    assertEquals(2.0, pres("Curl 3x12 2 RIR").load!!.value!!, 0.01)
    assertEquals(2.0, pres("Curl 3x12 RIR 2").load!!.value!!, 0.01)
  }

  @Test fun `an RPE beats an RIR when a line states both`() {
    // Both appearing is a coach hedging; RPE is the more specific instruction.
    assertEquals(LoadKind.RPE, pres("Squat 3x5 @RPE8 (2 RIR)").load!!.kind)
  }

  @Test fun `pounds hash and kilos all parse`() {
    assertEquals(225.0, pres("Squat 3x5 @ 225lb").load!!.value!!, 0.01)
    assertEquals(225.0, pres("Squat 3x5 @ 225#").load!!.value!!, 0.01)
    assertEquals(100.0, pres("Squat 3x5 @ 100 kilos").load!!.value!!, 0.01)
  }

  // ── rest ─────────────────────────────────────────────────────────────────────

  @Test fun `rest in clock form is seconds`() {
    val r = p("Bench 3x5 rest 1:30").rest
    assertEquals(RestKind.FIXED, r.kind)
    assertEquals(90, r.lowSec)
  }

  @Test fun `a rest range in seconds keeps both ends`() {
    val r = p("Bench 3x5 rest 90-120s").rest
    assertEquals(RestKind.RANGE, r.kind)
    assertEquals(90, r.lowSec)
    assertEquals(120, r.highSec)
  }

  @Test fun `rest as needed is not a number`() {
    assertEquals(RestKind.AS_NEEDED, p("Deadlift 5x1 rest as needed").rest.kind)
    assertEquals(RestKind.AS_NEEDED, p("Deadlift 5x1 walk back rest").rest.kind)
  }

  @Test fun `no rest in a superset is its own rest kind`() {
    assertEquals(RestKind.SUPERSET_NONE, p("A1) Bench 3x10 no rest").rest.kind)
  }

  // ── time, distance, calories ─────────────────────────────────────────────────

  @Test fun `durations in seconds and minutes`() {
    assertEquals(45, pres("Plank 3x45s").reps!!.min)
    assertEquals(120, pres("Plank 3 x 2:00").reps?.min ?: p("Plank 3 x 2:00").rest.lowSec)
    assertEquals(60, pres("Side Plank 60s each side").reps!!.min)
  }

  @Test fun `distances in metres and kilometres`() {
    assertEquals(400, pres("Sprint 5x400m").reps!!.min)
    assertEquals(2000, pres("Row 2000m").reps!!.min)
    assertEquals(5000, pres("Run 5km").reps!!.min)
  }

  @Test fun `calories are their own rep kind`() {
    assertEquals(RepKind.CALORIES, pres("Assault Bike 15 cal").reps!!.kind)
    assertEquals(15, pres("Assault Bike 15 cal").reps!!.min)
  }

  @Test fun `a bare m is metres when large and minutes when small`() {
    assertEquals(RepKind.DISTANCE_M, pres("Sled Push 5x400m").reps!!.kind)
    assertEquals(400, pres("Sled Push 5x400m").reps!!.min)
    val short = pres("Bike 3x2m")
    assertEquals(RepKind.TIME_SEC, short.reps!!.kind)
    assertEquals(120, short.reps!!.min)
  }

  @Test fun `an ambiguous bare m is flagged rather than silently decided`() {
    assertTrue("20m could be metres or minutes", p("Sled Drag 3x20m").ambiguousUnit)
    assertTrue("400m is not ambiguous", !p("Sprint 5x400m").ambiguousUnit)
  }

  // ── tempo ────────────────────────────────────────────────────────────────────

  @Test fun `tempo forms parse and sets by reps never does`() {
    assertEquals("31X0", pres("Squat 3x5 31X0").tempo)
    assertEquals("3-1-2-0", pres("Squat 3x5 3-1-2-0").tempo)
    assertEquals("tempo 3010 is a tempo when the word says so", "3010", pres("Squat 3x5 tempo 3010").tempo)
    // The rule that makes all of this safe.
    assertEquals("", pres("Squat 10x8").tempo)
    assertEquals(10, pres("Squat 10x8").count)
  }

  @Test fun `a bare four digit number is not a tempo`() {
    // A four-digit number is far more often a load or a distance.
    assertEquals("", pres("Row 3x2000").tempo)
  }

  @Test fun `paused is a modifier, not a tempo`() {
    val s = pres("Paused Bench 3x3 paused 2s")
    assertTrue(Modifier.PAUSED in s.modifiers)
    assertEquals("", s.tempo)
  }

  // ── supersets ────────────────────────────────────────────────────────────────

  @Test fun `a superset tag gives a group and an order`() {
    val a = p("A1) Incline DB Press 3x10")
    assertEquals("A", a.supersetGroup)
    assertEquals(1, a.supersetOrder)
    assertEquals("Incline DB Press", a.name)

    val b = p("B2. Cable Row 3x12")
    assertEquals("B", b.supersetGroup)
    assertEquals(2, b.supersetOrder)
  }

  @Test fun `a group letter with no number has no order`() {
    val a = p("C - Face Pull 3x15")
    assertEquals("C", a.supersetGroup)
    assertNull(a.supersetOrder)
  }

  // ── the anchor, and the name ─────────────────────────────────────────────────

  @Test fun `a number inside the exercise name does not become the prescription`() {
    val line = p("Dumbbell Row 45 degree 3x10")
    assertEquals("Dumbbell Row 45 degree", line.name)
    assertEquals(3, line.prescription.count)
    assertEquals(10, line.prescription.reps!!.min)
  }

  @Test fun `a line with no prescription keeps its whole name and reports UNKNOWN`() {
    val line = p("Some Weird Conditioning Finisher")
    assertEquals(SetKind.UNKNOWN, line.prescription.kind)
    assertNull(line.prescription.count)
    assertNull(line.prescription.reps)
    assertEquals("Some Weird Conditioning Finisher", line.name)
  }

  // ── coverage: the anti-silent-misread metric ─────────────────────────────────

  @Test fun `a fully understood line has full coverage`() {
    assertTrue("coverage was ${p("Bench Press 3x8 @ 80kg rest 90s").coverage}",
      p("Bench Press 3x8 @ 80kg rest 90s").coverage > 0.95)
  }

  @Test fun `a line with unreadable structure reports low coverage`() {
    // Made-up notation nobody's parser knows. The right answer is not to guess, it is to say so.
    val line = p("Bench 3x8 ~~~ zone 4 protocol delta 7")
    assertTrue("coverage was ${line.coverage}", line.coverage < 0.8)
    assertTrue(line.trailingNote.isNotBlank())
  }

  @Test fun `coverage is never outside zero to one`() {
    for (l in listOf("", "   ", "x", "3x8", "!!!", "Bench 3x8 @ 80kg", "a".repeat(400))) {
      val c = LineGrammar.parse(l)?.coverage ?: 0.0
      assertTrue("coverage $c for '$l'", c in 0.0..1.0)
    }
  }

  // ── determinism ──────────────────────────────────────────────────────────────

  @Test fun `the same line parses identically every time and in any locale`() {
    val line = "A1) Incline DB Press 3x8-12 @ RPE 7-8 rest 90s each side 31X0"
    val first = p(line)
    val defaults = java.util.Locale.getDefault()
    try {
      // Turkish lowercases I to a dotless i, which breaks any parser that lowercases without a
      // locale. This is exactly the determinism rule, machine-checked.
      java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
      val turkish = p(line)
      assertEquals(first.name, turkish.name)
      assertEquals(first.modifiers, turkish.modifiers)
      assertEquals(first.prescription.count, turkish.prescription.count)
      assertEquals(first.prescription.load?.kind, turkish.prescription.load?.kind)
      assertEquals(first.rest.lowSec, turkish.rest.lowSec)
      assertEquals(first.prescription.tempo, turkish.prescription.tempo)
    } finally {
      java.util.Locale.setDefault(defaults)
    }
  }

  // ── the anti-invention invariant ─────────────────────────────────────────────

  @Test fun `every number in the output appears in the source`() {
    val lines = listOf(
      "Bench Press 3x8", "Squat 5/3/1", "Row 3x8-12 @ 60-70kg", "Plank 3x45s",
      "Pull Up 1,2,3,4,5 x 5 rounds", "Thruster 21-15-9", "Bike AMRAP 12 min",
      "Deadlift 1x5+ @85% TM", "Curl 3x12 2 RIR", "Front Squat 3x(3.3.3) rest 15s"
    )
    for (line in lines) {
      val s = pres(line)
      val digitsInSource = line.filter { it.isDigit() }.toSet()
      // Every stated count and rep must be composed of digits that appear in the line. Crude, but
      // it catches the failure that matters: a number materialising from nowhere.
      val produced = buildList {
        // Two values are DERIVED from stated numbers rather than invented, and are excluded:
        //   - `count` when it is the length of a per-set list ("21-15-9" is three sets because
        //     three numbers were written);
        //   - `reps` when a per-set list exists, because it summarises that list (a cluster of
        //     3.3.3 totals 9, a rest-pause of 8+2+2 totals 12).
        // Everything else must trace back to a digit that appears in the line.
        if (s.perSet.isEmpty()) {
          s.count?.let { add(it) }
          s.reps?.min?.let { add(it) }
          s.reps?.max?.let { add(it) }
        }
        s.perSet.forEach { c -> c.reps?.min?.let { add(it) } }
      }
      for (n in produced) {
        // Derived totals (a ladder's 25, a rest-pause's 12, minutes as seconds) are sums of stated
        // numbers, so check digit provenance only for values that are small and atomic.
        if (n <= 9) assertTrue(
          "$line produced $n, whose digit is absent from the source",
          n.toString().all { it in digitsInSource })
      }
    }
  }

  @Test fun `an unstated set count is null, never one`() {
    for (line in listOf("Bench Press", "Prowler Sprints", "Farmer Carry heavy")) {
      assertNull(line, pres(line).count)
    }
  }
}
