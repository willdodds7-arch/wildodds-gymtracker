package com.wildodds.gymtracker.data.sync

import com.google.gson.GsonBuilder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Pure, deterministic exports of a [TrainingSnapshot] — no Android, no I/O — so the formatting is
 * unit-tested directly. The UI writes the returned strings to a file and shares them via FileProvider.
 */
object TrainingExporter {

  private val PRETTY = GsonBuilder().setPrettyPrinting().create()

  /** One row per logged set, joined up to its exercise / session / program. */
  val CSV_HEADER = listOf(
    "program", "week", "day", "session", "exercise", "set", "weightKg", "reps", "rpe", "pct1rm", "completedAt"
  )

  fun toJson(snapshot: TrainingSnapshot): String = PRETTY.toJson(snapshot)

  fun toCsv(snapshot: TrainingSnapshot): String {
    val exercisesById = snapshot.exercises.associateBy { it.id }
    val sessionsById = snapshot.sessions.associateBy { it.id }
    val programsById = snapshot.programs.associateBy { it.id }
    val logsById = snapshot.workoutLogs.associateBy { it.id }

    val sb = StringBuilder()
    sb.append(CSV_HEADER.joinToString(",")).append("\n")

    // Stable ordering: by week, then session/day, then exercise, then set — reproducible output.
    val rows = snapshot.setLogs.mapNotNull { set ->
      val log = logsById[set.workoutLogId] ?: return@mapNotNull null
      val exercise = exercisesById[log.exerciseId]
      val session = sessionsById[log.sessionId]
      val program = session?.let { programsById[it.programId] }
      Row(
        program = program?.name ?: "",
        week = log.weekNumber,
        day = session?.dayNumber ?: 0,
        session = session?.name ?: "",
        exercise = exercise?.name ?: "",
        set = set.setNumber,
        weightKg = set.weightKg,
        reps = set.reps,
        rpe = set.rpe,
        pct1rm = set.pct1rm,
        completedAt = log.completedAt
      )
    }.sortedWith(compareBy({ it.week }, { it.day }, { it.session }, { it.exercise }, { it.set }))

    for (r in rows) {
      sb.append(listOf(
        csv(r.program), r.week.toString(), r.day.toString(), csv(r.session), csv(r.exercise),
        r.set.toString(), numable(r.weightKg), numable(r.reps), numable(r.rpe), numable(r.pct1rm),
        iso(r.completedAt)
      ).joinToString(",")).append("\n")
    }
    return sb.toString()
  }

  /** A short human-readable recap (the "shareable summary"). */
  fun summaryText(snapshot: TrainingSnapshot): String {
    val totalSets = snapshot.setLogs.count { it.weightKg != null && it.reps != null }
    val volume = snapshot.setLogs.sumOf { ((it.weightKg ?: 0f) * (it.reps ?: 0)).toDouble() }
    return buildString {
      append("Wild Odds — training export\n")
      append("Programs: ${snapshot.programs.size}\n")
      append("Sessions completed: ${snapshot.completions.size}\n")
      append("Sets logged: $totalSets\n")
      append("Total volume: ${volume.toLong()} kg")
    }
  }

  // ── helpers ────────────────────────────────────────────────────────────────────

  private data class Row(
    val program: String, val week: Int, val day: Int, val session: String, val exercise: String,
    val set: Int, val weightKg: Float?, val reps: Int?, val rpe: Float?, val pct1rm: Float?,
    val completedAt: Long
  )

  private fun numable(v: Float?): String = v?.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() } ?: ""
  private fun numable(v: Int?): String = v?.toString() ?: ""

  private fun iso(epochMillis: Long): String =
    DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toInstant())

  /** RFC-4180 CSV field escaping. */
  private fun csv(field: String): String =
    if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' })
      "\"" + field.replace("\"", "\"\"") + "\""
    else field
}
