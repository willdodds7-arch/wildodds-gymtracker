package com.wildodds.gymtracker.data.sync

import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.db.entity.WorkoutLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure export formatting: CSV header + joined rows, JSON content, summary counts. */
class TrainingExporterTest {

  private fun snapshot(): TrainingSnapshot = TrainingSnapshot(
    programs = listOf(Program(id = 1, name = "PPL", totalWeeks = 6)),
    sessions = listOf(Session(id = 1, programId = 1, weekNumber = 1, dayNumber = 1, name = "Push", muscleGroups = "Chest")),
    exercises = listOf(Exercise(id = 1, sessionId = 1, name = "Bench", sets = 3, repsTarget = "5", orderIndex = 0)),
    workoutLogs = listOf(WorkoutLog(id = 1, exerciseId = 1, sessionId = 1, weekNumber = 1, completedAt = 0L)),
    setLogs = listOf(
      SetLog(id = 1, workoutLogId = 1, setNumber = 1, weightKg = 100f, reps = 5),
      SetLog(id = 2, workoutLogId = 1, setNumber = 2, weightKg = 100f, reps = 4, rpe = 8f)
    )
  )

  @Test
  fun csvHasHeaderAndJoinedRows() {
    val lines = TrainingExporter.toCsv(snapshot()).trim().lines()
    assertEquals(TrainingExporter.CSV_HEADER.joinToString(","), lines[0])
    assertEquals(3, lines.size)  // header + 2 sets
    // program,week,day,session,exercise,set,weightKg,reps,rpe,pct1rm,completedAt
    assertTrue(lines[1].startsWith("PPL,1,1,Push,Bench,1,100,5,,,"))
    assertTrue(lines[2].startsWith("PPL,1,1,Push,Bench,2,100,4,8,,"))
  }

  @Test
  fun csvEscapesCommas() {
    val snap = snapshot().copy(programs = listOf(Program(id = 1, name = "Push, Pull, Legs", totalWeeks = 6)))
    val line = TrainingExporter.toCsv(snap).trim().lines()[1]
    assertTrue("comma-containing field must be quoted", line.startsWith("\"Push, Pull, Legs\","))
  }

  @Test
  fun jsonContainsTheData() {
    val json = TrainingExporter.toJson(snapshot())
    assertTrue(json.contains("\"PPL\""))
    assertTrue(json.contains("\"Bench\""))
  }

  @Test
  fun summaryCountsSetsAndVolume() {
    val summary = TrainingExporter.summaryText(snapshot())
    assertTrue(summary.contains("Sets logged: 2"))
    assertTrue(summary.contains("Total volume: 900 kg"))  // 100*5 + 100*4
  }
}
