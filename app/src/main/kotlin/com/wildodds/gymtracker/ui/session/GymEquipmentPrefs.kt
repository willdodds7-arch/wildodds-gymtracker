package com.wildodds.gymtracker.ui.session

import android.content.Context
import com.wildodds.gymtracker.data.intelligence.Equipment

/**
 * Remembers the user's available gym equipment so the swap sheet isn't re-filled every session.
 * Stored GLOBALLY in the existing "gym_prefs" SharedPreferences (one regular gym is the common
 * case; a per-program store would add friction for little gain).
 */
object GymEquipmentPrefs {

  private const val PREFS = "gym_prefs"
  private const val KEY = "available_equipment"

  /** A typical fully-equipped gym. */
  val DEFAULT: Set<Equipment> = setOf(
  Equipment.BARBELL, Equipment.DUMBBELL, Equipment.MACHINE, Equipment.CABLE, Equipment.BODYWEIGHT
  )

  fun load(context: Context): Set<Equipment> {
  val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
  .getStringSet(KEY, null) ?: return DEFAULT
  return stored.mapNotNull { runCatching { Equipment.valueOf(it) }.getOrNull() }.toSet()
  .ifEmpty { DEFAULT }
  }

  fun save(context: Context, equipment: Set<Equipment>) {
  context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
  .putStringSet(KEY, equipment.map { it.name }.toSet()).apply()
  }
}
