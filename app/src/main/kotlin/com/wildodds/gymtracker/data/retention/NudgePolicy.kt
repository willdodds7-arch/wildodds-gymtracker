package com.wildodds.gymtracker.data.retention

import java.time.Instant
import java.time.ZoneId

/** Everything the anti-nag policy needs. All times are epoch millis. */
data class NudgeContext(
  val enabled: Boolean,                 // the opt-in feature flag (default OFF)
  val risk: DropoutRisk,
  val restRecommended: Boolean,         // from Phase 4B readiness (REST) — never nag a resting user
  val lastNudgeAt: Long?,               // when we last nudged
  val snoozedUntil: Long?,              // user hit snooze
  val minIntervalHours: Int,            // frequency cap
  val quietStartHour: Int,              // inclusive, 0..23 (e.g. 21)
  val quietEndHour: Int                 // exclusive, 0..23 (e.g. 8)
)

data class NudgeDecision(val allowed: Boolean, val reason: String)

/**
 * The respectful gate for re-engagement nudges. Pure + deterministic so the anti-nag rules are
 * provable: opt-in only, never during quiet hours, never more often than the frequency cap, never
 * while snoozed, never when the user is correctly resting (4B), and only for medium/high risk. One
 * good nudge beats five ignored ones.
 */
object NudgePolicy {

  fun decide(ctx: NudgeContext, now: Long, zone: ZoneId): NudgeDecision {
    if (!ctx.enabled) return NudgeDecision(false, "Re-engagement reminders are off")
    if (ctx.snoozedUntil != null && now < ctx.snoozedUntil) return NudgeDecision(false, "Snoozed")
    if (ctx.restRecommended) return NudgeDecision(false, "Recovery suggests rest today")
    if (ctx.risk == DropoutRisk.LOW) return NudgeDecision(false, "No re-engagement needed")
    if (inQuietHours(now, zone, ctx.quietStartHour, ctx.quietEndHour)) return NudgeDecision(false, "Quiet hours")

    val lastNudge = ctx.lastNudgeAt
    if (lastNudge != null) {
      val elapsedHours = (now - lastNudge) / 3_600_000.0
      if (elapsedHours < ctx.minIntervalHours) return NudgeDecision(false, "Within frequency cap")
    }
    return NudgeDecision(true, "OK to nudge")
  }

  /** Quiet-hours window, correctly handling the overnight wrap (e.g. 21 → 8). */
  fun inQuietHours(now: Long, zone: ZoneId, startHour: Int, endHour: Int): Boolean {
    if (startHour == endHour) return false
    val hour = Instant.ofEpochMilli(now).atZone(zone).hour
    return if (startHour < endHour) hour in startHour until endHour
    else hour >= startHour || hour < endHour   // wraps past midnight
  }
}
