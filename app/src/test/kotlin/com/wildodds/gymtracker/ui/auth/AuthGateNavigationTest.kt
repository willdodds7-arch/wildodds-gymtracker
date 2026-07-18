package com.wildodds.gymtracker.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
 * Onboarding navigation test for the thin, account-OPTIONAL intro: welcome → account → tour. The
 * no-account path reaches the app in three taps; the 13+ age gate and consent live only on the
 * "create account" branch. Driven with a [FakeAuthActions] (the real AuthViewModel builds the
 * Supabase client, which can't init its session storage on the JVM).
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

    var finished = false
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
    override fun finishOnboarding(username: String?) { finished = true; onboardingComplete.value = true }
    override fun beginFirstBackup(username: String?) {}
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

  // Onboarding screens scroll (OnboardingScaffold), and buttons can sit below the small test
  // viewport — scroll a node into view before touching it.
  private fun ComposeContentTestRule.tap(tag: String) = onNodeWithTag(tag).performScrollTo().performClick()
  private fun ComposeContentTestRule.type(tag: String, text: String) =
    onNodeWithTag(tag).performScrollTo().performTextInput(text)

  @Test
  fun freshLaunch_showsTheWelcome_notTheApp() {
    setGate(FakeAuthActions())
    composeRule.onNodeWithTag("welcome_continue").assertExists()
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertDoesNotExist()
  }

  @Test
  fun skippingTheAccount_reachesTheApp_inThreeTaps_noAgeGateNoConsent() {
    setGate(FakeAuthActions())
    composeRule.tap("welcome_continue")   // → account
    composeRule.tap("account_skip")       // → tour (no age gate, no auth)
    composeRule.onNodeWithTag("auth_title").assertDoesNotExist()
    composeRule.onNodeWithTag("consent_title").assertDoesNotExist()
    composeRule.tap("tour_skip")          // → finish → app
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertIsDisplayed()
  }

  @Test
  fun ageGateAndConsentAppearOnlyOnTheCreateAccountBranch() {
    setGate(FakeAuthActions())
    composeRule.tap("welcome_continue")
    composeRule.tap("account_create")     // → age gate
    composeRule.type("age_day", "1")
    composeRule.type("age_month", "1")
    composeRule.type("age_year", "1995")
    composeRule.tap("age_continue")
    composeRule.onNodeWithTag("auth_title").assertExists()   // reaches the sign-in form
  }

  @Test
  fun underThirteen_cannotMakeAnAccount_butCanStillUseTheAppLocally() {
    setGate(FakeAuthActions())
    composeRule.tap("welcome_continue")
    composeRule.tap("account_create")
    composeRule.type("age_day", "1")
    composeRule.type("age_month", "1")
    composeRule.type("age_year", "2020")
    composeRule.tap("age_continue")

    composeRule.onNodeWithTag("age_blocked_signup").assertExists()
    composeRule.onNodeWithTag("auth_title").assertDoesNotExist()   // no account created
    composeRule.tap("age_blocked_continue") // → tour
    composeRule.tap("tour_skip")
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertIsDisplayed()
  }

  @Test
  fun onceOnboardingComplete_theAppShows() {
    val fake = FakeAuthActions(initialStatus = authenticated())
    fake.onboardingComplete.value = true
    setGate(fake)
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertIsDisplayed()
  }

  @Test
  fun tour_canBeTakenThroughToTheApp() {
    setGate(FakeAuthActions())
    composeRule.tap("welcome_continue")
    composeRule.tap("account_skip")
    composeRule.tap("tour_take")          // enter the tour cards
    composeRule.tap("tour_next")          // card 1 → 2
    composeRule.tap("tour_next")          // card 2 → 3
    composeRule.tap("tour_next")          // "Start training" → finish
    composeRule.onNodeWithText("MAIN_APP_CONTENT").assertIsDisplayed()
  }
}
