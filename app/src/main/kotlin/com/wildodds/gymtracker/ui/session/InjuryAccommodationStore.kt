package com.wildodds.gymtracker.ui.session

import android.content.Context
import com.wildodds.gymtracker.data.intelligence.BodyRegion

/** The currently-active injury accommodation (so it persists across sessions and can be reverted). */
data class InjuryAccommodation(
  val region: BodyRegion,
  val avoidTags: Set<String>,
  val appliedAtMillis: Long
)

/**
 * Persists the active accommodation in the existing "gym_prefs" SharedPreferences. Stored (not a
 * session overlay) so the app can later prompt "still managing this?" and so it applies to every
 * session until reverted. Reverting just clears it — the program is never mutated.
 */
object InjuryAccommodationStore {
  private const val PREFS = "gym_prefs"
  private const val KEY_REGION = "injury_region"
  private const val KEY_TAGS = "injury_tags"
  private const val KEY_DATE = "injury_date"

  private fun sp(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

  fun save(context: Context, region: BodyRegion, avoidTags: Set<String>, nowMillis: Long) {
  sp(context).edit()
  .putString(KEY_REGION, region.name)
  .putStringSet(KEY_TAGS, avoidTags)
  .putLong(KEY_DATE, nowMillis)
  .apply()
  }

  fun load(context: Context): InjuryAccommodation? {
  val s = sp(context)
  val region = s.getString(KEY_REGION, null)
  ?.let { runCatching { BodyRegion.valueOf(it) }.getOrNull() } ?: return null
  return InjuryAccommodation(
  region = region,
  avoidTags = s.getStringSet(KEY_TAGS, emptySet()) ?: emptySet(),
  appliedAtMillis = s.getLong(KEY_DATE, 0L)
  )
  }

  fun clear(context: Context) {
  sp(context).edit().remove(KEY_REGION).remove(KEY_TAGS).remove(KEY_DATE).apply()
  }
}
