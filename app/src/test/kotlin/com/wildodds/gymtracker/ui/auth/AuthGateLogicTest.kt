package com.wildodds.gymtracker.ui.auth

import io.github.jan.supabase.gotrue.SessionSource
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.user.UserSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AuthGateLogicTest {

  private val session = UserSession(accessToken = "a", refreshToken = "r", expiresIn = 3600, tokenType = "bearer")

  private val authed = SessionStatus.Authenticated(session, SessionSource.Storage)

  @Test
  fun onboardingComplete_alwaysGoesToMain_withOrWithoutASession() {
    // Accounts are optional: a completed intro means the app, regardless of session state.
    assertEquals(GateDestination.MAIN, gateDestination(authed, true))                        // signed in
    assertEquals(GateDestination.MAIN, gateDestination(SessionStatus.NotAuthenticated(false), true)) // no account
    assertEquals(GateDestination.MAIN, gateDestination(SessionStatus.NetworkError, true))    // signed in, offline
  }

  @Test
  fun notComplete_showsTheIntro_onceTheSessionHasResolved() {
    assertEquals(GateDestination.ONBOARDING, gateDestination(SessionStatus.NotAuthenticated(false), false))
    assertEquals(GateDestination.ONBOARDING, gateDestination(authed, false))
  }

  @Test
  fun stillLoadingSession_andNotComplete_showsLoading_toAvoidFlashingTheIntro() {
    assertEquals(GateDestination.LOADING, gateDestination(SessionStatus.LoadingFromStorage, false))
  }

  @Test
  fun completeWins_evenWhileTheSessionIsStillLoading() {
    assertEquals(GateDestination.MAIN, gateDestination(SessionStatus.LoadingFromStorage, true))
  }

  // ── Age gate ─────────────────────────────────────────────────────────────────

  private val today = LocalDate.of(2026, 7, 11)

  @Test
  fun thirteenthBirthdayToday_isAllowed() {
    assertTrue(AgeGate.isOldEnough(today.minusYears(13), today))
  }

  @Test
  fun thirteenthBirthdayTomorrow_isBlocked() {
    assertFalse(AgeGate.isOldEnough(today.minusYears(13).plusDays(1), today))
  }

  @Test
  fun sameBirthYearIsNotEnough_monthAndDayMatter() {
    // Born December 2013: 2026 - 2013 = 13 by subtraction, but they're still 12 in July.
    assertFalse(AgeGate.isOldEnough(LocalDate.of(2013, 12, 1), today))
    // Born January 2013: already 13.
    assertTrue(AgeGate.isOldEnough(LocalDate.of(2013, 1, 1), today))
  }

  @Test
  fun clearlyOldEnough_andClearlyTooYoung() {
    assertTrue(AgeGate.isOldEnough(LocalDate.of(1990, 5, 20), today))
    assertFalse(AgeGate.isOldEnough(LocalDate.of(2020, 5, 20), today))
  }

  @Test
  fun impossibleDatesAreRejected() {
    assertNull(AgeGate.parseBirthDate(2000, 2, 30))
    assertNull(AgeGate.parseBirthDate(2000, 13, 1))
    assertNull(AgeGate.parseBirthDate(2000, 0, 10))
  }
}
