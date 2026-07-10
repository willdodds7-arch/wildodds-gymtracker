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

  @Test
  fun loadingFromStorage_showsLoading() {
    assertEquals(GateDestination.LOADING, gateDestination(SessionStatus.LoadingFromStorage, false))
    assertEquals(GateDestination.LOADING, gateDestination(SessionStatus.LoadingFromStorage, true))
  }

  @Test
  fun notAuthenticated_routesToOnboarding_regardlessOfOnboardingFlag() {
    assertEquals(GateDestination.ONBOARDING, gateDestination(SessionStatus.NotAuthenticated(false), false))
    assertEquals(GateDestination.ONBOARDING, gateDestination(SessionStatus.NotAuthenticated(true), true))
  }

  @Test
  fun authenticated_routesToMain_onceOnboardingIsComplete() {
    val status = SessionStatus.Authenticated(session, SessionSource.Storage)
    assertEquals(GateDestination.POST_AUTH_SETUP, gateDestination(status, false))
    assertEquals(GateDestination.MAIN, gateDestination(status, true))
  }

  @Test
  fun networkError_neverBlocksTheApp() {
    // Rule 1: NetworkError means a stored session exists but couldn't refresh — the user is
    // simply offline. They must land in the app, not on a login wall.
    assertEquals(GateDestination.MAIN, gateDestination(SessionStatus.NetworkError, true))
    assertEquals(GateDestination.POST_AUTH_SETUP, gateDestination(SessionStatus.NetworkError, false))
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
