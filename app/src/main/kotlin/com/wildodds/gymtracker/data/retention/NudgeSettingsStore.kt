package com.wildodds.gymtracker.data.retention

import android.content.Context

/**
 * Bookkeeping + user controls for re-engagement nudges (SharedPreferences — no DB). Holds the
 * frequency cap, quiet-hours window, and the last-nudge / snooze timestamps the [NudgePolicy] reads.
 * The on/off itself is the `REENGAGEMENT` feature flag (opt-in, default OFF).
 */
object NudgeSettingsStore {
  private const val PREFS = "nudge_prefs"
  private const val KEY_INTERVAL = "min_interval_hours"
  private const val KEY_QUIET_START = "quiet_start"
  private const val KEY_QUIET_END = "quiet_end"
  private const val KEY_LAST_NUDGE = "last_nudge_at"
  private const val KEY_SNOOZED_UNTIL = "snoozed_until"

  const val DEFAULT_INTERVAL_HOURS = 48
  const val DEFAULT_QUIET_START = 21
  const val DEFAULT_QUIET_END = 8

  private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun minIntervalHours(c: Context) = prefs(c).getInt(KEY_INTERVAL, DEFAULT_INTERVAL_HOURS)
  fun quietStart(c: Context) = prefs(c).getInt(KEY_QUIET_START, DEFAULT_QUIET_START)
  fun quietEnd(c: Context) = prefs(c).getInt(KEY_QUIET_END, DEFAULT_QUIET_END)
  fun lastNudgeAt(c: Context): Long? = prefs(c).getLong(KEY_LAST_NUDGE, -1L).takeIf { it >= 0 }
  fun snoozedUntil(c: Context): Long? = prefs(c).getLong(KEY_SNOOZED_UNTIL, -1L).takeIf { it >= 0 }

  fun setInterval(c: Context, hours: Int) = prefs(c).edit().putInt(KEY_INTERVAL, hours).apply()
  fun setQuietHours(c: Context, start: Int, end: Int) =
    prefs(c).edit().putInt(KEY_QUIET_START, start).putInt(KEY_QUIET_END, end).apply()
  fun recordNudge(c: Context, now: Long) = prefs(c).edit().putLong(KEY_LAST_NUDGE, now).apply()
  fun snooze(c: Context, until: Long) = prefs(c).edit().putLong(KEY_SNOOZED_UNTIL, until).apply()
  fun clearSnooze(c: Context) = prefs(c).edit().remove(KEY_SNOOZED_UNTIL).apply()
}
