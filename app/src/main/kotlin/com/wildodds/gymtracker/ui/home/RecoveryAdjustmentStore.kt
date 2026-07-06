package com.wildodds.gymtracker.ui.home

import android.content.Context

/**
 * A "lighter session" directive the user accepted from a recovery/adaptive suggestion. It scales the
 * next session's *prefill* (load + how many sets show) — never logged data, never the saved program —
 * so it is fully reversible: clearing it restores the normal session on the next open.
 */
data class RecoveryAdjustment(
  val loadScale: Float,
  val volumeScale: Float,
  val label: String,
  val createdAt: Long
)

/** SharedPreferences-backed store for the single active recovery adjustment (no DB schema change). */
object RecoveryAdjustmentStore {
  private const val PREFS = "recovery_adjustment"
  private const val KEY_LOAD = "load_scale"
  private const val KEY_VOLUME = "volume_scale"
  private const val KEY_LABEL = "label"
  private const val KEY_CREATED_AT = "created_at"

  fun load(context: Context): RecoveryAdjustment? {
    val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    if (!p.contains(KEY_LOAD)) return null
    return RecoveryAdjustment(
      loadScale = p.getFloat(KEY_LOAD, 1f),
      volumeScale = p.getFloat(KEY_VOLUME, 1f),
      label = p.getString(KEY_LABEL, "Lighter session") ?: "Lighter session",
      createdAt = p.getLong(KEY_CREATED_AT, 0L)
    )
  }

  fun save(context: Context, loadScale: Float, volumeScale: Float, label: String, createdAt: Long) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
      .putFloat(KEY_LOAD, loadScale)
      .putFloat(KEY_VOLUME, volumeScale)
      .putString(KEY_LABEL, label)
      .putLong(KEY_CREATED_AT, createdAt)
      .apply()
  }

  fun isActive(context: Context): Boolean =
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_LOAD)

  fun clear(context: Context) {
    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
  }
}
