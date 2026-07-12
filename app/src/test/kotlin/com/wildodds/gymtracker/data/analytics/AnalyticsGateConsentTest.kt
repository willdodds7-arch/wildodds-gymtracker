package com.wildodds.gymtracker.data.analytics

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.datastore.ThemePreferences
import com.wildodds.gymtracker.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Rule 4 gate, asserted deterministically: with consent NOT granted, [AnalyticsGate.persistIfConsented]
 * DROPS the event (returns false, writes nothing) — so nothing can leak on a later opt-in. Only
 * "granted" queues rows.
 *
 * Uses its OWN in-memory DAO (injected) rather than the AppDatabase.getInstance singleton, which
 * goes stale across Robolectric test-class boundaries ("Illegal connection pointer"). Drives the
 * awaitable core with runBlocking, so there are no fire-and-forget coroutines leaking across tests.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsGateConsentTest {

  private lateinit var context: Context
  private lateinit var db: AppDatabase

  @Before
  fun setUp() {
    context = ApplicationProvider.getApplicationContext()
    AnalyticsGate.init(context)
    db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
  }

  @After
  fun tearDown() = runBlocking {
    ThemePreferences(context).setAnalyticsConsent("unset")
    db.close()
  }

  private fun count() = runBlocking { db.analyticsOutboxDao().count() }

  @Test
  fun preConsent_dropsEvents() = runBlocking {
    ThemePreferences(context).setAnalyticsConsent("unset")
    val queued = AnalyticsGate.persistIfConsented(context, AnalyticsEvent.AppOpen, db.analyticsOutboxDao())
    assertFalse("pre-consent events must be dropped", queued)
    assertEquals(0, count())
  }

  @Test
  fun declined_dropsEvents() = runBlocking {
    ThemePreferences(context).setAnalyticsConsent("denied")
    val queued = AnalyticsGate.persistIfConsented(context, AnalyticsEvent.WorkoutStarted, db.analyticsOutboxDao())
    assertFalse(queued)
    assertEquals(0, count())
  }

  @Test
  fun granted_queuesEvents() = runBlocking {
    ThemePreferences(context).setAnalyticsConsent("granted")
    val queued = AnalyticsGate.persistIfConsented(context, AnalyticsEvent.ScreenView(Screens.HOME), db.analyticsOutboxDao())
    assertTrue(queued)
    assertEquals(1, count())
  }

  @Test
  fun screenIsSplitOut_andNoPiiReachesTheStoredRow() = runBlocking {
    ThemePreferences(context).setAnalyticsConsent("granted")
    AnalyticsGate.persistIfConsented(context, AnalyticsEvent.ScreenView(Screens.SETTINGS), db.analyticsOutboxDao())
    val row = db.analyticsOutboxDao().peek(1).single()
    assertEquals("screen_view", row.eventName)
    assertEquals(Screens.SETTINGS, row.screen)   // screen lifted to its own column...
    assertEquals("{}", row.propertiesJson)        // ...not left inside properties
    assertFalse("session id must not be an email", row.sessionId.contains("@"))
  }

  @Test
  fun clearDropsEverythingQueued() = runBlocking {
    ThemePreferences(context).setAnalyticsConsent("granted")
    AnalyticsGate.persistIfConsented(context, AnalyticsEvent.AppOpen, db.analyticsOutboxDao())
    assertEquals(1, count())
    db.analyticsOutboxDao().clear()   // what onConsentRevoked triggers
    assertEquals(0, count())
  }
}
