package com.wildodds.gymtracker.data.builder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Evolving sets. The properties that matter to a user: the working-set count they asked for is the
 * count they get, the ladder points the direction its name promises, and a percentage resolves to a
 * weight you can actually load onto a bar.
 */
class SetShapeTest {

  private fun List<PlanSet>.working() = filter { it.type == SetType.WORKING }
  private fun List<PlanSet>.percents() = mapNotNull { it.loadPercent }

  // ── Counts ─────────────────────────────────────────────────────────────────

  @Test
  fun `straight sets gives exactly the working sets asked for`() {
    val sets = SetShape.STRAIGHT.build(workingSets = 3, reps = "8-12")
    assertEquals(3, sets.size)
    assertTrue(sets.all { it.type == SetType.WORKING })
    assertTrue(sets.all { it.reps == "8-12" })
  }

  @Test
  fun `every shape honours the requested working set count`() {
    SetShape.entries.forEach { shape ->
      val working = shape.build(workingSets = 4, reps = "8").working().size
      // TOP_SET_BACKOFF splits its working sets into one top set plus back-offs, and DROP_SET adds
      // drops on top — both still expose exactly four WORKING rows.
      assertEquals("${shape.name} produced $working working sets", 4, working)
    }
  }

  @Test
  fun `warm-up ramp adds warm-ups on top of the working sets`() {
    val sets = SetShape.WARMUP_RAMP.build(workingSets = 3, reps = "8")
    assertEquals(2, sets.count { it.type == SetType.WARMUP })
    assertEquals(3, sets.working().size)
    // Warm-ups come first, always.
    assertEquals(SetType.WARMUP, sets.first().type)
  }

  @Test
  fun `drop sets append drops after the working sets`() {
    val sets = SetShape.DROP_SET.build(workingSets = 3, reps = "10")
    assertEquals(2, sets.count { it.type == SetType.DROP })
    assertEquals(SetType.DROP, sets.last().type)
    // A drop follows immediately — resting through it defeats the purpose.
    assertTrue(sets.filter { it.type == SetType.DROP }.all { it.restSeconds == 0 })
  }

  // ── Direction ──────────────────────────────────────────────────────────────

  @Test
  fun `ascending gets heavier every set and tops out at 100`() {
    val p = SetShape.ASCENDING.build(4, "5").working().percents()
    assertTrue("not ascending: $p", p.zipWithNext().all { (a, b) -> b > a })
    assertEquals(100, p.last())
  }

  @Test
  fun `reverse pyramid starts heaviest`() {
    val p = SetShape.REVERSE_PYRAMID.build(4, "6").working().percents()
    assertEquals(100, p.first())
    assertTrue("not descending: $p", p.zipWithNext().all { (a, b) -> b < a })
  }

  @Test
  fun `pyramid peaks in the middle`() {
    val p = SetShape.PYRAMID.build(5, "8").working().percents()
    val peak = p.indexOf(p.max())
    assertTrue("peak at $peak in $p", peak > 0 && peak < p.lastIndex)
  }

  @Test
  fun `top set is heaviest and back-offs are lighter`() {
    val working = SetShape.TOP_SET_BACKOFF.build(4, "5").working()
    val p = working.percents()
    assertEquals(100, p.first())
    assertTrue("back-offs should be lighter: $p", p.drop(1).all { it < 100 })
  }

  @Test
  fun `straight sets are all the same weight`() {
    val p = SetShape.STRAIGHT.build(5, "10").percents()
    assertEquals(1, p.distinct().size)
  }

  @Test
  fun `no shape ever prescribes more than the top set`() {
    SetShape.entries.forEach { shape ->
      val over = shape.build(4, "8").mapNotNull { it.loadPercent }.filter { it > 100 }
      assertTrue("${shape.name} prescribed $over", over.isEmpty())
    }
  }

  // ── Resolving percentages to real weights ──────────────────────────────────

  @Test
  fun `a percentage resolves against the top load`() {
    assertEquals(50f, SetShape.resolveLoad(100f, 50)!!, 0.01f)
    assertEquals(80f, SetShape.resolveLoad(100f, 80)!!, 0.01f)
  }

  @Test
  fun `resolved loads round to something you can actually load`() {
    // 62.4 kg is not a weight; 62.5 is.
    assertEquals(62.5f, SetShape.resolveLoad(83f, 75)!!, 0.01f)
    assertEquals(0f, SetShape.resolveLoad(100f, 1)!! % 2.5f, 0.01f)
  }

  @Test
  fun `an unknown top load resolves to nothing rather than a guess`() {
    assertNull(SetShape.resolveLoad(null, 80))
    assertNull(SetShape.resolveLoad(0f, 80))
    assertNull(SetShape.resolveLoad(100f, null))
  }

  @Test
  fun `describeLoad prefers a literal weight, then a resolved one, then the percentage`() {
    assertEquals("60 kg", SetShape.describeLoad(null, null, literal = "60 kg"))
    assertEquals("80 kg", SetShape.describeLoad(100f, 80, literal = ""))
    assertEquals("80%", SetShape.describeLoad(null, 80, literal = ""))
    assertEquals("", SetShape.describeLoad(null, null, literal = ""))
  }

  // ── Top load, and the "evolving" part ──────────────────────────────────────

  @Test
  fun `the top load is read off the hundred percent set`() {
    val ex = PlanExercise(name = "Bench Press", sets = SetShape.WARMUP_RAMP.build(3, "8"))
    assertNull("nothing typed yet", ex.topLoad)

    // Typing the working weight once is all it takes.
    val filled = ex.copy(sets = ex.sets.map {
      if (it.loadPercent == 100) it.copy(load = "100") else it
    })
    assertEquals(100f, filled.topLoad!!, 0.01f)
    // ...and every other row now resolves off it.
    val warmup = filled.sets.first()
    assertEquals(50f, SetShape.resolveLoad(filled.topLoad, warmup.loadPercent)!!, 0.01f)
  }

  @Test
  fun `changing the top load moves the whole ladder`() {
    val ex = PlanExercise(sets = SetShape.WARMUP_RAMP.build(3, "8")).let { e ->
      e.copy(sets = e.sets.map { if (it.loadPercent == 100) it.copy(load = "100") else it })
    }
    val heavier = ex.copy(sets = ex.sets.map { if (it.loadPercent == 100) it.copy(load = "120") else it })

    assertEquals(50f, SetShape.resolveLoad(ex.topLoad, 50)!!, 0.01f)
    assertEquals(60f, SetShape.resolveLoad(heavier.topLoad, 50)!!, 0.01f)
  }

  @Test
  fun `a straight prescription is not evolving but a ramp is`() {
    assertTrue(!PlanExercise(sets = SetShape.STRAIGHT.build(3, "8")).isEvolving)
    assertTrue(PlanExercise(sets = SetShape.WARMUP_RAMP.build(3, "8")).isEvolving)
    assertTrue(PlanExercise(sets = SetShape.ASCENDING.build(3, "8")).isEvolving)
  }

  // ── Detection (so the picker shows what you already have selected) ─────────

  @Test
  fun `detect round-trips every shape it generates`() {
    listOf(
      SetShape.STRAIGHT, SetShape.WARMUP_RAMP, SetShape.ASCENDING,
      SetShape.REVERSE_PYRAMID, SetShape.TOP_SET_BACKOFF, SetShape.DROP_SET
    ).forEach { shape ->
      assertEquals(shape, SetShape.detect(shape.build(4, "8")))
    }
  }

  @Test
  fun `detect finds a pyramid`() {
    assertEquals(SetShape.PYRAMID, SetShape.detect(SetShape.PYRAMID.build(5, "8")))
  }

  @Test
  fun `detect treats an empty or hand-typed exercise as straight`() {
    assertEquals(SetShape.STRAIGHT, SetShape.detect(emptyList()))
    assertEquals(SetShape.STRAIGHT, SetShape.detect(listOf(PlanSet(reps = "8"), PlanSet(reps = "8"))))
  }

  // ── Guards ────────────────────────────────────────────────────────────────

  @Test
  fun `absurd set counts are clamped rather than producing a huge list`() {
    assertTrue(SetShape.STRAIGHT.build(999, "8").size <= 12)
    assertTrue(SetShape.STRAIGHT.build(0, "8").isNotEmpty())
  }

  @Test
  fun `every shape explains itself in plain language`() {
    SetShape.entries.forEach {
      assertTrue("${it.name} has no explanation", it.explanation.length > 15)
      assertTrue("${it.name} should end in a full stop", it.explanation.trim().endsWith("."))
    }
  }

  @Test
  fun `warm-ups use easy reps not the working target`() {
    val sets = SetShape.WARMUP_RAMP.build(3, reps = "3")
    val warmups = sets.filter { it.type == SetType.WARMUP }
    // Nobody warms up with triples.
    assertTrue(warmups.none { it.reps == "3" })
  }
}
