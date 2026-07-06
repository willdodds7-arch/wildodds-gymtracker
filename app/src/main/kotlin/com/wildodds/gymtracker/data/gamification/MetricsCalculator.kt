package com.wildodds.gymtracker.data.gamification

import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.db.entity.WorkoutLog
import com.wildodds.gymtracker.data.db.entity.HabitCompletion
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A single logged set, joined to its exercise name + session + date — fuel for the side quests. */
private data class SetFact(
  val sessionKey: Pair<Long, Int>,
  val nameLower: String,
  val weight: Float,
  val reps: Int,
  val date: LocalDate,
  val millis: Long
)

/**
 * Pure reduction of raw local rows into a [MetricsSnapshot] for the achievement engine. No DB, no
 * Android — the repository reads the rows, this computes the numbers, the engine decides. Fully
 * testable with hand-built lists.
 */
object MetricsCalculator {

  private const val ON_DEMAND_PROGRAM_NAME = "__on_demand__"

  fun compute(
    programs: List<Program>,
    sessions: List<Session>,
    exercises: List<Exercise>,
    workoutLogs: List<WorkoutLog>,
    setLogs: List<SetLog>,
    completions: List<SessionCompletion>,
    habitCompletions: List<HabitCompletion>,
    usedTravelMode: Boolean,
    today: LocalDate,
    zone: ZoneId
  ): MetricsSnapshot {
    val workoutDates = completions
      .map { Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() }
      .toSet()

    val totalVolume = setLogs
      .sumOf { ((it.weightKg ?: 0f) * (it.reps ?: 0)).toDouble() }
      .toLong()

    val exerciseById = exercises.associateBy { it.id }
    val logById = workoutLogs.associateBy { it.id }
    val distinctExercises = setLogs
      .mapNotNull { logById[it.workoutLogId]?.exerciseId?.let { id -> exerciseById[id]?.name } }
      .toSet().size

    val habitBest = habitCompletions
      .groupBy { it.habitId }
      .map { (_, comps) -> StreakCalculator.longestStreak(comps.mapNotNull { parseDate(it) }) }
      .maxOrNull() ?: 0

    // A program is "completed" when every one of its session rows has at least one completion.
    val completedSessionIds = completions.map { it.sessionId }.toSet()
    val realProgramIds = programs.filterNot { it.name == ON_DEMAND_PROGRAM_NAME }.map { it.id }.toSet()
    val programsCompleted = sessions
      .filter { it.programId in realProgramIds }
      .groupBy { it.programId }
      .count { (_, sess) -> sess.isNotEmpty() && sess.all { it.id in completedSessionIds } }

    // ── Side quests ──────────────────────────────────────────────────────────
    val facts: List<SetFact> = setLogs.mapNotNull { s ->
      val log = logById[s.workoutLogId] ?: return@mapNotNull null
      val name = exerciseById[log.exerciseId]?.name ?: return@mapNotNull null
      val w = s.weightKg ?: return@mapNotNull null
      val r = s.reps ?: return@mapNotNull null
      SetFact(
        sessionKey = log.sessionId to log.weekNumber,
        nameLower = name.lowercase(),
        weight = w, reps = r,
        date = Instant.ofEpochMilli(log.completedAt).atZone(zone).toLocalDate(),
        millis = log.completedAt
      )
    }

    val benched100 = facts.any { "bench" in it.nameLower && it.weight >= 100f && it.reps >= 1 }
    val didBulgarian = facts.any { "bulgarian" in it.nameLower }

    val squatFacts = facts.filter { "squat" in it.nameLower }
    val estSquat1rm = squatFacts.maxOfOrNull { it.weight * (1f + it.reps / 30f) } ?: 0f
    val quadratic = estSquat1rm > 0f && squatFacts.groupBy { it.sessionKey }.any { (_, sets) ->
      sets.count { it.reps >= 10 && it.weight >= 0.40f * estSquat1rm } >= 10
    }

    val squatDays = squatFacts.map { it.date }.toSet()
    val squatEveryDayForWeek = hasConsecutiveRun(squatDays, 7)

    val cutoff = today.minusDays(30)
    val earliestTraining = facts.map { it.date }.minOrNull()
    val skippedCalves = earliestTraining != null && !earliestTraining.isAfter(cutoff) &&
      facts.none { ("calf" in it.nameLower || "calves" in it.nameLower) && !it.date.isBefore(cutoff) }

    val armTokens = listOf("curl", "bicep", "tricep", "pushdown", "push-down", "skull",
      "preacher", "hammer", "kickback", "forearm", "wrist")
    val armsOnly = facts.groupBy { it.sessionKey }.any { (_, sets) ->
      sets.isNotEmpty() && sets.all { f -> armTokens.any { it in f.nameLower } }
    }

    val einstein = facts.groupBy { it.nameLower }.any { (_, sets) ->
      val ordered = sets.sortedWith(compareBy({ it.date }, { it.millis }))
      var prevMax = 0f; var seen = false; var hit = false
      for (f in ordered) {
        if (!seen) { prevMax = f.weight; seen = true; continue }
        if (f.weight > prevMax) {
          val delta = f.weight - prevMax
          if (delta > 0f && delta < 1f) hit = true
          prevMax = f.weight
        }
      }
      hit
    }

    val midnight = completions.any { Instant.ofEpochMilli(it.completedAt).atZone(zone).hour == 0 }
    val high200 = completions.any { (it.peakHeartRate ?: 0) >= 200 }
    val lowZone = completions.any { c ->
      val peak = c.peakHeartRate
      c.avgHeartRate != null && peak != null && peak in 1..129
    }

    return MetricsSnapshot(
      currentStreakDays = StreakCalculator.currentStreak(workoutDates, today),
      longestStreakDays = StreakCalculator.longestStreak(workoutDates),
      sessionsCompleted = completions.size,
      totalVolumeKg = totalVolume,
      habitBestStreakDays = habitBest,
      distinctExercises = distinctExercises,
      programsCompleted = programsCompleted,
      usedTravelMode = usedTravelMode,
      didQuadraticFormula = quadratic,
      benched100 = benched100,
      skippedCalvesForMonth = skippedCalves,
      squattedEveryDayForWeek = squatEveryDayForWeek,
      trainedAtWombatHours = midnight,
      stayedBelowZone3 = lowZone,
      trainedArmsOnly = armsOnly,
      reached200Bpm = high200,
      didBulgarianSplitSquat = didBulgarian,
      einsteinProgress = einstein
    )
  }

  /** True if [days] contains some run of [n] consecutive calendar days. */
  private fun hasConsecutiveRun(days: Set<LocalDate>, n: Int): Boolean =
    days.any { start -> (0 until n).all { start.plusDays(it.toLong()) in days } }

  private fun parseDate(c: HabitCompletion): LocalDate? =
    runCatching { LocalDate.parse(c.completedDate) }.getOrNull()
}
