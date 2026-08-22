package com.wildodds.gymtracker.data

import com.wildodds.gymtracker.data.importer.resolve.ExerciseIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The library is now a view over [ExerciseIndex] rather than a second, smaller answer to the same
 * question. These tests exist to keep it that way: the failure they guard against is someone adding
 * a convenience map back, and the two drifting apart again without anything going red.
 */
class ExerciseLibraryTest {

  @Test fun `every movement in the index is in the library`() {
    assertEquals(ExerciseIndex.entries.size, ExerciseLibrary.LIBRARY.size)
    assertTrue("the index should carry the whole catalogue, not a sample",
      ExerciseLibrary.LIBRARY.size > 900)
  }

  @Test fun `every muscle name in the index maps to a muscle group`() {
    // A typo in the generated TSV would otherwise be dropped silently and quietly shrink a
    // movement's volume contribution.
    val known = MuscleGroup.entries.map { it.name }.toSet()
    val unknown = ExerciseIndex.entries
      .flatMap { it.primaryMuscles + it.secondaryMuscles }
      .distinct()
      .filter { it !in known }
    assertEquals("muscle names with no MuscleGroup", emptyList<String>(), unknown)
  }

  @Test fun `every movement has at least one primary muscle`() {
    val empty = ExerciseIndex.entries.filter { it.primaryMuscles.isEmpty() }.map { it.canonical }
    assertEquals(emptyList<String>(), empty)
  }

  @Test fun `a compound carries its helpers and an isolation does not`() {
    val squat = ExerciseLibrary.lookup("Back Squat")!!
    assertEquals(listOf(MuscleGroup.QUADS, MuscleGroup.GLUTES), squat.primary)
    assertTrue(MuscleGroup.HAMSTRINGS in squat.secondary)

    // The bug this whole change exists to prevent: a leg extension is (SQUAT, QUADS) too, and a
    // pattern-derived table would hand it the squat's glutes and hamstrings.
    val extension = ExerciseLibrary.lookup("Leg Extension")!!
    assertEquals(listOf(MuscleGroup.QUADS), extension.primary)
    assertEquals(emptyList<MuscleGroup>(), extension.secondary)
  }

  @Test fun `a muscle never appears as both primary and secondary`() {
    val overlapping = ExerciseIndex.entries
      .filter { e -> e.secondaryMuscles.any { it in e.primaryMuscles } }
      .map { it.canonical }
    assertEquals("double-counted muscles", emptyList<String>(), overlapping)
  }

  @Test fun `the main mover is still the first primary`() {
    // Consumers that want one muscle take primaryMuscles[0]; that value predates the column growing
    // and must not move.
    assertEquals("QUADS", ExerciseIndex.find("Back Squat")!!.primaryMuscle)
    assertEquals("CHEST", ExerciseIndex.find("Bench Press")!!.primaryMuscle)
    assertEquals("HAMSTRINGS", ExerciseIndex.find("Deadlift")!!.primaryMuscle)
  }

  @Test fun `the names people actually say resolve`() {
    // Each of these was known to the old 63-entry map, unknown to the index, and appears in the
    // app's own program templates.
    for (name in listOf(
      "Incline Dumbbell Press", "Dumbbell Shoulder Press", "Cable Fly", "Bodyweight Squat"
    )) {
      assertNotNull("$name should resolve", ExerciseLibrary.lookup(name))
    }
  }

  @Test fun `a name inside a longer line resolves to the most specific movement`() {
    // The segmenter asks this question of whole lines, so the match has to survive the prescription
    // sitting next to it — and "Incline Bench Press" must beat "Bench Press".
    assertEquals("Incline Bench Press", ExerciseIndex.mentionedIn("3x8 Incline Bench Press @ 60kg")?.canonical)
    assertEquals("Bench Press", ExerciseIndex.mentionedIn("Bench Press 5x5")?.canonical)
  }

  @Test fun `a word that merely contains a movement name is not a match`() {
    // Substring matching answered yes here; whole-word matching does not.
    assertNull(ExerciseIndex.mentionedIn("Benchmarking"))
    assertNull(ExerciseLibrary.lookup("Benchmarking"))
  }

  @Test fun `an unknown movement is null rather than a guess`() {
    assertNull(ExerciseLibrary.lookup("Quantum Flange Pull"))
    assertNull(ExerciseLibrary.lookup(""))
    assertNull(ExerciseLibrary.lookup("   "))
  }

  @Test fun `a stretch trains nothing rather than being omitted`() {
    val stretch = ExerciseIndex.find("Couch Stretch")!!
    assertEquals(emptyList<String>(), stretch.secondaryMuscles)
    assertFalse(stretch.primaryMuscles.isEmpty())
  }
}
