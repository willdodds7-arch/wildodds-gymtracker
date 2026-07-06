package com.wildodds.gymtracker.ui.home

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The recovery overlay must round-trip and, crucially, be fully reversible (clear → gone). */
@RunWith(RobolectricTestRunner::class)
class RecoveryAdjustmentStoreTest {

  private lateinit var context: Context

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    RecoveryAdjustmentStore.clear(context)
  }

  @Test
  fun absentByDefault() {
    assertNull(RecoveryAdjustmentStore.load(context))
    assertFalse(RecoveryAdjustmentStore.isActive(context))
  }

  @Test
  fun savesAndLoadsTheAdjustment() {
    RecoveryAdjustmentStore.save(context, loadScale = 0.85f, volumeScale = 0.8f,
      label = "Scale today back", createdAt = 1_000L)

    assertTrue(RecoveryAdjustmentStore.isActive(context))
    val loaded = RecoveryAdjustmentStore.load(context)!!
    assertNotNull(loaded)
    assertEquals(0.85f, loaded.loadScale, 0.0001f)
    assertEquals(0.8f, loaded.volumeScale, 0.0001f)
    assertEquals("Scale today back", loaded.label)
    assertEquals(1_000L, loaded.createdAt)
  }

  @Test
  fun clearMakesItReversible() {
    RecoveryAdjustmentStore.save(context, 0.9f, 0.8f, "Insert a deload", 2_000L)
    assertTrue(RecoveryAdjustmentStore.isActive(context))

    RecoveryAdjustmentStore.clear(context)

    assertFalse("revert must fully disarm the overlay", RecoveryAdjustmentStore.isActive(context))
    assertNull(RecoveryAdjustmentStore.load(context))
  }
}
