package com.wildodds.gymtracker.ui.settings

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
 * Dark Mode is the first toggle on the screen, so we select the first toggleable node — the
 * screen's scrollable Column flattens the semantics tree, so a label-relative matcher would
 * ambiguously match every switch.
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

  // The screen and its Dark Mode label render.
  compose.onNodeWithText("Settings").assertExists()
  compose.onNodeWithText("Dark Mode").assertExists()

  // Dark Mode is the first toggle and defaults off.
  compose.onAllNodes(isToggleable()).onFirst().assertIsOff()

  compose.onAllNodes(isToggleable()).onFirst().performClick()

  // The flip round-trips through DataStore → StateFlow → recomposition; wait for it.
  compose.waitUntil(timeoutMillis = 5_000) {
  runCatching { compose.onAllNodes(isToggleable()).onFirst().assertIsOn() }.isSuccess
  }
  compose.onAllNodes(isToggleable()).onFirst().assertIsOn()
  }
}
