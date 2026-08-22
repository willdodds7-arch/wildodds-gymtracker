package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.db.entity.StoredSetPrescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The compile step is the one place where the reader can quietly lose what it understood.
 *
 * These tests pin the boundary from both sides: everything the v26 schema CAN hold must arrive as a
 * field (never as a notes blob), and the set of things it still cannot hold must be exactly
 * [DraftCompiler.LOSSY_FIELDS] — so a future field silently falling out of the model fails CI
 * instead of quietly degrading imports.
 */
class DraftCompilerLossTest {

  private fun draftOf(slot: SlotDraft, type: SessionType = SessionType.TRAINING) =
    ProgramImportDraft(
      name = "P",
      weeks = listOf(WeekDraft(1, sessions = listOf(SessionDraft("Day 1", 1, type = type, slots = listOf(slot)))))
    )

  private fun slot(
    raw: String = "Bench Press 3x8",
    sets: List<SetPrescription> = listOf(SetPrescription(raw, count = 3, reps = RepSpec(RepKind.COUNT, 8))),
    rest: RestSpec = RestSpec(),
    supersetGroup: String = "",
    supersetOrder: Int? = null,
    modifiers: List<String> = emptyList(),
    optional: Boolean = false,
    notes: String = ""
  ) = SlotDraft(
    rawText = raw,
    exercise = ExerciseRef(raw.substringBefore(" 3x"), "Bench Press", ResolutionStatus.EXACT, 0.9),
    sets = sets, rest = rest, supersetGroup = supersetGroup, supersetOrder = supersetOrder,
    modifiers = modifiers, optional = optional, notes = notes
  )

  private fun compileOne(slot: SlotDraft) =
    DraftCompiler.compile(draftOf(slot)).program.sessions.first().exercises.first()

  // ── everything the schema CAN hold arrives as a field ────────────────────────

  @Test fun `rest becomes columns, not a notes blob`() {
    val ex = compileOne(slot(rest = RestSpec(RestKind.RANGE, 90, 120, "rest 90-120s")))
    assertEquals(90, ex.restSeconds)
    assertEquals(120, ex.restRangeSecondsMax)
    assertTrue("rest must not be duplicated into notes", "Rest" !in ex.notes)
  }

  @Test fun `tempo becomes a column when it is a real four-slot tempo`() {
    val ex = compileOne(slot(sets = listOf(SetPrescription("x", count = 3, tempo = "31X0"))))
    assertEquals("31X0", ex.tempo)
  }

  @Test fun `a tempo-shaped string that is not a tempo is rejected rather than stored`() {
    // "3s pause" is a modifier. Writing it into the tempo column would make the column mean two
    // different things, which is worse than storing nothing.
    val ex = compileOne(slot(sets = listOf(SetPrescription("x", count = 3, tempo = "3s pause"))))
    assertNull(ex.tempo)
  }

  @Test fun `superset group and order become columns`() {
    val ex = compileOne(slot(supersetGroup = "A", supersetOrder = 2))
    assertEquals("A", ex.supersetGroup)
    assertEquals(2, ex.supersetOrder)
    assertTrue("superset must not be duplicated into notes", "Superset" !in ex.notes)
  }

  @Test fun `load kind, value, range and unit become columns`() {
    val ex = compileOne(slot(sets = listOf(SetPrescription(
      "x", count = 3,
      load = LoadSpec(LoadKind.ABSOLUTE, 60.0, 70.0, WeightUnit.KG, "60-70kg")))))
    assertEquals("ABSOLUTE", ex.loadKind)
    assertEquals(60.0, ex.loadValue!!, 0.001)
    assertEquals(70.0, ex.loadValueMax!!, 0.001)
    assertEquals("KG", ex.loadUnit)
  }

  @Test fun `RIR is a load kind now, not a note`() {
    val ex = compileOne(slot(sets = listOf(SetPrescription(
      "x", count = 3, load = LoadSpec(LoadKind.RIR, 2.0, text = "2")))))
    assertEquals("RIR", ex.loadKind)
    assertEquals("POINTS", ex.loadUnit)
    assertTrue("RIR must not be duplicated into notes", "RIR" !in ex.notes)
  }

  @Test fun `five three one survives as three real set rows`() {
    val perSet = listOf(5, 3, 1).map { r ->
      SetPrescription("$r", count = 1, reps = RepSpec(RepKind.COUNT, r))
    }
    val ex = compileOne(slot(sets = listOf(SetPrescription("5/3/1", count = 3, perSet = perSet))))
    assertEquals(3, ex.perSet.size)
    assertEquals(listOf("5", "3", "1"), ex.perSet.map(StoredSetPrescription::reps))
    assertEquals(listOf(1, 2, 3), ex.perSet.map(StoredSetPrescription::setNumber))
  }

  @Test fun `modifiers become a column and are sorted for determinism`() {
    val ex = compileOne(slot(modifiers = listOf(Modifier.EACH_SIDE, Modifier.PAUSED), optional = true))
    assertEquals(listOf(Modifier.EACH_SIDE, Modifier.OPTIONAL, Modifier.PAUSED), ex.modifiers)
  }

  @Test fun `the verbatim source line is kept even at full confidence`() {
    val ex = compileOne(slot().copy(confidence = 1.0))
    assertEquals("Bench Press 3x8", ex.rawSourceLine)
  }

  @Test fun `an unstated set count stays null rather than becoming one`() {
    val ex = compileOne(slot(sets = listOf(SetPrescription("Prowler sprints", kind = SetKind.UNKNOWN))))
    assertNull("null means the source didn't say — which is not one set", ex.setsStated)
    assertEquals("", ex.repsTarget)
  }

  @Test fun `a rest day compiles to a REST session with no exercises`() {
    val draft = ProgramImportDraft(
      name = "P",
      weeks = listOf(WeekDraft(1, sessions = listOf(
        SessionDraft("Push", 1, slots = listOf(slot())),
        SessionDraft("Rest", 2, type = SessionType.REST, slots = emptyList())
      )))
    )
    val sessions = DraftCompiler.compile(draft).program.sessions
    assertEquals(listOf("TRAINING", "REST"), sessions.map { it.sessionType })
    assertEquals(listOf(1, 2), sessions.map { it.dayNumber })
    assertTrue(sessions[1].exercises.isEmpty())
  }

  // ── the documented lossy set is exactly what it says ─────────────────────────

  @Test fun `notes carry the coach's prose and nothing else when everything else fits`() {
    val ex = compileOne(slot(
      rest = RestSpec(RestKind.FIXED, 120, text = "rest 2min"),
      supersetGroup = "B", supersetOrder = 1,
      modifiers = listOf(Modifier.EACH_SIDE),
      notes = "keep the elbows tucked"
    ))
    assertEquals("keep the elbows tucked", ex.notes)
  }

  @Test fun `every lossy field raises an INFO flag naming it`() {
    // One draft that trips every documented lossy path at once.
    val d = ProgramImportDraft(
      name = "P",
      weeks = listOf(WeekDraft(1, isDeload = true, sessions = listOf(SessionDraft(
        "Day 1", 1, notes = "session note",
        slots = listOf(slot(
          sets = listOf(SetPrescription("x", kind = SetKind.CLUSTER, count = 3,
            load = LoadSpec(LoadKind.BAND, text = "red band"))),
          rest = RestSpec(RestKind.AS_NEEDED, text = "as needed")
        ))
      )))),
      unresolvedProgression = listOf("add 2.5kg each week")
    )
    val flags = DraftCompiler.compile(d).flags
    assertTrue(flags.all { it.severity == FlagSeverity.INFO })
    // Exactly the documented set — no more (something new fell out of the model silently) and no
    // fewer (a documented lossy field stopped being exercised, so the doc is now a lie).
    assertEquals(
      DraftCompiler.LOSSY_FIELDS.keys.filterNot { it == "restKind.supersetNone" }.toSet(),
      flags.map { it.path }.toSet()
    )
  }

  @Test fun `no-rest supersets are the other rest kind that cannot be a number`() {
    val flags = DraftCompiler.compile(draftOf(slot(
      rest = RestSpec(RestKind.SUPERSET_NONE, text = "no rest")
    ))).flags
    assertEquals(listOf("restKind.supersetNone"), flags.map { it.path })
  }

  @Test fun `the lossy field list is documented, not empty, and stable in order`() {
    // If this list ever empties out, the compile step is lossless and the flagging can go. Until
    // then it must stay honest about exactly what it cannot hold.
    assertEquals(
      listOf("setKind", "restKind.asNeeded", "restKind.supersetNone", "load.text",
             "weekDeload", "sessionNotes", "programProgression"),
      DraftCompiler.LOSSY_FIELDS.keys.toList()
    )
  }
}
