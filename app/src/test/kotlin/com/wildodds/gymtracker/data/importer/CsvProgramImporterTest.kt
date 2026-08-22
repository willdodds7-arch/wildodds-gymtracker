package com.wildodds.gymtracker.data.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pasted delimited text — the "I copied a block out of a spreadsheet" case.
 *
 * A `.csv` FILE never reaches here: it is extracted into a grid and read by the layout engine. This
 * path is for text arriving as text, and its headerless branch was the weak point — a table with no
 * headings had its cells joined back into one line, which named a movement and prescribed nothing.
 */
class CsvProgramImporterTest {

  private fun slots(raw: String) =
    CsvProgramImporter.import("Pasted", raw).weeks.flatMap { w -> w.sessions.flatMap { it.slots } }

  @Test fun `a headerless pasted table keeps its prescriptions`() {
    val slots = slots("Back Squat\t5\t5\nBench Press\t3\t8\nBarbell Row\t3\t10")
    assertEquals(listOf("Back Squat", "Bench Press", "Barbell Row"), slots.map { it.exercise.original })
    assertEquals(listOf(5, 3, 3), slots.map { it.sets.first().count })
    assertEquals(listOf(5, 8, 10), slots.map { it.sets.first().reps?.min })
  }

  @Test fun `a headerless pasted table reads its load column`() {
    val slots = slots("Back Squat\t5\t5\t100kg\nBench Press\t3\t8\t80kg")
    assertEquals(listOf(100.0, 80.0), slots.map { it.sets.first().load?.value })
  }

  @Test fun `a header row is still preferred when there is one`() {
    val slots = slots("Exercise,Sets,Reps\nBack Squat,5,5\nBench Press,3,8")
    assertEquals(listOf("Back Squat", "Bench Press"), slots.map { it.exercise.original })
    assertEquals(listOf(5, 3), slots.map { it.sets.first().count })
  }

  @Test fun `a single column paste is not treated as a table`() {
    // One column tells the role assignment nothing, so the line-join is still the right read - and
    // it is the read that gets the notation out of "Back Squat 5x5".
    val slots = slots("Back Squat 5x5\nBench Press 3x8\nBarbell Row 3x10")
    assertEquals(listOf(5, 3, 3), slots.map { it.sets.first().count })
  }

  @Test fun `per-set commas are not a table`() {
    // "Front Squat 6, 8, 10" splits into ["Front Squat 6", "8", "10"], which has the shape of a
    // three-column row and is one exercise with three sets. Read as a table it would become 6 sets
    // of 8 - a confident, wrong number - so a digit in the name cell rules the table read out.
    val slots = slots("Front Squat 6, 8, 10\nRomanian Deadlift 8, 10, 12")
    assertEquals(listOf("Front Squat", "Romanian Deadlift"), slots.map { it.exercise.original })
    assertEquals(listOf(3, 3), slots.map { it.sets.first().perSet.size })
  }

  @Test fun `a table with nothing but names is left to the line reader`() {
    // No second role to find, so the grid read would add nothing over the join.
    val slots = slots("Back Squat\nBench Press\nBarbell Row")
    assertEquals(3, slots.size)
    assertNull(slots.first().sets.firstOrNull()?.count)
  }

  @Test fun `an empty paste does not crash`() {
    assertTrue(CsvProgramImporter.import("Pasted", "").weeks.single().sessions.isEmpty())
  }
}
