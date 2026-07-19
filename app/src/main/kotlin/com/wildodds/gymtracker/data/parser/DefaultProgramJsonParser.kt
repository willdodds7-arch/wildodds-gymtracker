package com.wildodds.gymtracker.data.parser

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import java.io.InputStream
import java.io.InputStreamReader

/**
 * Parses the bundled `blocks.json` catalogue into a list of [ParsedProgram] "blocks".
 *
 * Two block shapes are supported:
 *  - SINGLE-WEEK (`days`): the block describes ONE representative training week. It runs for
 *    [DEFAULT_BLOCK_WEEKS] weeks — that single week is repeated, and the app's carry-forward +
 *    progression engine handles week-to-week load changes. This keeps the in-memory catalogue light
 *    across 800+ blocks; the weeks are physically materialised at import time (GymRepository).
 *  - PER-WEEK (`weeks`): the block lists every week explicitly, each with its own `days`. This lets a
 *    program prescribe genuinely different exercises / reps / intensities per week (e.g. the Candito
 *    6-week wave). These weeks are materialised as-is at import (repeatWeeks = 1).
 *
 * A block may use either `days` or `weeks`; if both are present, `weeks` wins.
 */
class DefaultProgramJsonParser(private val context: Context) {

  fun parse(): List<ParsedProgram> =
    try {
      context.assets.open(ASSET_NAME).use { parseStream(it) }
    } catch (_: Exception) {
      emptyList()
    }

  companion object {
    const val ASSET_NAME = "blocks.json"

    /** Parse from an arbitrary stream (the asset, or a test fixture). A bad block never aborts the rest. */
    internal fun parseStream(stream: InputStream): List<ParsedProgram> =
      try {
        val lib = InputStreamReader(stream, Charsets.UTF_8).use { reader ->
          Gson().fromJson(reader, JsonLibrary::class.java)
        }
        lib?.programs.orEmpty().mapIndexedNotNull { idx, block -> block.toParsedProgram(idx) }
      } catch (_: Exception) {
        emptyList()
      }
    /** Default length of a single-week block, in weeks. */
    const val DEFAULT_BLOCK_WEEKS = 4

    /** Map a block's style string to the app's coarse category bucket. */
    internal fun styleToCategory(style: String): String = when {
      style.contains("Hypertrophy", ignoreCase = true)   -> "Hypertrophy"
      style.contains("Powerbuilding", ignoreCase = true) -> "Powerbuilding"
      style.contains("Powerlifting", ignoreCase = true)  -> "Powerlifting"
      style.contains("Strength", ignoreCase = true)      -> "Strength"
      else -> "Strength"
    }
  }

  // ── JSON DTOs (Gson) ────────────────────────────────────────────────────────

  internal data class JsonLibrary(
    val programs: List<JsonBlock> = emptyList()
  )

  internal data class JsonBlock(
    val name: String = "",
    val author: String = "",
    val type: String = "",
    val split: String = "",
    val style: String = "",
    val specialisation: String = "",
    val description: String = "",
    // Single-week shape: one representative week, repeated at import.
    val days: List<JsonDay> = emptyList(),
    // Override the repeat length for a single-week (`days`) block. 0 = default block length.
    @SerializedName("block_weeks") val blockWeeks: Int = 0,
    // Per-week shape: every week listed explicitly (takes precedence over `days`).
    val weeks: List<JsonWeek> = emptyList(),
    // The whole program can be run on a different main lift (e.g. Russian Squat Routine on OHP):
    // the start flow offers a lift picker and rewrites the main-lift exercises before import.
    @SerializedName("lift_swappable") val liftSwappable: Boolean = false
  )

  internal data class JsonWeek(
    val name: String = "",
    val days: List<JsonDay> = emptyList()
  )

  internal data class JsonDay(
    val name: String = "",
    val exercises: List<JsonExercise> = emptyList()
  )

  internal data class JsonExercise(
    val name: String = "",
    val sets: Int = 3,
    @SerializedName("target_reps") val targetReps: String? = null,
    @SerializedName("percent_1rm") val percent1rm: String? = null,
    val rpe: String? = null,
    val notes: String? = null
  )
}

/** Map a JSON day to a [ParsedSession] for a given (1-based) week and day position. */
private fun DefaultProgramJsonParser.JsonDay.toParsedSession(weekNumber: Int, dayIdx: Int): ParsedSession? {
  val exercises = exercises
    .filter { it.name.isNotBlank() }
    .mapIndexed { i, ex ->
      ParsedExercise(
        name = ex.name.trim(),
        sets = ex.sets.coerceAtLeast(1),
        repsTarget = ex.targetReps?.trim()?.ifBlank { null } ?: "8-12",
        notes = ex.notes?.trim().orEmpty(),
        orderIndex = i,
        rpeTarget = ex.rpe?.trim().orEmpty(),
        pct1rmTarget = ex.percent1rm?.trim().orEmpty()
      )
    }
  if (exercises.isEmpty()) return null
  return ParsedSession(
    weekNumber = weekNumber,
    dayNumber = dayIdx + 1,
    name = name.trim().ifBlank { "Day ${dayIdx + 1}" },
    muscleGroups = exercises.take(3).joinToString(" / ") { it.name.split(" ").firstOrNull() ?: it.name },
    exercises = exercises
  )
}

/** Pure mapping from a JSON block to a [ParsedProgram] (JVM-only, fully testable). */
internal fun DefaultProgramJsonParser.JsonBlock.toParsedProgram(index: Int): ParsedProgram? {
  if (name.isBlank()) return null

  val perWeek = weeks.isNotEmpty()

  val sessions: List<ParsedSession> =
    if (perWeek) {
      weeks.flatMapIndexed { wIdx, week ->
        week.days.mapIndexedNotNull { dayIdx, day -> day.toParsedSession(wIdx + 1, dayIdx) }
      }
    } else {
      days.mapIndexedNotNull { dayIdx, day -> day.toParsedSession(1, dayIdx) }
    }

  if (sessions.isEmpty()) return null

  val tracksOneRm = sessions.any { s -> s.exercises.any { it.pct1rmTarget.isNotBlank() } }
  val tracksRpe   = sessions.any { s -> s.exercises.any { it.rpeTarget.isNotBlank() } }
  val daysPerWeek = sessions.filter { it.weekNumber == 1 }.size

  // Per-week blocks are already materialised across N weeks (use as-is). Single-week blocks
  // repeat for `block_weeks` (or the default block length).
  val weekCount = when {
    perWeek        -> weeks.size
    blockWeeks > 0 -> blockWeeks
    else           -> DefaultProgramJsonParser.DEFAULT_BLOCK_WEEKS
  }

  return ParsedProgram(
    name = name.trim(),
    totalWeeks = weekCount,
    sessions = sessions,
    coverImage = ((index % 5) + 1).toString(),
    category = DefaultProgramJsonParser.styleToCategory(style),
    isUserCreated = false,
    trackRpe = tracksRpe,
    trackOneRm = tracksOneRm,
    description = description.trim(),
    coach = author.trim(),
    coachBio = "",
    daysPerWeek = daysPerWeek,
    split = split.trim(),
    style = style.trim(),
    repeatWeeks = if (perWeek) 1 else weekCount,
    liftSwappable = liftSwappable
  )
}
