package com.wildodds.gymtracker.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.wildodds.gymtracker.ui.theme.AppTheme
import io.github.jan.supabase.gotrue.SessionSource
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.user.UserSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 2 gate: "main graph unreachable without a session (navigation test)". Drives the real
 * AuthGate + onboarding composables with a [FakeAuthActions] — the real AuthViewModel constructs
 * the Supabase client, which can't initialise its session storage on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class AuthGateNavigationTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  private class FakeAuthActions(
    initialStatus: SessionStatus = SessionStatus.NotAuthenticated(false)
  ) : AuthActions {
    override val sessionStatus = MutableStateFlow(initialStatus)
    override val onboardingComplete = MutableStateFlow(false)
    override val ageGatePassed = MutableStateFlow(false)
    override val ageGateBlocked = MutableStateFlow(false)
    override val form = MutableStateFlow(AuthFormState())

    override fun recordAgeGateResult(oldEnough: Boolean) {
      if (oldEnough) ageGatePassed.value = true else ageGateBlocked.value = true
    }
    override fun signUp(email: String, password: String) {}
    override fun signIn(email: String, password: String) {}
    override fun signInWithGoogleIdToken(idToken: String) {}
    override fun requestPasswordReset(email: String) {}
    override fun updatePassword(newPassword: String, onDone: () -> Unit) {}
    override fun clearError() {}
    override fun setAnalyticsConsent(granted: Boolean) {}
    override fun finishOnboarding(username: String?) {}
  }

  private fun setGate(actions: AuthActions) {
    composeRule.setContent {
      AppTheme(isDarkMode = false, onToggleDarkMode = {}) {
        AuthGate(actions) { Text("MAIN_APP_CONTENT") }
      }
    }
  }

  private fun authenticated(): SessionStatus {
    val session = UserSession(accessToken = "a", refreshToken = "r", expiresIn = 3600, tokenType = "bearer")
    return SessionStatus.Authenticated(session, SessionSource.Storage)
  }

  @Test
  fun withoutASession_onboardingShows_andMainGraphIsUnreachable() {
    setGate(FakeAuthActions())
    composeRule.onNodeWithTag("welcome_continue").assertIsDisplayed()
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertDoesNotExist()
  }

  @Test
  fun underThirteen_isHardBlocked_beforeAnyAccountStep() {
    setGate(FakeAuthActions())
    composeRule.onNodeWithTag("welcome_continue").performClick()
    composeRule.onNodeWithTag("age_day").performTextInput("1")
    composeRule.onNodeWithTag("age_month").performTextInput("1")
    composeRule.onNodeWithTag("age_year").performTextInput("2020")
    composeRule.onNodeWithTag("age_continue").performClick()

    composeRule.onNodeWithTag("age_blocked").assertIsDisplayed()
    // No sign-up form, no main app — nowhere to go from the block screen.
    composeRule.onNodeWithTag("auth_title").assertDoesNotExist()
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertDoesNotExist()
  }

  @Test
  fun ofAge_reachesTheSignInForm_butStillNotTheMainGraph() {
    setGate(FakeAuthActions())
    composeRule.onNodeWithTag("welcome_continue").performClick()
    composeRule.onNodeWithTag("age_day").performTextInput("1")
    composeRule.onNodeWithTag("age_month").performTextInput("1")
    composeRule.onNodeWithTag("age_year").performTextInput("1995")
    composeRule.onNodeWithTag("age_continue").performClick()

    composeRule.onNodeWithTag("auth_title").assertIsDisplayed()
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertDoesNotExist()
  }

  @Test
  fun authenticatedButOnboardingIncomplete_showsConsent_notMain() {
    setGate(FakeAuthActions(initialStatus = authenticated()))
    composeRule.onNodeWithTag("consent_title").assertIsDisplayed()
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertDoesNotExist()
  }

  @Test
  fun authenticatedWithOnboardingComplete_showsMain() {
    val fake = FakeAuthActions(initialStatus = authenticated())
    fake.onboardingComplete.value = true
    setGate(fake)
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertIsDisplayed()
  }

  @Test
  fun consentScreen_acceptAndDeclineAreBothPresent_andDeclineMovesOn() {
    val fake = FakeAuthActions(initialStatus = authenticated())
    setGate(fake)
    composeRule.onNodeWithTag("consent_accept").assertIsDisplayed()
    composeRule.onNodeWithTag("consent_decline").assertIsDisplayed()
    composeRule.onNodeWithTag("consent_decline").performClick()
    composeRule.onNodeWithTag("username_field").assertIsDisplayed()
  }
}
