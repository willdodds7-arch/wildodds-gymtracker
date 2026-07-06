package com.wildodds.gymtracker.data.parser

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Covers the no-hard-cap fix for the Program Index, plus a health check that the real shipped
 * catalogue still parses cleanly. Runs under Robolectric (parser uses android.util.Xml).
 */
@RunWith(RobolectricTestRunner::class)
class DefaultProgramXlsxParserTest {

  private val parser = DefaultProgramXlsxParser(ApplicationProvider.getApplicationContext())

  // Mirrors the cycles in make_catalogue_fixture.py.
  private val goals = listOf("Hypertrophy", "Powerlifting", "Powerbuilding", "Strength",
  "Athletic", "GPP", "Conditioning", "Bodyweight")
  private val intensities = listOf("RPE", "%1RM", "RPE/%1RM", "Linear", "")
  private val expectedCategory = mapOf(
  "Hypertrophy" to "Hypertrophy", "Powerlifting" to "Powerlifting",
  "Powerbuilding" to "Powerbuilding", "Strength" to "Strength",
  "Athletic" to "GPP", "GPP" to "GPP", "Conditioning" to "Conditioning",
  "Bodyweight" to "GPP"
  )

  @Test
  fun allSixtyFiveIndexedProgramsResolveCategoryAndTrackingFromIndex() {
  val input = javaClass.getResourceAsStream("/fixtures/catalogue_index65.xlsx")
  assertNotNull("catalogue fixture missing", input)
  val programs = input!!.use { parser.parseStream(it) }

  assertEquals("all 65 indexed programs must parse (no hard cap)", 65, programs.size)

  val byName = programs.associateBy { it.name }
  for (i in 1..65) {
  val prog = byName["Prog$i"]
  assertNotNull("Prog$i missing — index cap dropped it", prog)
  val goal = goals[(i - 1) % goals.size]
  val intensity = intensities[(i - 1) % intensities.size]

  // Category comes from the index Goal column — for EVERY program, including #61-65.
  assertEquals("category for Prog$i", expectedCategory[goal], prog!!.category)

  // The program sheets have no RPE/%1RM cells, so tracking can only come from the index.
  assertEquals("trackRpe for Prog$i ($intensity)", intensity.contains("RPE"), prog.trackRpe)
  assertEquals("trackOneRm for Prog$i ($intensity)", intensity.contains("%1RM"), prog.trackOneRm)
  }
  }

  @Test
  fun toolAppendedProgramParsesCleanly() {
  // Fixture produced by: python tools/add_program.py tools/example_program_spec.json \
  //   --workbook .../catalogue_index65.xlsx --output .../catalogue_appended66.xlsx
  // Proves a program written by the authoring tool re-parses through the real parser.
  val input = javaClass.getResourceAsStream("/fixtures/catalogue_appended66.xlsx")
  assertNotNull("appended-catalogue fixture missing", input)
  val programs = input!!.use { parser.parseStream(it) }

  assertEquals(66, programs.size)
  val added = programs.firstOrNull { it.name == "Example Upper/Lower Hypertrophy" }
  assertNotNull("the appended program did not parse", added)
  assertEquals("Hypertrophy", added!!.category)       // from the appended index row's Goal
  assertTrue("intensity RPE should track RPE", added.trackRpe)
  assertEquals(2, added.sessions.size)                // two days in the spec
  }

  @Test
  fun shippedCatalogueParsesCleanly() {
  // The bundled catalogue was emptied for this release (programs return next update). It must still
  // parse without error; any programs present must be well-formed.
  val programs = parser.parse()  // reads the real bundled default_programs.xlsx
  programs.forEach { p ->
  assertTrue("program has no name", p.name.isNotBlank())
  assertTrue("program ${p.name} has no sessions", p.sessions.isNotEmpty())
  }
  }
}
