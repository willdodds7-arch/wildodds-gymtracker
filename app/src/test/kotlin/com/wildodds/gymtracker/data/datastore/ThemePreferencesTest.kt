package com.wildodds.gymtracker.data.datastore

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the generic flag API on [ThemePreferences]: defaults, persistence, and that the
 * generic and typed accessors share the same underlying DataStore key (back-compat).
 *
 * Each test uses a distinct key — DataStore's backing file persists across test methods in the
 * Robolectric sandbox, so a shared key would let one test's writes leak into another.
 */
@RunWith(RobolectricTestRunner::class)
class ThemePreferencesTest {

  private val prefs = ThemePreferences(ApplicationProvider.getApplicationContext())

  @Test
  fun unsetFlagReturnsProvidedDefault() = runTest {
  // A key never written by any test — so only the default is ever observed.
  assertTrue(prefs.flag("probe_unset_only", default = true).first())
  assertFalse(prefs.flag("probe_unset_only", default = false).first())
  }

  @Test
  fun setFlagPersistsAndIsReadBack() = runTest {
  prefs.setFlag("probe_persist", true)
  assertTrue(prefs.flag("probe_persist", default = false).first())

  prefs.setFlag("probe_persist", false)
  assertFalse(prefs.flag("probe_persist", default = true).first())
  }

  @Test
  fun genericWriteIsVisibleToTypedAccessor() = runTest {
  // The generic key "feat_session_timer" is the SAME pref the typed accessor reads.
  prefs.setFlag("feat_session_timer", false)
  assertFalse(prefs.featSessionTimer.first())
  assertFalse(prefs.flag("feat_session_timer", default = true).first())

  prefs.setFlag("feat_session_timer", true)
  assertTrue(prefs.featSessionTimer.first())
  }
}
