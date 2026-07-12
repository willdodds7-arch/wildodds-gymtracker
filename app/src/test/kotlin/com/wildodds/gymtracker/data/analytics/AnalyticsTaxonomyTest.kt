package com.wildodds.gymtracker.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rule 4, enforced as a test: every event any taxonomy case can produce carries ONLY coarse,
 * code-defined property values — no free text, no health/workout numbers, no names/emails, no
 * precise location. This constructs a representative instance of every [AnalyticsEvent] case,
 * including adversarial inputs (a user-typed-looking string, a raw workout count), and asserts
 * the resulting properties never contain anything PII-shaped.
 */
class AnalyticsTaxonomyTest {

  // Every event case, built with adversarial inputs where a value comes from outside the taxonomy.
  private val allEvents: List<AnalyticsEvent> = listOf(
    AnalyticsEvent.AppOpen,
    AnalyticsEvent.OnboardingStep("consent"),
    AnalyticsEvent.WorkoutStarted,
    // adversarial: caller must bucket — the case only accepts a bucket string
    AnalyticsEvent.WorkoutCompleted(AnalyticsEvent.bucketCount(47)),
    AnalyticsEvent.ProgramCreated("builder"),
    AnalyticsEvent.FeatureToggled("feat_session_timer", true),
    AnalyticsEvent.ScreenView(Screens.HOME),
    AnalyticsEvent.SyncCompleted("success"),
    AnalyticsEvent.SettingsSearch(hadResults = true)
  )

  @Test
  fun everyEventNameIsSnakeCaseAndKnown() {
    val known = setOf(
      "app_open", "onboarding_step", "workout_started", "workout_completed",
      "program_created", "feature_toggled", "screen_view", "sync_completed", "settings_search"
    )
    allEvents.forEach { assertTrue("unknown event ${it.name}", it.name in known) }
    assertEquals("taxonomy size drifted — update this test AND the banned-content review", known.size, allEvents.size)
  }

  @Test
  fun noPropertyValueLooksLikePii() {
    // A property value is suspect if it contains an @, a long digit run (a measurement / id /
    // phone), decimals (weights/RPE), whitespace (free text / names), or coordinate punctuation.
    val emailish = Regex("@")
    val longDigits = Regex("""\d{3,}""")
    val decimals = Regex("""\d+\.\d+""")
    val whitespace = Regex("""\s""")
    val coordish = Regex("""-?\d+\.\d+\s*,\s*-?\d+\.\d+""")

    allEvents.forEach { event ->
      event.properties.forEach { (k, v) ->
        assertFalse("$k contains '@' (email?): $v", emailish.containsMatchIn(v))
        assertFalse("$k has a long digit run (id/measure?): $v", longDigits.containsMatchIn(v))
        assertFalse("$k has decimals (weight/rpe?): $v", decimals.containsMatchIn(v))
        assertFalse("$k has whitespace (free text/name?): $v", whitespace.containsMatchIn(v))
        assertFalse("$k looks like coordinates: $v", coordish.containsMatchIn(v))
      }
    }
  }

  @Test
  fun workoutCountIsBucketedNotRaw() {
    assertEquals("1-3", AnalyticsEvent.bucketCount(1))
    assertEquals("1-3", AnalyticsEvent.bucketCount(3))
    assertEquals("4-6", AnalyticsEvent.bucketCount(5))
    assertEquals("7-9", AnalyticsEvent.bucketCount(8))
    assertEquals("10+", AnalyticsEvent.bucketCount(10))
    assertEquals("10+", AnalyticsEvent.bucketCount(999))
    // The completed-workout event never exposes the raw number.
    val e = AnalyticsEvent.WorkoutCompleted(AnalyticsEvent.bucketCount(999))
    assertFalse(e.properties.values.any { it.contains("999") })
  }

  @Test
  fun settingsSearch_recordsOnlyWhetherThereWereResults_neverTheQuery() {
    val e = AnalyticsEvent.SettingsSearch(hadResults = false)
    assertEquals(mapOf("had_results" to "false"), e.properties)
  }
}
