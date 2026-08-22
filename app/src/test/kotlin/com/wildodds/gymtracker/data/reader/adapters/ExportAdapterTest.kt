package com.wildodds.gymtracker.data.reader.adapters

import com.wildodds.gymtracker.data.importer.LoadKind
import com.wildodds.gymtracker.data.reader.ProgramReader
import com.wildodds.gymtracker.data.reader.ingest.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One fixture per adapter, plus the two rules that keep adapters from doing harm:
 *
 *  1. **A near miss falls through.** A file that is nearly a Strong export but not quite must go to
 *     the general pipeline, which reads it fine anyway. An adapter is an accuracy upgrade, never a
 *     gate — a signature that grabs too eagerly is worse than no adapter at all.
 *  2. **Sets are folded honestly.** Five identical logged sets are "5 x 8". Five different ones are
 *     five different sets, kept as five — averaging them would put a number in the program that
 *     appears nowhere in the source.
 *
 * The fixtures are authored from each app's published column names. For Wild Odds' own two formats
 * that is exact, because this codebase writes them. For the third-party ones it proves the adapter
 * reads that SHAPE, not that the shape is current in every version of that app — a distinction
 * recorded in `docs/reader/04-gaps.md` rather than left for someone to discover.
 */
class ExportAdapterTest {

  private val reader = ProgramReader()

  private fun read(csv: String, name: String) =
    reader.read(csv.toByteArray(Charsets.UTF_8), name)

  // ── Strong ───────────────────────────────────────────────────────────────────

  private val strong = """
    Date,Workout Name,Duration,Exercise Name,Set Order,Weight,Reps,Distance,Seconds,Notes,Workout Notes,RPE
    2026-03-02 18:04:00,Push A,1h 5m,Bench Press,1,80,8,0,0,,,8
    2026-03-02 18:04:00,Push A,1h 5m,Bench Press,2,80,8,0,0,,,8
    2026-03-02 18:04:00,Push A,1h 5m,Bench Press,3,80,8,0,0,,,8
    2026-03-02 18:04:00,Push A,1h 5m,Overhead Press,1,45,10,0,0,elbows in,,7
    2026-03-04 18:00:00,Pull A,58m,Barbell Row,1,70,10,0,0,,,7
    2026-03-04 18:00:00,Pull A,58m,Barbell Row,2,70,8,0,0,,,8
  """.trimIndent()

  @Test fun `a Strong export is read natively`() {
    val r = read(strong, "strong.csv")
    assertEquals("strong", r.route)
    assertEquals(SourceKind.DELIMITED, r.kind)
    val sessions = r.importResult.program.sessions
    assertEquals(2, sessions.size)
    assertTrue(sessions[0].name.contains("Push A"))
    assertEquals(listOf("Bench Press", "Overhead Press"), sessions[0].exercises.map { it.name })
  }

  @Test fun `identical logged sets fold to a count, differing ones stay per-set`() {
    val ex = read(strong, "strong.csv").importResult.program.sessions
      .flatMap { it.exercises }.associateBy { it.name }

    val bench = ex.getValue("Bench Press")
    assertEquals(3, bench.setsStated)
    assertTrue("three identical sets are '3 x 8', not three rows", bench.perSet.isEmpty())
    assertEquals("8", bench.repsTarget)

    val row = ex.getValue("Barbell Row")
    assertEquals(2, row.setsStated)
    assertEquals("10 then 8 are two different things", listOf("10", "8"), row.perSet.map { it.reps })
  }

  @Test fun `Strong weights are absolute but their unit is not assumed`() {
    val bench = read(strong, "strong.csv").importResult.program.sessions
      .flatMap { it.exercises }.first { it.name == "Bench Press" }
    assertEquals("ABSOLUTE", bench.loadKind)
    assertEquals(80.0, bench.loadValue!!, 0.001)
    // Strong exports in whatever unit the user picked, without saying which. Assuming kg would
    // silently halve every American import, so the unit stays unknown and review asks.
    assertNull(bench.loadUnit)
  }

  @Test fun `an export is flagged as an export so the user knows where the numbers came from`() {
    val flags = read(strong, "strong.csv").importResult.flags
    assertTrue(flags.any { it.message.contains("Strong export") })
  }

  @Test fun `a near-miss Strong file falls through to the general pipeline instead of being forced`() {
    val notQuite = """
      Date,Workout,Exercise,Weight,Reps
      2026-03-02,Push,Bench Press,80,8
      2026-03-02,Push,Overhead Press,45,10
    """.trimIndent()
    val r = read(notQuite, "maybe-strong.csv")
    assertTrue("must not claim to be a Strong export", r.route != "strong")
    assertTrue("but must still import", r.importResult.exerciseCount >= 2)
  }

  // ── Hevy ─────────────────────────────────────────────────────────────────────

  @Test fun `a Hevy export is read natively, in kg`() {
    val hevy = """
      title,start_time,end_time,description,exercise_title,superset_id,exercise_notes,set_index,set_type,weight_kg,reps,distance_km,duration_seconds,rpe
      Upper A,2026-03-02T18:00:00Z,2026-03-02T19:00:00Z,,Incline Bench Press,,,0,normal,60,10,,,7
      Upper A,2026-03-02T18:00:00Z,2026-03-02T19:00:00Z,,Incline Bench Press,,,1,normal,60,10,,,7
      Upper A,2026-03-02T18:00:00Z,2026-03-02T19:00:00Z,,Lat Pulldown,A,,0,normal,55,12,,,
    """.trimIndent()
    val r = read(hevy, "hevy_workouts.csv")
    assertEquals("hevy", r.route)
    val ex = r.importResult.program.sessions.single().exercises
    assertEquals(listOf("Incline Bench Press", "Lat Pulldown"), ex.map { it.name })
    assertEquals(2, ex[0].setsStated)
    assertEquals("KG", ex[0].loadUnit)
    assertEquals("A", ex[1].supersetGroup)
  }

  // ── FitNotes ─────────────────────────────────────────────────────────────────

  @Test fun `a FitNotes export reads its explicit weight unit`() {
    val fitnotes = """
      Date,Exercise,Category,Weight,Weight Unit,Reps,Distance,Distance Unit,Time,Comment
      2026-03-02,Barbell Squat,Legs,225,lbs,5,,,,felt heavy
      2026-03-02,Barbell Squat,Legs,225,lbs,5,,,,
      2026-03-02,Leg Press,Legs,180,lbs,12,,,,
    """.trimIndent()
    val r = read(fitnotes, "FitNotes_Export.csv")
    assertEquals("fitnotes", r.route)
    val squat = r.importResult.program.sessions.single().exercises.first()
    // The resolver canonicalises "Barbell Squat" to the movement it names. The VERBATIM source is
    // still kept on rawSourceLine, so nothing is lost by the rename.
    assertEquals("Back Squat", squat.name)
    assertTrue(squat.rawSourceLine!!.contains("Barbell Squat"))
    assertEquals("LB", squat.loadUnit)
    assertEquals(2, squat.setsStated)
    assertTrue(squat.notes.contains("felt heavy"))
  }

  // ── program exports ──────────────────────────────────────────────────────────

  @Test fun `a TrainHeroic program export keeps its weeks`() {
    val th = """
      Week,Day Title,Exercise,Sets,Reps,Load,Notes
      1,Lower A,Back Squat,5,5,75%,build slowly
      1,Lower A,Romanian Deadlift,3,8,,
      2,Lower A,Back Squat,5,3,85%,
    """.trimIndent()
    val r = read(th, "trainheroic-block.csv")
    assertEquals("trainheroic", r.route)
    val weeks = r.importResult.program.sessions.map { it.weekNumber }.distinct()
    assertEquals(listOf(1, 2), weeks)
    val squat = r.importResult.program.sessions.first().exercises.first()
    assertEquals("PCT_1RM", squat.loadKind)
    assertEquals(75.0, squat.loadValue!!, 0.001)
  }

  @Test fun `a Trainerize export reads its target columns`() {
    val tz = """
      Workout Name,Exercise Name,Target Sets,Target Reps,Rest Time,Notes
      Full Body A,Goblet Squat,3,12,60s,keep the chest up
      Full Body A,Push Up,3,15,45s,
    """.trimIndent()
    val r = read(tz, "trainerize.csv")
    assertEquals("trainerize", r.route)
    val ex = r.importResult.program.sessions.single().exercises
    assertEquals(2, ex.size)
    assertEquals(3, ex[0].setsStated)
    assertEquals("12", ex[0].repsTarget)
    assertEquals(60, ex[0].restSeconds)
  }

  @Test fun `an Everfit export is recognised by its title columns`() {
    val ef = """
      Workout Title,Exercise Title,Sets,Reps,Weight,Note
      Day 1 - Push,Dumbbell Bench Press,4,10,30,control the eccentric
      Day 1 - Push,Cable Fly,3,15,,
    """.trimIndent()
    val r = read(ef, "everfit-export.csv")
    assertEquals("everfit", r.route)
    assertEquals(2, r.importResult.exerciseCount)
  }

  @Test fun `a Boostcamp export is recognised by its full column set`() {
    val bc = """
      Week,Day,Exercise,Sets,Reps,Intensity
      1,Day 1,Bench Press,3,5,80%
      1,Day 1,Barbell Row,3,8,
    """.trimIndent()
    val r = read(bc, "boostcamp.csv")
    assertEquals("boostcamp", r.route)
    assertEquals(2, r.importResult.exerciseCount)
  }

  @Test fun `a rest day in a program export survives as a rest day`() {
    val th = """
      Week,Day Title,Exercise,Sets,Reps,Load,Notes
      1,Lower A,Back Squat,5,5,75%,
      1,Rest,-,,,,
      1,Upper A,Bench Press,5,5,75%,
    """.trimIndent()
    val sessions = read(th, "trainheroic-block.csv").importResult.program.sessions
    assertEquals(listOf("TRAINING", "REST", "TRAINING"), sessions.map { it.sessionType })
  }

  // ── Wild Odds' own formats ───────────────────────────────────────────────────

  @Test fun `Wild Odds own program JSON round-trips back in`() {
    val json = """
      {"schemaVersion":1,"kind":"wildodds.program","name":"Winter Block","durationWeeks":6,
       "description":"Six weeks of upper/lower",
       "workouts":[
         {"name":"Upper A","kind":"TRAINING","exercises":[
            {"name":"Bench Press","notes":"tuck the elbows","sets":[
               {"type":"WORKING","reps":"5","rpe":"8","restSeconds":"180"},
               {"type":"WORKING","reps":"5","rpe":"8","restSeconds":"180"},
               {"type":"WORKING","reps":"5","rpe":"8","restSeconds":"180"}]}]},
         {"name":"Rest","kind":"REST","exercises":[]}]}
    """.trimIndent()
    val r = read(json, "winter-block.json")
    assertEquals("wildodds-json", r.route)
    assertEquals("Winter Block", r.importResult.program.name)
    assertEquals(6, r.importResult.program.repeatWeeks)
    val sessions = r.importResult.program.sessions
    assertEquals(listOf("TRAINING", "REST"), sessions.map { it.sessionType })
    val bench = sessions.first().exercises.single()
    assertEquals(3, bench.setsStated)
    assertEquals("5", bench.repsTarget)
    assertEquals(180, bench.restSeconds)
    assertEquals("tuck the elbows", bench.notes)
  }

  @Test fun `a per-set Wild Odds export keeps its differing sets`() {
    val json = """
      {"kind":"wildodds.program","name":"Wave","durationWeeks":1,"workouts":[
        {"name":"Squat Day","kind":"TRAINING","exercises":[
          {"name":"Back Squat","sets":[
            {"reps":"5"},{"reps":"3"},{"reps":"1"}]}]}]}
    """.trimIndent()
    val squat = read(json, "wave.json").importResult.program.sessions.single().exercises.single()
    assertEquals(listOf("5", "3", "1"), squat.perSet.map { it.reps })
  }

  @Test fun `an adapter never runs on a document it does not match`() {
    val notes = "Shopping list\n- eggs\n- milk\n- oats\n"
    val doc = com.wildodds.gymtracker.data.reader.ingest.ExtractedDocument(text = notes)
    assertNull(ExportAdapters.parse(doc, "list.txt"))
  }

  @Test fun `every registered adapter has a unique id`() {
    val ids = ExportAdapters.ALL.map { it.id }
    assertEquals("adapter ids must be unique — they appear in flags and the corpus scoreboard",
      ids.size, ids.distinct().size)
    assertTrue("at least six named app exports are required", ids.size >= 6)
  }

  @Test fun `an unresolvable load kind is never invented`() {
    val strongNoWeight = """
      Date,Workout Name,Exercise Name,Set Order,Weight,Reps
      2026-03-02,Push,Push Up,1,0,20
    """.trimIndent()
    val ex = read(strongNoWeight, "strong.csv").importResult.program.sessions.single().exercises.single()
    assertNull("a zero weight is bodyweight, not a 0 kg prescription", ex.loadValue)
    assertNotNull(ex.rawSourceLine)
  }
}
