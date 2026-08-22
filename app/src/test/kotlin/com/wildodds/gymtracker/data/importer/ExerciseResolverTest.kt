package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex
import com.wildodds.gymtracker.data.importer.resolve.Fuzzy
import com.wildodds.gymtracker.data.importer.resolve.Metaphone
import com.wildodds.gymtracker.data.importer.resolve.Normalise
import com.wildodds.gymtracker.data.importer.resolve.SemanticGuards
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercise resolution: reach, and the guards that keep the reach honest.
 *
 * The asymmetry that shapes every threshold in here: an unresolved name costs the user one
 * confirmation tap, while a confidently WRONG match writes a different exercise into their program
 * and they may never notice. So the tests come in two halves — how much more it now resolves, and
 * the near-misses it must refuse to resolve automatically.
 */
class ExerciseResolverTest {

  private fun r(name: String) = ExerciseResolver.resolve(name)

  private fun resolved(name: String) = r(name).resolvedName

  // ── the index ────────────────────────────────────────────────────────────────

  @Test fun `the bundled index is large and well-formed`() {
    val e = ExerciseIndex.entries
    assertTrue("expected a real library, got ${e.size}", e.size >= 800)
    assertTrue("every entry has a canonical name", e.all { it.canonical.isNotBlank() })
    assertTrue("every entry has a movement pattern", e.all { it.pattern.isNotBlank() })
    assertTrue("every entry has a primary muscle", e.all { it.primaryMuscle.isNotBlank() })
    assertTrue("every entry has equipment", e.all { it.equipment.isNotBlank() })
  }

  @Test fun `canonical names are unique after normalisation`() {
    val keys = ExerciseIndex.entries.map { it.key }
    assertEquals("duplicate canonical names make resolution order-dependent",
      keys.size, keys.distinct().size)
  }

  @Test fun `no alias shadows a different canonical name`() {
    for ((alias, entry) in ExerciseIndex.byAlias) {
      val canonical = ExerciseIndex.byKey[alias]
      assertTrue("alias '$alias' shadows canonical '${canonical?.canonical}'",
        canonical == null || canonical.canonical == entry.canonical)
    }
  }

  // ── the miss the spec named ──────────────────────────────────────────────────

  @Test fun `Push Press resolves`() {
    val ref = r("Push Press")
    assertEquals("Push Press", ref.resolvedName)
    assertEquals(ResolutionStatus.EXACT, ref.status)
  }

  // ── layered matching ─────────────────────────────────────────────────────────

  @Test fun `exact names resolve exactly`() {
    for (n in listOf("Back Squat", "Romanian Deadlift", "Lat Pulldown", "Face Pull", "Hip Thrust")) {
      assertEquals(n, ResolutionStatus.EXACT, r(n).status)
    }
  }

  @Test fun `abbreviations expand`() {
    assertEquals("Romanian Deadlift", resolved("RDL"))
    assertEquals("Overhead Press", resolved("OHP"))
    assertEquals("Close Grip Bench Press", resolved("CGBP"))
    assertEquals("Bulgarian Split Squat", resolved("BSS"))
    assertEquals("Glute Ham Raise", resolved("GHR"))
    assertEquals("Barbell Row", resolved("BOR"))
    assertEquals("Toes to Bar", resolved("T2B"))
  }

  @Test fun `token order and punctuation do not matter`() {
    // All three are the same lift written three ways.
    val expected = resolved("Barbell Back Squat")
    assertEquals(expected, resolved("Back Squat (Barbell)"))
    assertEquals(expected, resolved("Squat, Back - BB"))
  }

  @Test fun `equipment qualifiers decompose`() {
    // The index has several plausible "incline dumbbell press" families, so assert the qualifiers
    // survived rather than one exact string — losing either one is the bug.
    val out = resolved("incline DB press")
    assertTrue("lost the incline: $out", out.contains("Incline", true))
    assertTrue("lost the dumbbell: $out", out.contains("Dumbbell", true))
  }

  @Test fun `an equipment word is never explained away against different equipment`() {
    // "incline DB press" must not resolve to a BARBELL incline press just because "dumbbell" is an
    // equipment word. That is a silent change of implement.
    val e = ExerciseResolver.metadataFor(resolved("incline DB press"))
    assertEquals("DUMBBELL", e?.equipment)
  }

  @Test fun `plurals, possessives and tense are normalised away`() {
    assertEquals(resolved("Barbell Curl"), resolved("Barbell Curls"))
    assertEquals(resolved("Farmer Carry"), resolved("Farmer's Carry"))
    assertEquals(resolved("Dip"), resolved("Dips"))
  }

  @Test fun `typos are tolerated in proportion to word length`() {
    assertEquals("Bench Press", resolved("Bnech Press"))          // transposition
    assertEquals("Romanian Deadlift", resolved("Roumanian Deadlift"))
    assertEquals("Bulgarian Split Squat", resolved("Bulgarian Split Squad"))
  }

  @Test fun `phonetic spelling resolves`() {
    assertTrue(Metaphone.sounds("romanian", "roumanian"))
    assertTrue(Metaphone.sounds("fly", "flye"))
    assertTrue(Metaphone.sounds("kroc", "krok"))
    assertTrue("different sounds must not collide", !Metaphone.sounds("press", "pull"))
    assertTrue("nor these", !Metaphone.sounds("squat", "split"))
  }

  @Test fun `a learned alias beats everything`() {
    val ref = ExerciseResolver.resolve("Coach's Special") { "Back Squat" }
    assertEquals(ResolutionStatus.ALIAS, ref.status)
    assertEquals("Back Squat", ref.resolvedName)
    assertEquals("learned_alias", ref.via)
  }

  // ── the guards ───────────────────────────────────────────────────────────────

  @Test fun `a typo fix can never flip front squat to back squat`() {
    val ref = r("Front Squat")
    assertEquals("Front Squat", ref.resolvedName)
    assertNotEquals("Back Squat", ref.resolvedName)
  }

  @Test fun `every guarded distinction is refused as an automatic match`() {
    val pairs = listOf(
      setOf("front", "squat") to setOf("back", "squat"),
      setOf("incline", "press") to setOf("decline", "press"),
      setOf("pullup") to setOf("pulldown"),
      setOf("seated", "press") to setOf("standing", "press"),
      setOf("high", "bar", "squat") to setOf("low", "bar", "squat"),
      setOf("close", "grip") to setOf("wide", "grip"),
      setOf("single", "leg") to setOf("double", "leg"),
      setOf("romanian", "deadlift") to setOf("sumo", "deadlift")
    )
    for ((a, b) in pairs) {
      assertTrue("$a and $b must be blocked from matching",
        SemanticGuards.blockedBy(a, b) != null)
    }
  }

  @Test fun `a guard does not block when only one side asserts anything`() {
    // "Squat" versus "Front Squat" is a generalisation, not a contradiction.
    assertEquals(null, SemanticGuards.blockedBy(setOf("squat"), setOf("front", "squat")))
  }

  @Test fun `plain Deadlift never silently becomes Romanian Deadlift`() {
    val ref = r("Deadlift")
    assertEquals("Deadlift", ref.resolvedName)
  }

  @Test fun `a blocked near-match is offered as a candidate rather than applied`() {
    // A misspelling that lands closest to the wrong side of a guard must be INFERRED, with the
    // candidate visible, so the review screen asks instead of assuming.
    val ref = r("Frnot Squat")
    assertTrue("expected a candidate list, got ${ref.candidates}", ref.candidates.isNotEmpty())
    assertTrue("a guarded match is never EXACT", ref.status != ResolutionStatus.EXACT)
  }

  // ── candidates and provenance ────────────────────────────────────────────────

  @Test fun `anything below EXACT carries ranked candidates`() {
    val ref = r("Incline Dumbell Bench")   // deliberate misspelling
    assertTrue("no candidates offered", ref.candidates.isNotEmpty())
    assertTrue("at most three", ref.candidates.size <= 3)
    val scores = ref.candidates.map { it.score }
    assertEquals("candidates must be ranked", scores.sortedDescending(), scores)
  }

  @Test fun `every result records which layer produced it`() {
    assertEquals("exact", r("Back Squat").via)
    // "RDL" expands during normalisation, so it lands as an exact hit rather than an alias one —
    // which is the better outcome, and the `via` records honestly which layer got there.
    assertEquals("exact", r("RDL").via)
    assertEquals("bundled_alias", r("bench").via)
    assertTrue(r("Bnech Press").via.isNotBlank())
  }

  @Test fun `an unknown name is UNRESOLVED rather than forced onto something`() {
    val ref = r("Zercher Yoke Carry Medley Thing")
    assertTrue("expected a low-confidence answer, got ${ref.status} ${ref.resolvedName}",
      ref.status == ResolutionStatus.UNRESOLVED || ref.status == ResolutionStatus.INFERRED)
    assertTrue(ref.confidence <= 0.6)
    assertEquals("the original is kept forever", "Zercher Yoke Carry Medley Thing", ref.original)
  }

  @Test fun `metadata is available for the custom-exercise path`() {
    val m = ExerciseResolver.metadataFor("Romanian Deadlift")!!
    assertEquals("HINGE", m.pattern)
    assertEquals("HAMSTRINGS", m.primaryMuscle)
    assertEquals("BARBELL", m.equipment)
  }

  // ── determinism and cost ─────────────────────────────────────────────────────

  @Test fun `resolution is deterministic and locale independent`() {
    val names = listOf("RDL", "incline DB press", "Bnech Press", "Squat, Back - BB", "Pull-Up")
    val english = names.map { resolved(it) }
    val defaults = java.util.Locale.getDefault()
    try {
      java.util.Locale.setDefault(java.util.Locale("tr", "TR"))
      assertEquals(english, names.map { resolved(it) })
    } finally {
      java.util.Locale.setDefault(defaults)
    }
    repeat(3) { assertEquals(english, names.map { resolved(it) }) }
  }

  @Test fun `bounded edit distance abandons hopeless comparisons`() {
    assertEquals(0, Fuzzy.damerauLevenshtein("squat", "squat", 2))
    assertEquals(1, Fuzzy.damerauLevenshtein("squat", "squta", 2))   // transposition is one edit
    assertTrue(Fuzzy.damerauLevenshtein("squat", "deadlift", 2) > 2)
    assertEquals("three-letter words get no budget at all", 0, Fuzzy.budgetFor(3))
  }

  @Test fun `normalisation is idempotent`() {
    for (n in listOf("Farmer's Walk", "Pull-Up", "Squat, Back - BB", "  Bench  Press  ")) {
      assertEquals(Normalise.key(n), Normalise.key(Normalise.key(n)))
    }
  }

  // ── the number that matters ──────────────────────────────────────────────────

  /**
   * The hit rate on a fixed set of names taken from real programs, written the way people write
   * them. It is asserted rather than merely printed so a regression fails CI, and printed so the
   * corpus scoreboard has a comparable figure.
   *
   * The pre-v2 resolver — normalise, expand a 14-entry abbreviation map, look up a 63-entry muscle
   * map — resolved 9 of these 60.
   */
  @Test fun `hit rate on real-world names`() {
    val names = listOf(
      "Back Squat", "Front Squat", "Bench Press", "Incline Bench Press", "Overhead Press",
      "Push Press", "Deadlift", "Romanian Deadlift", "Sumo Deadlift", "Barbell Row",
      "Pendlay Row", "Dumbbell Row", "Lat Pulldown", "Pull Up", "Chin Up",
      "Dip", "Face Pull", "Lateral Raise", "Rear Delt Fly", "Hammer Curl",
      "Barbell Curl", "Preacher Curl", "Skull Crusher", "Tricep Pushdown", "Close Grip Bench Press",
      "Hip Thrust", "Glute Bridge", "Leg Press", "Leg Extension", "Leg Curl",
      "Calf Raise", "Bulgarian Split Squat", "Walking Lunge", "Step Up", "Goblet Squat",
      "Hack Squat", "Good Morning", "Glute Ham Raise", "Nordic Curl", "Back Extension",
      "Plank", "Side Plank", "Hanging Leg Raise", "Ab Wheel", "Cable Crunch",
      "Farmer Carry", "Kettlebell Swing", "Power Clean", "Snatch", "Thruster",
      // …and the same movements as people actually type them.
      "RDL", "OHP", "BSS", "CGBP", "GHR",
      "incline DB press", "db row", "seated cable row", "Squat, Back - BB", "Pull-Ups"
    )
    val results = names.map { it to r(it) }
    val hits = results.count {
      it.second.status == ResolutionStatus.EXACT || it.second.status == ResolutionStatus.ALIAS
    }
    val rate = hits.toDouble() / names.size
    val misses = results.filter {
      it.second.status != ResolutionStatus.EXACT && it.second.status != ResolutionStatus.ALIAS
    }.map { "${it.first} -> ${it.second.resolvedName} (${it.second.status})" }

    println("EXERCISE RESOLUTION HIT RATE: $hits/${names.size} = ${(rate * 100).toInt()}%")
    if (misses.isNotEmpty()) println("  misses: ${misses.joinToString("; ")}")

    assertTrue("hit rate fell to ${(rate * 100).toInt()}% — misses: $misses", rate >= 0.90)
  }
}
