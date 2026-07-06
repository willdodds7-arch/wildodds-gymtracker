package com.wildodds.gymtracker.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Compose test for the registry-driven Settings search: typing filters rows across every group.
 * Assertions are made on the filtered result (which lands at the top of the list) so we never
 * depend on whether an unfiltered row happens to be scrolled into view.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsSearchTest {

  @get:Rule
  val compose = createComposeRule()

  @Test
  fun searchFiltersRowsAcrossGroups() {
  val app = ApplicationProvider.getApplicationContext<Application>()
  val vm = SettingsViewModel(app)
  compose.setContent { MaterialTheme { SettingsScreen(vm = vm) } }

  // "epley" matches only the 1RM Calculator (Display); the Session Timer row disappears.
  compose.onNodeWithTag("settings_search").performTextInput("epley")
  compose.waitForIdle()
  compose.onNodeWithText("1RM Calculator").assertExists()
  compose.onNodeWithText("Session Timer").assertDoesNotExist()

  // Re-query "timer": the Session rows come back and the 1RM row drops out.
  compose.onNodeWithTag("settings_search").performTextReplacement("timer")
  compose.waitForIdle()
  compose.onNodeWithText("Session Timer").assertExists()
  compose.onNodeWithText("1RM Calculator").assertDoesNotExist()

  // A query that matches nothing shows the explicit "no results" state (and no rows).
  compose.onNodeWithTag("settings_search").performTextReplacement("zzzznope")
  compose.waitForIdle()
  compose.onNodeWithText("No settings match", substring = true).assertExists()
  compose.onNodeWithText("Session Timer").assertDoesNotExist()
  }
}
