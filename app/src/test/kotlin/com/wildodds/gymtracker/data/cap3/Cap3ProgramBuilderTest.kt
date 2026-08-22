package com.wildodds.gymtracker.data.cap3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** CAP3 has to come out the other side as an ordinary program. */
class Cap3ProgramBuilderTest {

  private val program = Cap3ProgramBuilder.build(Cap3FixtureTest.fixtureConfig())

  @Test
  fun threeWeeksOfSixTrainingDaysWithNoRestDay() {
    assertEquals(CAP3_WEEKS, program.totalWeeks)
    assertEquals(CAP3_WEEKS * CAP3_DAYS_PER_WEEK, program.sessions.size)
    for (week in 1..CAP3_WEEKS) {
      val days = program.sessions.filter { it.weekNumber == week }
      assertEquals("week $week", (1..6).toList(), days.map { it.dayNumber }.sorted())
      days.forEach { assertTrue("week $week ${it.name}", it.exercises.isNotEmpty()) }
    }
    assertEquals(CAP3_DAYS_PER_WEEK, program.daysPerWeek)
  }

  @Test
  fun everyDayLeadsWithTaggedT1AndT2AndTheRestAreOrdinaryAccessories() {
    program.sessions.forEach { session ->
      val where = "week ${session.weekNumber} day ${session.dayNumber}"
      val tagged = session.exercises.filter { it.cap3Tag.isNotBlank() }
      val roles = tagged.mapNotNull { Cap3Tag.parse(it.cap3Tag)?.role }
      assertEquals("$where leads with T1", Cap3Role.T1, roles.first())
      assertEquals("$where then T2", Cap3Role.T2, roles[1])
      // Accessories come after the tagged lifts and carry no tag, so they behave like any other
      // exercise: editable, swappable, deletable.
      val accessories = session.exercises.drop(tagged.size)
      assertTrue("$where has accessories", accessories.isNotEmpty())
      accessories.forEach { assertEquals("$where ${it.name}", "", it.cap3Tag) }
      assertEquals("$where order", session.exercises.indices.toList(), session.exercises.map { it.orderIndex })
    }
  }

  @Test
  fun theEmomIsItsOwnRowOnTheWeeksThatRunOne() {
    program.sessions.forEach { session ->
      val slot = Cap3DaySlot.byIndex(session.dayNumber - 1)
      val emom = session.exercises.firstOrNull { Cap3Tag.parse(it.cap3Tag)?.role == Cap3Role.EMOM }
      if (Cap3Engine.hasEmom(session.weekNumber, slot)) {
        assertNotNull("week ${session.weekNumber} $slot", emom)
        assertTrue(emom!!.name.endsWith("— EMOM"))
      } else {
        assertEquals("week ${session.weekNumber} $slot", null, emom)
      }
    }
    // 18 days, 3 of which are de-emphasis days without an EMOM.
    assertEquals(15, program.sessions.count { s ->
      s.exercises.any { Cap3Tag.parse(it.cap3Tag)?.role == Cap3Role.EMOM }
    })
  }

  @Test
  fun taggedRowsNameTheConfiguredVariationAndPointAtTheirOwnDay() {
    val config = Cap3FixtureTest.fixtureConfig()
    program.sessions.forEach { session ->
      val slot = Cap3DaySlot.byIndex(session.dayNumber - 1)
      session.exercises.filter { it.cap3Tag.isNotBlank() }.forEach { ex ->
        val tag = Cap3Tag.parse(ex.cap3Tag)!!
        assertEquals(session.weekNumber, tag.week)
        assertEquals(slot, tag.daySlot)
        val expected = when (tag.role) {
          Cap3Role.T1 -> config.lift(slot.primary).exercise
          else -> config.lift(config.secondarySlot(slot)).exercise
        }
        assertTrue("${ex.name} should be $expected", ex.name.startsWith(expected))
      }
    }
  }

  @Test
  fun theMajorEffortDayIsFlaggedInItsNotes() {
    val meDays = program.sessions.filter { s ->
      s.exercises.first().notes.contains("Major-effort")
    }
    assertEquals(CAP3_WEEKS, meDays.size)
    meDays.forEach { s ->
      val slot = Cap3DaySlot.byIndex(s.dayNumber - 1)
      assertTrue(Cap3Schemes.isMajorEffort(s.weekNumber, slot))
      assertEquals("6/4/2", s.exercises.first().repsTarget)
    }
  }

  @Test
  fun sessionNamesAreTheDayTitles() {
    assertEquals(
      Cap3DaySlot.entries.map { it.title },
      program.sessions.filter { it.weekNumber == 1 }.sortedBy { it.dayNumber }.map { it.name }
    )
  }

  @Test
  fun theMajorEffortDayIsDetectableFromItsTagsAlone() {
    // This is what Home uses to put the lightning bolt on the week's hard day.
    var flagged = 0
    program.sessions.forEach { session ->
      val slot = Cap3DaySlot.byIndex(session.dayNumber - 1)
      val isMe = Cap3Tag.isMajorEffortDay(session.exercises.map { it.cap3Tag })
      assertEquals(
        "week ${session.weekNumber} $slot",
        Cap3Schemes.isMajorEffort(session.weekNumber, slot),
        isMe
      )
      if (isMe) flagged++
    }
    assertEquals(CAP3_WEEKS, flagged)
    // Any other program has no tags at all, so it never lights up.
    assertEquals(false, Cap3Tag.isMajorEffortDay(listOf("", "", "")))
    assertEquals(false, Cap3Tag.isMajorEffortDay(emptyList()))
  }
}
