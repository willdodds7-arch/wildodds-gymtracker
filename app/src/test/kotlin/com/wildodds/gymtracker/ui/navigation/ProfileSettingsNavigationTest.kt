package com.wildodds.gymtracker.ui.navigation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.wildodds.gymtracker.ui.theme.AppTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Phase 0: Settings was removed from the bottom tab bar. Profile's gear icon
 * (testTag "profile_settings_gear") is now the only entry point into it.
 */
@RunWith(RobolectricTestRunner::class)
class ProfileSettingsNavigationTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun tappingProfileGearOpensSettings() {
    // Mirrors MainActivity's setContent: AppNavigation always renders inside AppTheme in
    // production, and some composables (e.g. AppType) rely on it being present.
    composeRule.setContent {
      AppTheme(isDarkMode = false, onToggleDarkMode = {}) {
        AppNavigation()
      }
    }

    // Start on Home; the bottom nav's "Profile" tab label is the only "Profile" text on screen.
    composeRule.onNodeWithText("Profile").performClick()

    // The gear icon in Profile's top bar opens Settings.
    composeRule.onNodeWithTag("profile_settings_gear").performClick()

    composeRule.onNodeWithText("Settings").assertIsDisplayed()
  }
}
