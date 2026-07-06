package com.wildodds.gymtracker.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [SettingsRegistry] search/grouping — no Android dependencies, so these
 * run fast as plain JUnit. Every existing feature flag must be present and findable.
 */
class SettingsRegistryTest {

  private fun keys(query: String) = SettingsRegistry.search(query).map { it.key }

  @Test
  fun everyLegacyFlagIsRegistered() {
  val keys = SettingsRegistry.entries.map { it.key }
  listOf(
  "feat_session_timer", "feat_weight_autofill", "feat_add_exercise_mid_session",
  "feat_progression_picker", "feat_1rm_calculator"
  ).forEach { assertTrue("missing registry entry for $it", it in keys) }
  }

  @Test
  fun blankQueryReturnsEverythingInOrder() {
  assertEquals(SettingsRegistry.entries, SettingsRegistry.search(""))
  assertEquals(SettingsRegistry.entries, SettingsRegistry.search("   "))
  }

  @Test
  fun titleAndKeywordMatchesAreCaseInsensitive() {
  // "epley" only appears in the 1RM Calculator keywords.
  assertEquals(listOf("feat_1rm_calculator"), keys("epley"))
  assertEquals(listOf("feat_1rm_calculator"), keys("EPLEY"))
  // "ronnie" only appears in the Rest Timer Sound keywords.
  assertEquals(listOf("timer_sound"), keys("ronnie"))
  }

  @Test
  fun substringMatchesAcrossTitleSummaryKeywords() {
  // "timer" hits both the Session Timer toggle and the Rest Timer Sound control…
  val timer = keys("timer")
  assertTrue("feat_session_timer" in timer)
  assertTrue("timer_sound" in timer)
  // …but not the 1RM calculator.
  assertFalse("feat_1rm_calculator" in timer)
  }

  @Test
  fun multiTokenQueryRequiresAllTokens() {
  // Both tokens present only in the Rest Timer Sound entry.
  assertEquals(listOf("timer_sound"), keys("rest sound"))
  }

  @Test
  fun noMatchesReturnsEmpty() {
  assertTrue(SettingsRegistry.search("zzzznotathing").isEmpty())
  }

  @Test
  fun groupedResultsHonorGroupOrderAndDropEmptyGroups() {
  val groups = SettingsRegistry.grouped("").keys.toList()
  // The declared groups lead, in order (later phases append extra groups after these).
  assertEquals(SettingsRegistry.groupOrder, groups.take(SettingsRegistry.groupOrder.size))

  // A query that only hits the Display group yields just that group.
  assertEquals(listOf("Display"), SettingsRegistry.grouped("epley").keys.toList())
  }

  @Test
  fun sessionExitGuardIsRegisteredSearchableAndDefaultsOn() {
  val entry = SettingsRegistry.entries.firstOrNull { it.key == SettingsRegistry.SESSION_EXIT_GUARD }
  assertTrue("exit-guard entry missing", entry != null)
  assertTrue("exit guard must default ON", entry!!.default)
  assertEquals("Session", entry.group)
  // Findable by the words a user would actually type.
  assertTrue(SettingsRegistry.SESSION_EXIT_GUARD in keys("leave"))
  assertTrue(SettingsRegistry.SESSION_EXIT_GUARD in keys("exit"))
  }

  @Test
  fun defaultForKnownAndUnknownKeys() {
  assertTrue(SettingsRegistry.defaultFor("feat_session_timer"))
  assertFalse(SettingsRegistry.defaultFor("dark_mode"))
  assertFalse(SettingsRegistry.defaultFor("nonexistent_key"))
  }
}
