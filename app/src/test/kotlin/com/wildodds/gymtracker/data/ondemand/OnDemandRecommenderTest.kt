package com.wildodds.gymtracker.data.ondemand

import com.wildodds.gymtracker.data.MuscleGroup
import com.wildodds.gymtracker.data.intelligence.Equipment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandRecommenderTest {

  private fun session(id: String, focus: Set<MuscleGroup>, dur: Int = 20, equip: Set<Equipment> = emptySet()) =
  OnDemandSession(id, id, SessionSource.TEMPLATE, focus, dur, equip,
  listOf(OnDemandExercise("X", 3, "8-12")))

  @Test
  fun rankingFavoursUnderWorkedGroups() {
  // Pull is starved (0 sets), chest is well covered (15 sets vs 10 MEV).
  val volume = mapOf(MuscleGroup.CHEST to 15f, MuscleGroup.LATS to 0f)
  val pullSession = session("Pull", setOf(MuscleGroup.LATS, MuscleGroup.UPPER_BACK))
  val pushSession = session("Push", setOf(MuscleGroup.CHEST))
  val ranked = OnDemandRecommender.rank(listOf(pushSession, pullSession), volume)
  assertEquals("under-worked pull session ranks first", "Pull", ranked.first().id)
  assertNotNull("recommended session has a reason", ranked.first().reason)
  assertTrue(ranked.first().reason!!.contains("Lats") || ranked.first().reason!!.contains("Upper Back"))
  // Well-covered session gets no complement reason.
  assertNull(ranked.first { it.id == "Push" }.reason)
  }

  @Test
  fun deficitsAreNormalised() {
  val d = OnDemandRecommender.deficits(mapOf(MuscleGroup.LATS to 5f))  // MEV 10 → 50% deficit
  assertEquals(0.5f, d[MuscleGroup.LATS]!!, 0.001f)
  assertEquals(1f, d[MuscleGroup.QUADS]!!, 0.001f)  // no work → full deficit
  }

  @Test
  fun durationFilter() {
  val sessions = listOf(session("short", setOf(MuscleGroup.LATS), dur = 15),
  session("long", setOf(MuscleGroup.LATS), dur = 45))
  val out = OnDemandRecommender.filter(sessions, OnDemandFilter(maxDurationMin = 20))
  assertEquals(listOf("short"), out.map { it.id })
  }

  @Test
  fun muscleFilter() {
  val sessions = listOf(session("legs", setOf(MuscleGroup.QUADS)), session("back", setOf(MuscleGroup.LATS)))
  val out = OnDemandRecommender.filter(sessions, OnDemandFilter(muscle = MuscleGroup.LATS))
  assertEquals(listOf("back"), out.map { it.id })
  }

  @Test
  fun equipmentFilter() {
  val dumbbell = session("db", setOf(MuscleGroup.BICEPS), equip = setOf(Equipment.DUMBBELL))
  val barbell = session("bb", setOf(MuscleGroup.BICEPS), equip = setOf(Equipment.BARBELL))
  val out = OnDemandRecommender.filter(listOf(dumbbell, barbell), OnDemandFilter(equipment = Equipment.DUMBBELL))
  assertEquals(listOf("db"), out.map { it.id })
  }

  @Test
  fun gapGeneratorBuildsSessionsForUnderWorkedMuscles() {
  val gen = GapSessionGenerator.generate(listOf(MuscleGroup.LATS, MuscleGroup.BICEPS))
  assertTrue(gen.isNotEmpty())
  assertTrue(gen.all { it.source == SessionSource.GENERATED })
  assertTrue(gen.all { it.exercises.size >= 2 })
  // The lats session focuses on lats.
  assertTrue(gen.any { MuscleGroup.LATS in it.muscleFocus })
  }
}
