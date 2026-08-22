package com.wildodds.gymtracker.data.importer

import com.wildodds.gymtracker.data.ExerciseLibrary

/**
 * Resolves a parsed exercise name to a canonical form. In this app there is no exercise *table* —
 * exercises are free-text names and [ExerciseLibrary] is a name→muscle map — so v1 resolution is:
 * normalize → expand gym abbreviations → look up a learned alias → confirm against the built-in
 * library. The verbatim [ExerciseRef.original] is always kept. Anything not confidently matched is
 * returned INFERRED/UNRESOLVED so the review step flags it rather than silently trusting a guess.
 *
 * [aliasLookup] is injected (a pure `(normalized) -> canonical?`) so this is unit-testable; the app
 * backs it with a per-user store that learns from corrections (see [PrefsAliasStore]).
 */
object ExerciseResolver {

  private val abbreviations = mapOf(
    "bb" to "barbell", "db" to "dumbbell", "kb" to "kettlebell", "sm" to "smith machine",
    "ohp" to "overhead press", "rdl" to "romanian deadlift", "sldl" to "stiff leg deadlift",
    "gm" to "good morning", "ghr" to "glute ham raise", "bss" to "bulgarian split squat",
    "bw" to "bodyweight", "cgbp" to "close grip bench press", "pause" to "pause"
  )

  /** Lowercase, strip punctuation, expand standalone abbreviations. Stable + idempotent. */
  fun normalize(name: String): String {
    var s = name.lowercase().trim()
    s = s.replace(Regex("""[()\[\]{}]"""), " ")
    s = s.replace(Regex("""[^a-z0-9+/\- ]"""), " ")
    s = s.replace(Regex("""\s+"""), " ").trim()
    if (s.isBlank()) return s
    return s.split(" ").joinToString(" ") { abbreviations[it] ?: it }.trim()
  }

  fun resolve(original: String, aliasLookup: (String) -> String? = { null }): ExerciseRef {
    val norm = normalize(original)
    if (norm.isBlank()) return ExerciseRef(original, original.trim(), ResolutionStatus.UNRESOLVED, 0.0)

    aliasLookup(norm)?.let { canonical ->
      return ExerciseRef(original, canonical, ResolutionStatus.ALIAS, 0.98)
    }
    if (ExerciseLibrary.lookup(norm) != null || ExerciseLibrary.lookup(original) != null) {
      return ExerciseRef(original, titleCase(norm), ResolutionStatus.EXACT, 0.9)
    }
    return ExerciseRef(original, titleCase(norm), ResolutionStatus.INFERRED, 0.55)
  }

  private fun titleCase(s: String): String =
    s.split(" ").filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
}
