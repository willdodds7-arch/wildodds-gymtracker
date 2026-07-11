package com.wildodds.gymtracker.ui.auth

import io.github.jan.supabase.gotrue.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** What the root of the app shows. Pure function of session + onboarding state, so it's unit-testable. */
enum class GateDestination { LOADING, ONBOARDING, POST_AUTH_SETUP, MAIN }

/**
 * Rule 1 (offline-first) is encoded here: [SessionStatus.NetworkError] means a stored session
 * exists but couldn't be refreshed — the user has an account and is merely offline, so they go
 * STRAIGHT into the app. A dropped connection never blocks a workout. Only a genuinely absent
 * session (never signed in, or signed out) routes to onboarding/auth.
 */
fun gateDestination(status: SessionStatus, onboardingComplete: Boolean): GateDestination =
  when (status) {
    is SessionStatus.LoadingFromStorage -> GateDestination.LOADING
    is SessionStatus.NotAuthenticated -> GateDestination.ONBOARDING
    is SessionStatus.Authenticated,
    is SessionStatus.NetworkError ->
      if (onboardingComplete) GateDestination.MAIN else GateDestination.POST_AUTH_SETUP
  }

/**
 * Age gate (13+, hard block). Computed from a full date of birth so a birthday later this year
 * doesn't slip through — but ONLY the pass/fail boolean is ever stored (see ThemePreferences);
 * the birth date itself is discarded immediately. Runs before sign-up, i.e. before any data
 * collection at all.
 */
object AgeGate {
  const val MIN_AGE_YEARS = 13

  fun isOldEnough(birthDate: LocalDate, today: LocalDate): Boolean =
    !birthDate.plusYears(MIN_AGE_YEARS.toLong()).isAfter(today)

  /** Null when the fields don't form a real calendar date (Feb 30, month 13, …). */
  fun parseBirthDate(year: Int, month: Int, day: Int): LocalDate? =
    runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

data class AuthFormState(
  val isLoading: Boolean = false,
  val error: String? = null,
  // One-shot flags the UI reacts to (e.g. "reset email sent" notice).
  val resetEmailSent: Boolean = false
)

/**
 * What the auth/onboarding UI needs from its ViewModel, as an interface so the composables are
 * testable under Robolectric — the real [AuthViewModel] constructs the Supabase client, whose
 * Auth plugin needs device-only session storage a JVM test can't provide. Tests use a fake.
 */
interface AuthActions {
  val sessionStatus: StateFlow<SessionStatus>
  val onboardingComplete: StateFlow<Boolean>
  val ageGatePassed: StateFlow<Boolean>
  val ageGateBlocked: StateFlow<Boolean>
  val form: StateFlow<AuthFormState>

  fun recordAgeGateResult(oldEnough: Boolean)
  fun signUp(email: String, password: String)
  fun signIn(email: String, password: String)
  fun signInWithGoogleIdToken(idToken: String)
  fun requestPasswordReset(email: String)
  fun updatePassword(newPassword: String, onDone: () -> Unit)
  fun clearError()
  fun setAnalyticsConsent(granted: Boolean)
  fun finishOnboarding(username: String?)

  /** First-login claim: saves the username and kicks off the initial upload of any existing
   *  local training data. The backup screen watches SyncStatus for its progress. */
  fun beginFirstBackup(username: String?)
}
