package com.wildodds.gymtracker.data.sync

import com.wildodds.gymtracker.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Offline-first sync: Room stays the source of truth; this engine reconciles it with the
 * server when a connection exists. PUSH-then-PULL, batched, last-write-wins on `updatedAt`:
 *
 *  - PUSH sends every local row changed since the push cursor (plus tombstones). The server
 *    only replaces rows that are strictly older (see sync_push in the SQL migration), so a
 *    concurrent newer edit from another device is never clobbered.
 *  - PULL applies everything past the pull cursor, in parent→child order, skipping any row
 *    the local DB has a newer version of (that version wins on the next push).
 *
 * Trade-offs (documented, accepted for a single-user personal app): LWW keyed on client
 * clocks — skewed clocks can pick the "wrong" winner between near-simultaneous edits; an
 * update that post-dates a delete resurrects the row. No merge UI, no vector clocks.
 */
class SyncEngine(
  private val db: AppDatabase,
  private val backend: SyncBackend,
  private val cursors: SyncCursorStore,
  private val batchSize: Int = 500
) {

  sealed class Result {
    data object Success : Result()
    data class Failure(val message: String) : Result()
  }

  suspend fun syncNow(): Result = withContext(Dispatchers.IO) {
    SyncStatus.update { it.copy(phase = SyncPhase.RUNNING, message = "") }
    try {
      push()
      pull()
      cursors.lastSyncAt = System.currentTimeMillis()
      SyncStatus.update { it.copy(phase = SyncPhase.OK, lastSyncAt = cursors.lastSyncAt) }
      Result.Success
    } catch (e: Exception) {
      // Rule 1: sync failure is a non-event for the app — data stays local, we retry later.
      val msg = e.message ?: e::class.simpleName.orEmpty()
      SyncStatus.update { it.copy(phase = SyncPhase.FAILED, message = msg) }
      Result.Failure(msg)
    }
  }

  // ── Push ──────────────────────────────────────────────────────────────────────

  private suspend fun push() {
    val dao = db.syncDao()
    val backup = db.backupDao()
    val since = cursors.lastPushedAt

    // id → syncId maps for resolving parent references in payloads. Full-table reads are fine
    // at personal-training-log scale (a few thousand rows).
    val programSync = backup.programs().associate { it.id to it.syncId }
    val sessionSync = backup.sessions().associate { it.id to it.syncId }
    val exerciseSync = backup.exercises().associate { it.id to it.syncId }
    val workoutLogSync = backup.workoutLogs().associate { it.id to it.syncId }

    val rows = mutableListOf<SyncRow>()
    var maxStamp = since

    fun track(stamp: Long) { if (stamp > maxStamp) maxStamp = stamp }

    dao.programsSince(since).forEach { p ->
      if (p.syncId.isEmpty()) return@forEach
      rows += SyncRow("programs", p.syncId, p.updatedAt, payload = SyncPayloads.fromProgram(p)); track(p.updatedAt)
    }
    dao.programPhasesSince(since).forEach { p ->
      val parent = programSync[p.programId] ?: return@forEach
      rows += SyncRow("program_phases", p.syncId, p.updatedAt, payload = SyncPayloads.fromProgramPhase(p, parent)); track(p.updatedAt)
    }
    dao.sessionsSince(since).forEach { s ->
      val parent = programSync[s.programId] ?: return@forEach
      rows += SyncRow("sessions", s.syncId, s.updatedAt, payload = SyncPayloads.fromSession(s, parent)); track(s.updatedAt)
    }
    dao.exercisesSince(since).forEach { e ->
      val parent = sessionSync[e.sessionId] ?: return@forEach
      rows += SyncRow("exercises", e.syncId, e.updatedAt, payload = SyncPayloads.fromExercise(e, parent)); track(e.updatedAt)
    }
    dao.workoutLogsSince(since).forEach { w ->
      val ex = exerciseSync[w.exerciseId] ?: return@forEach
      val se = sessionSync[w.sessionId] ?: return@forEach
      rows += SyncRow("workout_logs", w.syncId, w.updatedAt, payload = SyncPayloads.fromWorkoutLog(w, ex, se)); track(w.updatedAt)
    }
    dao.setLogsSince(since).forEach { s ->
      val parent = workoutLogSync[s.workoutLogId] ?: return@forEach
      rows += SyncRow("set_logs", s.syncId, s.updatedAt, payload = SyncPayloads.fromSetLog(s, parent)); track(s.updatedAt)
    }
    dao.completionsSince(since).forEach { c ->
      val parent = sessionSync[c.sessionId] ?: return@forEach
      rows += SyncRow("session_completions", c.syncId, c.updatedAt, payload = SyncPayloads.fromCompletion(c, parent)); track(c.updatedAt)
    }
    dao.tombstonesSince(since).forEach { t ->
      rows += SyncRow(t.entityType, t.syncId, t.deletedAt, deletedAt = t.deletedAt,
        payload = kotlinx.serialization.json.JsonObject(emptyMap()))
      track(t.deletedAt)
    }

    rows.chunked(batchSize).forEach { backend.push(it) }
    // Advance only over what was actually read — anything written mid-push has a newer stamp
    // and is picked up next time.
    cursors.lastPushedAt = maxStamp
  }

  // ── Pull ──────────────────────────────────────────────────────────────────────

  private suspend fun pull() {
    while (true) {
      val batch = backend.pull(cursors.lastPullSeq, batchSize)
      if (batch.isEmpty()) return

      // Deletes first (a delete tombstone always outranks the row's own state locally —
      // if the delete was actually older, the newer live row re-arrives on a later push).
      batch.filter { it.deletedAt != null }.forEach { applyDelete(it) }

      // Then live rows, parent→child so FK targets exist by the time children apply.
      val live = batch.filter { it.deletedAt == null }.groupBy { it.entityType }
      ENTITY_ORDER.forEach { type -> live[type]?.forEach { applyLive(it) } }

      cursors.lastPullSeq = batch.maxOf { it.seq }
      if (batch.size < batchSize) return
    }
  }

  private suspend fun applyDelete(row: RemoteSyncRow) {
    val dao = db.syncDao()
    when (row.entityType) {
      "programs" -> dao.deleteProgramBySyncId(row.syncId)
      "program_phases" -> dao.deleteProgramPhaseBySyncId(row.syncId)
      "sessions" -> dao.deleteSessionBySyncId(row.syncId)
      "exercises" -> dao.deleteExerciseBySyncId(row.syncId)
      "workout_logs" -> dao.deleteWorkoutLogBySyncId(row.syncId)
      "set_logs" -> dao.deleteSetLogBySyncId(row.syncId)
      "session_completions" -> dao.deleteCompletionBySyncId(row.syncId)
    }
  }

  private suspend fun applyLive(row: RemoteSyncRow) {
    val dao = db.syncDao()
    val o = row.payload
    fun parentSyncId(key: String): String? = o[key]?.jsonPrimitive?.contentOrNull

    when (row.entityType) {
      "programs" -> {
        val existing = dao.programBySyncId(row.syncId)
        if (existing == null) dao.insertProgram(SyncPayloads.toProgram(o, row.syncId, row.updatedAt, 0))
        else if (row.updatedAt > existing.updatedAt)
          dao.updateProgram(SyncPayloads.toProgram(o, row.syncId, row.updatedAt, existing.id))
      }
      "program_phases" -> {
        val parent = parentSyncId("programSyncId")?.let { dao.programBySyncId(it) } ?: return
        val existing = dao.programPhaseBySyncId(row.syncId)
        if (existing == null) dao.insertProgramPhase(SyncPayloads.toProgramPhase(o, row.syncId, row.updatedAt, 0, parent.id))
        else if (row.updatedAt > existing.updatedAt)
          dao.updateProgramPhase(SyncPayloads.toProgramPhase(o, row.syncId, row.updatedAt, existing.id, parent.id))
      }
      "sessions" -> {
        val parent = parentSyncId("programSyncId")?.let { dao.programBySyncId(it) } ?: return
        val existing = dao.sessionBySyncId(row.syncId)
        if (existing == null) dao.insertSession(SyncPayloads.toSession(o, row.syncId, row.updatedAt, 0, parent.id))
        else if (row.updatedAt > existing.updatedAt)
          dao.updateSession(SyncPayloads.toSession(o, row.syncId, row.updatedAt, existing.id, parent.id))
      }
      "exercises" -> {
        val parent = parentSyncId("sessionSyncId")?.let { dao.sessionBySyncId(it) } ?: return
        val existing = dao.exerciseBySyncId(row.syncId)
        if (existing == null) dao.insertExercise(SyncPayloads.toExercise(o, row.syncId, row.updatedAt, 0, parent.id))
        else if (row.updatedAt > existing.updatedAt)
          dao.updateExercise(SyncPayloads.toExercise(o, row.syncId, row.updatedAt, existing.id, parent.id))
      }
      "workout_logs" -> {
        val ex = parentSyncId("exerciseSyncId")?.let { dao.exerciseBySyncId(it) } ?: return
        val se = parentSyncId("sessionSyncId")?.let { dao.sessionBySyncId(it) } ?: return
        val existing = dao.workoutLogBySyncId(row.syncId)
        if (existing == null) dao.insertWorkoutLog(SyncPayloads.toWorkoutLog(o, row.syncId, row.updatedAt, 0, ex.id, se.id))
        else if (row.updatedAt > existing.updatedAt)
          dao.updateWorkoutLog(SyncPayloads.toWorkoutLog(o, row.syncId, row.updatedAt, existing.id, ex.id, se.id))
      }
      "set_logs" -> {
        val parent = parentSyncId("workoutLogSyncId")?.let { dao.workoutLogBySyncId(it) } ?: return
        val existing = dao.setLogBySyncId(row.syncId)
        if (existing == null) dao.insertSetLog(SyncPayloads.toSetLog(o, row.syncId, row.updatedAt, 0, parent.id))
        else if (row.updatedAt > existing.updatedAt)
          dao.updateSetLog(SyncPayloads.toSetLog(o, row.syncId, row.updatedAt, existing.id, parent.id))
      }
      "session_completions" -> {
        val parent = parentSyncId("sessionSyncId")?.let { dao.sessionBySyncId(it) } ?: return
        val existing = dao.completionBySyncId(row.syncId)
        if (existing == null) dao.insertCompletion(SyncPayloads.toCompletion(o, row.syncId, row.updatedAt, 0, parent.id))
        else if (row.updatedAt > existing.updatedAt)
          dao.updateCompletion(SyncPayloads.toCompletion(o, row.syncId, row.updatedAt, existing.id, parent.id))
      }
    }
  }

  companion object {
    /** Parent→child apply order. */
    val ENTITY_ORDER = listOf(
      "programs", "program_phases", "sessions", "exercises",
      "workout_logs", "set_logs", "session_completions"
    )
  }
}
