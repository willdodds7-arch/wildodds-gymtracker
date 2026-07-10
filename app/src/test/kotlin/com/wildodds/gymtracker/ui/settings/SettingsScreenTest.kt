package com.wildodds.gymtracker.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Critical-flow Compose test: the Settings screen renders a known toggle (Dark Mode) and
 * flipping it updates the control. Runs the real [SettingsViewModel] (DataStore-backed) so
 * the toggle round-trips through persistence exactly as it does in the app.
 *
 * Toggle switches carry a "toggle_<flagKey>" testTag (labels and switches are separate nodes in
 * the flattened semantics tree, so a label-relative matcher can't reach the switch). The list
 * is lazy and the Account group now precedes Appearance, so scroll to the row first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsScreenTest {

  @get:Rule
  val compose = createComposeRule()

  @Test
  fun darkModeToggle_rendersAndFlips() {
  val app = ApplicationProvider.getApplicationContext<Application>()
  val vm = SettingsViewModel(app)

  compose.setContent {
  MaterialTheme { SettingsScreen(vm = vm) }
  }

  compose.onNodeWithText("Settings").assertExists()

  compose.onNode(hasScrollAction()).performScrollToNode(hasTestTag("toggle_dark_mode"))
  compose.onNodeWithText("Dark Mode").assertExists()

  compose.onNodeWithTag("toggle_dark_mode").assertIsOff()
  compose.onNodeWithTag("toggle_dark_mode").performClick()

  // The flip round-trips through DataStore → StateFlow → recomposition; wait for it.
  compose.waitUntil(timeoutMillis = 5_000) {
  runCatching { compose.onNodeWithTag("toggle_dark_mode").assertIsOn() }.isSuccess
  }
  compose.onNodeWithTag("toggle_dark_mode").assertIsOn()
  }
}
