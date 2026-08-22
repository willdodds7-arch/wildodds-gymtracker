package com.wildodds.gymtracker.ui.builder

import com.wildodds.gymtracker.data.ExerciseLibrary
import com.wildodds.gymtracker.data.MuscleGroup
import com.wildodds.gymtracker.data.builder.ProgramMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the bundled templates against the two things emulator testing actually caught: a
 * description that contradicted the structure, and exercise names the library couldn't resolve
 * (which silently drop out of every volume total).
 */
class ProgramTemplatesTest {

  @Test
  fun `every template has a name and at least one training day`() {
    ProgramTemplates.all().forEach { t ->
      assertTrue("unnamed template", t.name.isNotBlank())
      assertTrue("${t.name} has no training days", ProgramMath.trainingDaysPerWeek(t) > 0)
    }
  }

  @Test
  fun `a description claiming N days matches the actual day count`() {
    // "Hypertrophy-focused 6-day split" shipped on a 3-day structure.
    val claim = Regex("(\\d+)[- ]day")
    ProgramTemplates.all().forEach { t ->
      val stated = claim.find(t.description)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
      assertEquals(
        "${t.name} says $stated days but has ${ProgramMath.trainingDaysPerWeek(t)}",
        stated, ProgramMath.trainingDaysPerWeek(t)
      )
    }
  }

  @Test
  fun `every template exercise resolves in the library`() {
    // An unresolved name contributes nothing to the volume panel, so a template shipping one
    // hands the user a program whose numbers are quietly wrong. "Rope Pushdown" was the culprit.
    val unresolved = ProgramTemplates.all().flatMap { t ->
      t.workouts.flatMap { w -> w.exercises }
        .map { it.name }
        .filter { ExerciseLibrary.lookup(it) == null }
        .map { "${t.name}: $it" }
    }.distinct()
    assertTrue("unresolved template exercises: $unresolved", unresolved.isEmpty())
  }

  @Test
  fun `every template exercise has a rep target`() {
    ProgramTemplates.all().forEach { t ->
      t.workouts.flatMap { it.exercises }.forEach { ex ->
        assertTrue("${t.name} / ${ex.name} has no rep target", !ex.hasNoRepTarget)
      }
    }
  }

  @Test
  fun `templates carry no warnings a user would see on load`() {
    ProgramTemplates.all().forEach { t ->
      assertTrue(
        "${t.name} loads with warnings: ${ProgramMath.warnings(t)}",
        ProgramMath.warnings(t).isEmpty()
      )
    }
  }

  @Test
  fun `push pull legs really is a six day split`() {
    val ppl = ProgramTemplates.all().first { it.name == "Push Pull Legs" }
    assertEquals(6, ProgramMath.trainingDaysPerWeek(ppl))
  }

  @Test
  fun `a rope pushdown resolves to triceps without the word tricep`() {
    val info = ExerciseLibrary.lookup("Rope Pushdown")
    assertTrue("Rope Pushdown should resolve", info != null)
    assertTrue(info!!.primary.contains(MuscleGroup.TRICEPS))
  }
}
