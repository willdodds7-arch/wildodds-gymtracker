package com.wildodds.gymtracker.data.gamification

import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.db.entity.WorkoutLog
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class MetricsCalculatorTest {

  private val zone = ZoneOffset.UTC
  private val day1 = LocalDate.of(2026, 6, 20)
  private val day2 = LocalDate.of(2026, 6, 21)
  private fun millis(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

  @Test
  fun computesVolumeStreakDistinctAndProgramCompletion() {
    val metrics = MetricsCalculator.compute(
      programs = listOf(Program(id = 1, name = "P", totalWeeks = 1)),
      sessions = listOf(
        Session(id = 1, programId = 1, weekNumber = 1, dayNumber = 1, name = "A", muscleGroups = ""),
        Session(id = 2, programId = 1, weekNumber = 1, dayNumber = 2, name = "B", muscleGroups = "")
      ),
      exercises = listOf(
        Exercise(id = 1, sessionId = 1, name = "Squat", sets = 3, repsTarget = "5", orderIndex = 0),
        Exercise(id = 2, sessionId = 2, name = "Bench", sets = 3, repsTarget = "5", orderIndex = 0)
      ),
      workoutLogs = listOf(
        WorkoutLog(id = 1, exerciseId = 1, sessionId = 1, weekNumber = 1),
        WorkoutLog(id = 2, exerciseId = 2, sessionId = 2, weekNumber = 1)
      ),
      setLogs = listOf(
        SetLog(id = 1, workoutLogId = 1, setNumber = 1, weightKg = 100f, reps = 5),  // 500
        SetLog(id = 2, workoutLogId = 2, setNumber = 1, weightKg = 60f, reps = 5)    // 300
      ),
      completions = listOf(
        SessionCompletion(id = 1, sessionId = 1, weekNumber = 1, completedAt = millis(day1)),
        SessionCompletion(id = 2, sessionId = 2, weekNumber = 1, completedAt = millis(day2))
      ),
      habitCompletions = emptyList(),
      usedTravelMode = false,
      today = day2,
      zone = zone
    )

    assertEquals(2, metrics.sessionsCompleted)
    assertEquals(800L, metrics.totalVolumeKg)
    assertEquals(2, metrics.distinctExercises)
    assertEquals(1, metrics.programsCompleted)     // every session completed
    assertEquals(2, metrics.longestStreakDays)     // two consecutive days
    assertEquals(2, metrics.currentStreakDays)
  }

  @Test
  fun incompleteProgramIsNotCounted() {
    val metrics = MetricsCalculator.compute(
      programs = listOf(Program(id = 1, name = "P", totalWeeks = 1)),
      sessions = listOf(
        Session(id = 1, programId = 1, weekNumber = 1, dayNumber = 1, name = "A", muscleGroups = ""),
        Session(id = 2, programId = 1, weekNumber = 1, dayNumber = 2, name = "B", muscleGroups = "")
      ),
      exercises = emptyList(), workoutLogs = emptyList(), setLogs = emptyList(),
      completions = listOf(SessionCompletion(id = 1, sessionId = 1, weekNumber = 1, completedAt = millis(day1))),
      habitCompletions = emptyList(), usedTravelMode = false, today = day1, zone = zone
    )
    assertEquals(0, metrics.programsCompleted)  // session 2 not completed
  }
}
