package com.wildodds.gymtracker.data.sync

import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.ProgramPhase
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SessionCompletion
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.db.entity.WorkoutLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Entity ⇄ payload JSON. Parent references travel as the PARENT'S syncId (local autoincrement
 * ids collide across devices); the engine resolves them back to local ids on apply. Local `id`,
 * `syncId` and `updatedAt` are carried at the SyncRow level, never inside the payload.
 * Unknown fields are ignored and missing fields fall back to defaults, so payloads stay
 * forward/backward compatible across app versions (mirrors the additive-Room-migration rule).
 */
object SyncPayloads {

  private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull
  private fun JsonObject.int(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull
  private fun JsonObject.lng(key: String): Long? = this[key]?.jsonPrimitive?.longOrNull
  private fun JsonObject.flt(key: String): Float? = this[key]?.jsonPrimitive?.floatOrNull
  private fun JsonObject.bool(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

  // ── programs ───────────────────────────────────────────────────────────────
  fun fromProgram(p: Program): JsonObject = buildJsonObject {
    put("name", p.name); put("totalWeeks", p.totalWeeks); put("createdAt", p.createdAt)
    put("coverImage", p.coverImage); put("isFlexible", p.isFlexible); put("category", p.category)
    put("isActive", p.isActive); put("isUserCreated", p.isUserCreated)
    put("trackRpe", p.trackRpe); put("trackOneRm", p.trackOneRm); put("isPaused", p.isPaused)
    put("currentPhase", p.currentPhase); put("description", p.description)
    put("coach", p.coach); put("coachBio", p.coachBio); put("daysPerWeek", p.daysPerWeek)
    put("split", p.split); put("style", p.style)
  }

  fun toProgram(o: JsonObject, syncId: String, updatedAt: Long, localId: Long): Program = Program(
    id = localId,
    name = o.str("name").orEmpty(),
    totalWeeks = o.int("totalWeeks") ?: 1,
    createdAt = o.lng("createdAt") ?: 0L,
    coverImage = o.str("coverImage"),
    isFlexible = o.bool("isFlexible") ?: false,
    category = o.str("category").orEmpty(),
    isActive = o.bool("isActive") ?: false,
    isUserCreated = o.bool("isUserCreated") ?: false,
    trackRpe = o.bool("trackRpe") ?: false,
    trackOneRm = o.bool("trackOneRm") ?: false,
    isPaused = o.bool("isPaused") ?: false,
    currentPhase = o.int("currentPhase") ?: 1,
    description = o.str("description").orEmpty(),
    coach = o.str("coach").orEmpty(),
    coachBio = o.str("coachBio").orEmpty(),
    daysPerWeek = o.int("daysPerWeek") ?: 0,
    split = o.str("split").orEmpty(),
    style = o.str("style").orEmpty(),
    syncId = syncId,
    updatedAt = updatedAt
  )

  // ── program_phases ─────────────────────────────────────────────────────────
  fun fromProgramPhase(p: ProgramPhase, programSyncId: String): JsonObject = buildJsonObject {
    put("programSyncId", programSyncId)
    put("phaseNumber", p.phaseNumber); put("name", p.name)
    put("durationWeeks", p.durationWeeks); put("focus", p.focus)
  }

  fun toProgramPhase(o: JsonObject, syncId: String, updatedAt: Long, localId: Long, programId: Long): ProgramPhase =
    ProgramPhase(
      id = localId, programId = programId,
      phaseNumber = o.int("phaseNumber") ?: 1,
      name = o.str("name").orEmpty(),
      durationWeeks = o.int("durationWeeks") ?: 1,
      focus = o.str("focus").orEmpty(),
      syncId = syncId, updatedAt = updatedAt
    )

  // ── sessions ───────────────────────────────────────────────────────────────
  fun fromSession(s: Session, programSyncId: String): JsonObject = buildJsonObject {
    put("programSyncId", programSyncId)
    put("weekNumber", s.weekNumber); put("dayNumber", s.dayNumber)
    put("name", s.name); put("muscleGroups", s.muscleGroups); put("phaseNumber", s.phaseNumber)
  }

  fun toSession(o: JsonObject, syncId: String, updatedAt: Long, localId: Long, programId: Long): Session = Session(
    id = localId, programId = programId,
    weekNumber = o.int("weekNumber") ?: 1,
    dayNumber = o.int("dayNumber") ?: 1,
    name = o.str("name").orEmpty(),
    muscleGroups = o.str("muscleGroups").orEmpty(),
    phaseNumber = o.int("phaseNumber") ?: 1,
    syncId = syncId, updatedAt = updatedAt
  )

  // ── exercises ──────────────────────────────────────────────────────────────
  fun fromExercise(e: Exercise, sessionSyncId: String): JsonObject = buildJsonObject {
    put("sessionSyncId", sessionSyncId)
    put("name", e.name); put("sets", e.sets); put("repsTarget", e.repsTarget)
    put("notes", e.notes); put("orderIndex", e.orderIndex)
    put("rpeTarget", e.rpeTarget); put("pct1rmTarget", e.pct1rmTarget)
    put("supersetGroupId", e.supersetGroupId); put("unilateralMode", e.unilateralMode)
  }

  fun toExercise(o: JsonObject, syncId: String, updatedAt: Long, localId: Long, sessionId: Long): Exercise = Exercise(
    id = localId, sessionId = sessionId,
    name = o.str("name").orEmpty(),
    sets = o.int("sets") ?: 1,
    repsTarget = o.str("repsTarget").orEmpty(),
    notes = o.str("notes").orEmpty(),
    orderIndex = o.int("orderIndex") ?: 0,
    rpeTarget = o.str("rpeTarget").orEmpty(),
    pct1rmTarget = o.str("pct1rmTarget").orEmpty(),
    supersetGroupId = o.int("supersetGroupId"),
    unilateralMode = o.int("unilateralMode") ?: 0,
    syncId = syncId, updatedAt = updatedAt
  )

  // ── workout_logs ───────────────────────────────────────────────────────────
  fun fromWorkoutLog(w: WorkoutLog, exerciseSyncId: String, sessionSyncId: String): JsonObject = buildJsonObject {
    put("exerciseSyncId", exerciseSyncId); put("sessionSyncId", sessionSyncId)
    put("weekNumber", w.weekNumber); put("completedAt", w.completedAt)
    put("progressionChoice", w.progressionChoice)
  }

  fun toWorkoutLog(
    o: JsonObject, syncId: String, updatedAt: Long, localId: Long, exerciseId: Long, sessionId: Long
  ): WorkoutLog = WorkoutLog(
    id = localId, exerciseId = exerciseId, sessionId = sessionId,
    weekNumber = o.int("weekNumber") ?: 1,
    completedAt = o.lng("completedAt") ?: 0L,
    progressionChoice = o.str("progressionChoice"),
    syncId = syncId, updatedAt = updatedAt
  )

  // ── set_logs ───────────────────────────────────────────────────────────────
  fun fromSetLog(s: SetLog, workoutLogSyncId: String): JsonObject = buildJsonObject {
    put("workoutLogSyncId", workoutLogSyncId)
    put("setNumber", s.setNumber); put("weightKg", s.weightKg); put("reps", s.reps)
    put("rpe", s.rpe); put("pct1rm", s.pct1rm)
    put("weightRightKg", s.weightRightKg); put("repsRight", s.repsRight)
  }

  fun toSetLog(o: JsonObject, syncId: String, updatedAt: Long, localId: Long, workoutLogId: Long): SetLog = SetLog(
    id = localId, workoutLogId = workoutLogId,
    setNumber = o.int("setNumber") ?: 1,
    weightKg = o.flt("weightKg"),
    reps = o.int("reps"),
    rpe = o.flt("rpe"),
    pct1rm = o.flt("pct1rm"),
    weightRightKg = o.flt("weightRightKg"),
    repsRight = o.int("repsRight"),
    syncId = syncId, updatedAt = updatedAt
  )

  // ── session_completions ────────────────────────────────────────────────────
  fun fromCompletion(c: SessionCompletion, sessionSyncId: String): JsonObject = buildJsonObject {
    put("sessionSyncId", sessionSyncId)
    put("weekNumber", c.weekNumber); put("completedAt", c.completedAt)
    put("durationSeconds", c.durationSeconds); put("strainRating", c.strainRating)
    put("avgHeartRate", c.avgHeartRate); put("peakHeartRate", c.peakHeartRate)
    put("hrSeries", c.hrSeries); put("fatigueScore", c.fatigueScore)
  }

  fun toCompletion(o: JsonObject, syncId: String, updatedAt: Long, localId: Long, sessionId: Long): SessionCompletion =
    SessionCompletion(
      id = localId, sessionId = sessionId,
      weekNumber = o.int("weekNumber") ?: 1,
      completedAt = o.lng("completedAt") ?: 0L,
      durationSeconds = o.lng("durationSeconds"),
      strainRating = o.int("strainRating"),
      avgHeartRate = o.int("avgHeartRate"),
      peakHeartRate = o.int("peakHeartRate"),
      hrSeries = o.str("hrSeries"),
      fatigueScore = o.int("fatigueScore"),
      syncId = syncId, updatedAt = updatedAt
    )
}
