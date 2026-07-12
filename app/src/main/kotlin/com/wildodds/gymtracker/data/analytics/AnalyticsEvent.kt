package com.wildodds.gymtracker.data.analytics

/**
 * The COMPLETE analytics taxonomy. Nothing else is loggable — [AnalyticsClient] only accepts
 * an [AnalyticsEvent], so a new event type means adding a case here (and the banned-content
 * unit test then polices its properties).
 *
 * Rule 4, enforced by construction: every [properties] value is a coarse enum/bucket/flag —
 * NO free text, NO health/workout numbers, NO names/emails, NO precise location. The only
 * strings allowed are from fixed, code-defined vocabularies (screen names, feature keys,
 * onboarding step ids), never anything a user typed or any measured value.
 */
sealed class AnalyticsEvent(val name: String, val properties: Map<String, String> = emptyMap()) {

  data object AppOpen : AnalyticsEvent("app_open")

  /** [step] is a fixed onboarding-step id (e.g. "age_gate", "consent"), never user input. */
  data class OnboardingStep(val step: String) : AnalyticsEvent("onboarding_step", mapOf("step" to step))

  data object WorkoutStarted : AnalyticsEvent("workout_started")

  /**
   * [exerciseCountBucket] is a coarse size bucket ("1-3" / "4-6" / "7-9" / "10+"), never the
   * actual count, and certainly never weights/reps. Use [bucketCount] to derive it.
   */
  data class WorkoutCompleted(val exerciseCountBucket: String) :
    AnalyticsEvent("workout_completed", mapOf("exercise_count_bucket" to exerciseCountBucket))

  /** [source] is where the program came from, from a fixed set ("builder" / "import" / "catalogue" / "ai"). */
  data class ProgramCreated(val source: String) :
    AnalyticsEvent("program_created", mapOf("source" to source))

  /** [feature] is a SettingsRegistry flag key; [enabled] the new state. */
  data class FeatureToggled(val feature: String, val enabled: Boolean) :
    AnalyticsEvent("feature_toggled", mapOf("feature" to feature, "enabled" to enabled.toString()))

  /** [screen] is a fixed route/screen name from [Screens]. */
  data class ScreenView(val screen: String) : AnalyticsEvent("screen_view", mapOf("screen" to screen))

  /** [outcome] is "success" or "failure" — never row counts or error text. */
  data class SyncCompleted(val outcome: String) :
    AnalyticsEvent("sync_completed", mapOf("outcome" to outcome))

  /**
   * settings_search records only WHETHER a search yielded results, never the query itself —
   * a query is free text a user typed and must never be logged.
   */
  data class SettingsSearch(val hadResults: Boolean) :
    AnalyticsEvent("settings_search", mapOf("had_results" to hadResults.toString()))

  companion object {
    /** Coarse bucket for a count, so exact workout sizes never leave the device. */
    fun bucketCount(n: Int): String = when {
      n <= 3 -> "1-3"
      n <= 6 -> "4-6"
      n <= 9 -> "7-9"
      else -> "10+"
    }
  }
}

/** Canonical screen names for [AnalyticsEvent.ScreenView] — a fixed vocabulary, not free text. */
object Screens {
  const val HOME = "home"
  const val LIBRARY = "library"
  const val PROFILE = "profile"
  const val SETTINGS = "settings"
  const val SESSION = "session"
  const val HISTORY = "history"
  const val HABITS = "habits"
  const val CREATE_PROGRAM = "create_program"
}
