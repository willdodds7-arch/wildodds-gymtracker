package com.wildodds.gymtracker.ui.auth

import io.github.jan.supabase.gotrue.SessionStatus
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** What the root of the app shows. Pure function of session + onboarding state, so it's unit-testable. */
enum class GateDestination { LOADING, ONBOARDING, MAIN }

/**
 * Online-first became account-OPTIONAL: the intro (which offers, but never forces, an account) runs
 * once and sets `onboardingComplete`. After that the user is in the app — signed in or not. So the
 * routing key is `onboardingComplete`, not the session:
 *  - onboardingComplete ⇒ MAIN, whether or not there's a session (a signed-in-but-offline user, or a
 *    no-account user, both belong in the app — Rule 1: nothing about the network blocks a workout).
 *  - still resolving the stored session on cold start ⇒ LOADING (avoids flashing the intro at a
 *    returning user for a frame).
 *  - otherwise ⇒ ONBOARDING (the thin 3-screen intro).
 */
fun gateDestination(status: SessionStatus, onboardingComplete: Boolean): GateDestination = when {
  onboardingComplete -> GateDestination.MAIN
  status is SessionStatus.LoadingFromStorage -> GateDestination.LOADING
  else -> GateDestination.ONBOARDING
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
