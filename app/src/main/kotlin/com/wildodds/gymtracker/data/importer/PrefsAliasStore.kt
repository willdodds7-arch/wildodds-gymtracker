package com.wildodds.gymtracker.data.importer

import android.content.Context

/**
 * Per-user learned exercise aliases (normalized parsed name → canonical name the user confirmed in
 * review). Backed by SharedPreferences so it needs no Room migration for v1, and scoped to this
 * device/user only — a coach's private shorthand never pollutes anyone else's resolution (spec §5).
 * Every review correction calls [learn], so import gets more accurate the more it's used.
 */
class PrefsAliasStore(context: Context) {

  private val prefs = context.applicationContext.getSharedPreferences("import_aliases", Context.MODE_PRIVATE)

  /** For [UniversalProgramImporter.import]'s `aliasLookup`. Key is already normalized by the resolver. */
  fun lookup(normalized: String): String? = prefs.getString(normalized, null)

  /** Record that the user's written [written] name means [canonical]. Normalizes before storing. */
  fun learn(written: String, canonical: String) {
    val key = ExerciseResolver.normalize(written)
    if (key.isBlank() || canonical.isBlank() || key == canonical.lowercase()) return
    prefs.edit().putString(key, canonical).apply()
  }

  val asLookup: (String) -> String? get() = ::lookup
}
