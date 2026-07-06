package com.wildodds.gymtracker.data.retention

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class NudgePolicyTest {

  private val zone = ZoneOffset.UTC
  private fun at(hour: Int): Long = LocalDate.of(2026, 6, 21).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli()

  private fun ctx(
    enabled: Boolean = true,
    risk: DropoutRisk = DropoutRisk.HIGH,
    rest: Boolean = false,
    lastNudgeAt: Long? = null,
    snoozedUntil: Long? = null
  ) = NudgeContext(enabled, risk, rest, lastNudgeAt, snoozedUntil,
    minIntervalHours = 48, quietStartHour = 21, quietEndHour = 8)

  @Test
  fun allowsAGoodNudgeWhenEverythingIsClear() {
    assertTrue(NudgePolicy.decide(ctx(), at(12), zone).allowed)
  }

  @Test
  fun neverNudgesWhenDisabled() {
    assertFalse(NudgePolicy.decide(ctx(enabled = false), at(12), zone).allowed)
  }

  @Test
  fun neverNudgesAtLowRisk() {
    assertFalse(NudgePolicy.decide(ctx(risk = DropoutRisk.LOW), at(12), zone).allowed)
  }

  @Test
  fun neverNudgesARestingUser() {
    assertFalse(NudgePolicy.decide(ctx(rest = true), at(12), zone).allowed)
  }

  @Test
  fun respectsQuietHoursIncludingOvernightWrap() {
    assertFalse("23:00 is inside 21→8 quiet", NudgePolicy.decide(ctx(), at(23), zone).allowed)
    assertFalse("03:00 is inside 21→8 quiet", NudgePolicy.decide(ctx(), at(3), zone).allowed)
    assertTrue("12:00 is outside quiet", NudgePolicy.decide(ctx(), at(12), zone).allowed)
  }

  @Test
  fun respectsTheFrequencyCap() {
    val now = at(12)
    assertFalse("within 48h cap", NudgePolicy.decide(ctx(lastNudgeAt = now - 3_600_000L), now, zone).allowed)
    assertTrue("past the cap", NudgePolicy.decide(ctx(lastNudgeAt = now - 49L * 3_600_000L), now, zone).allowed)
  }

  @Test
  fun respectsSnooze() {
    val now = at(12)
    assertFalse(NudgePolicy.decide(ctx(snoozedUntil = now + 3_600_000L), now, zone).allowed)
  }
}
