package com.wildodds.gymtracker.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.SyncTriggers
import com.wildodds.gymtracker.data.db.entity.Exercise
import com.wildodds.gymtracker.data.db.entity.Program
import com.wildodds.gymtracker.data.db.entity.Session
import com.wildodds.gymtracker.data.db.entity.SetLog
import com.wildodds.gymtracker.data.db.entity.WorkoutLog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

/**
 * Phase 3 gates, run as real convergence tests: two REAL Room databases ("device A"/"device B",
 * with the production change-tracking triggers installed) sharing one in-memory server that
 * implements the exact LWW semantics of the sync_push SQL function. The only faked piece is the
 * network transport; the RLS half of the gate lives in the live Supabase integration tests.
 */
@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

  private lateinit var dbA: AppDatabase
  private lateinit var dbB: AppDatabase
  private lateinit var server: FakeServer
  private lateinit var engineA: SyncEngine
  private lateinit var engineB: SyncEngine

  /** In-memory stand-in for sync_rows + sync_push: conditional LWW upsert, seq bumps on write. */
  private class FakeServer : SyncBackend {
    data class Stored(val row: SyncRow, val seq: Long)
    val rows = LinkedHashMap<Pair<String, String>, Stored>()
    private var seqCounter = 0L
    var offline = false

    override suspend fun push(rows: List<SyncRow>) {
      if (offline) throw IOException("airplane mode")
      rows.forEach { r ->
        val key = r.entityType to r.syncId
        val existing = this.rows[key]
        if (existing == null || existing.row.updatedAt < r.updatedAt) {
          this.rows[key] = Stored(r, ++seqCounter)
        }
      }
    }

    override suspend fun pull(afterSeq: Long, limit: Int): List<RemoteSyncRow> {
      if (offline) throw IOException("airplane mode")
      return rows.values.filter { it.seq > afterSeq }.sortedBy { it.seq }.take(limit)
        .map { RemoteSyncRow(it.row.entityType, it.row.syncId, it.row.updatedAt, it.row.deletedAt, it.row.payload, it.seq) }
    }
  }

  private class MemoryCursors : SyncCursorStore {
    override var lastPushedAt = 0L
    override var lastPullSeq = 0L
    override var lastSyncAt = 0L
  }

  private fun newDb(): AppDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
      .addCallback(object : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) { SyncTriggers.install(db) }
      })
      .allowMainThreadQueries()
      .build()

  @Before
  fun setUp() {
    dbA = newDb(); dbB = newDb()
    server = FakeServer()
    engineA = SyncEngine(dbA, server, MemoryCursors())
    engineB = SyncEngine(dbB, server, MemoryCursors())
  }

  @After
  fun tearDown() { dbA.close(); dbB.close() }

  /** Insert a full training graph on one device, returning the program's local id. */
  private fun seedTrainingData(db: AppDatabase): Long = runBlocking {
    val dao = db.syncDao()
    val programId = dao.insertProgram(Program(name = "Wild Odds Split", totalWeeks = 4))
    val sessionId = dao.insertSession(Session(programId = programId, weekNumber = 1, dayNumber = 1, name = "Quads", muscleGroups = "Quads"))
    val exerciseId = dao.insertExercise(Exercise(sessionId = sessionId, name = "Back Squat", sets = 2, repsTarget = "2", orderIndex = 0))
    val logId = dao.insertWorkoutLog(WorkoutLog(exerciseId = exerciseId, sessionId = sessionId, weekNumber = 1))
    dao.insertSetLog(SetLog(workoutLogId = logId, setNumber = 1, weightKg = 140f, reps = 2))
    programId
  }

  @Test
  fun triggersAssignSyncIdsAndTimestampsToLocalInserts() = runBlocking {
    val id = seedTrainingData(dbA)
    val program = dbA.backupDao().programs().first { it.id == id }
    assertTrue("syncId assigned by trigger", program.syncId.isNotEmpty())
    assertTrue("updatedAt stamped by trigger", program.updatedAt > 0)

    // And distinct rows get distinct ids.
    val id2 = runBlocking { dbA.syncDao().insertProgram(Program(name = "Second", totalWeeks = 1)) }
    val p2 = dbA.backupDao().programs().first { it.id == id2 }
    assertNotEquals(program.syncId, p2.syncId)
  }

  @Test
  fun twoDevices_convergeToTheSameTrainingGraph() = runBlocking {
    seedTrainingData(dbA)
    assertEquals(SyncEngine.Result.Success, engineA.syncNow())
    assertEquals(SyncEngine.Result.Success, engineB.syncNow())

    // B has the full graph, with FKs remapped to ITS local ids.
    val bPrograms = dbB.backupDao().programs()
    val bSessions = dbB.backupDao().sessions()
    val bExercises = dbB.backupDao().exercises()
    val bLogs = dbB.backupDao().workoutLogs()
    val bSets = dbB.backupDao().setLogs()
    assertEquals(1, bPrograms.size); assertEquals(1, bSessions.size)
    assertEquals(1, bExercises.size); assertEquals(1, bLogs.size); assertEquals(1, bSets.size)
    assertEquals("Wild Odds Split", bPrograms[0].name)
    assertEquals(bPrograms[0].id, bSessions[0].programId)
    assertEquals(bSessions[0].id, bExercises[0].sessionId)
    assertEquals(bExercises[0].id, bLogs[0].exerciseId)
    assertEquals(bLogs[0].id, bSets[0].workoutLogId)
    assertEquals(140f, bSets[0].weightKg)

    // Same identity on both sides.
    val aProgram = dbA.backupDao().programs()[0]
    assertEquals(aProgram.syncId, bPrograms[0].syncId)
  }

  @Test
  fun airplaneModeWorkout_syncsLaterWithoutLoss() = runBlocking {
    // A workout logged while offline: sync fails gracefully, nothing is lost or blocked.
    server.offline = true
    seedTrainingData(dbA)
    val offlineResult = engineA.syncNow()
    assertTrue(offlineResult is SyncEngine.Result.Failure)
    assertEquals(1, dbA.backupDao().setLogs().size) // local data untouched

    // Connection returns: the same data pushes and reaches device B intact.
    server.offline = false
    assertEquals(SyncEngine.Result.Success, engineA.syncNow())
    assertEquals(SyncEngine.Result.Success, engineB.syncNow())
    assertEquals(1, dbB.backupDao().setLogs().size)
    assertEquals(140f, dbB.backupDao().setLogs()[0].weightKg)
  }

  @Test
  fun concurrentEdit_lastWriteWins_onBothDevices() = runBlocking {
    seedTrainingData(dbA)
    engineA.syncNow(); engineB.syncNow()

    // Both devices rename the same program; B's edit is strictly newer. Explicit future
    // timestamps sidestep the trigger's now() (its WHEN guard skips writes that change
    // updatedAt themselves) so the ordering is deterministic AND newer than the push cursor.
    val tA = System.currentTimeMillis() + 60_000
    val tB = tA + 60_000
    val aProgram = dbA.backupDao().programs()[0]
    val bProgram = dbB.backupDao().programs()[0]
    dbA.syncDao().updateProgram(aProgram.copy(name = "A's name", updatedAt = tA))
    dbB.syncDao().updateProgram(bProgram.copy(name = "B's name", updatedAt = tB))

    engineA.syncNow() // A pushes tA
    engineB.syncNow() // B pushes tB (server keeps it: newer); pulls A's tA row → older → skipped
    engineA.syncNow() // A pulls B's tB row → B wins locally too

    assertEquals("B's name", dbA.backupDao().programs()[0].name)
    assertEquals("B's name", dbB.backupDao().programs()[0].name)
    assertEquals(tB, dbA.backupDao().programs()[0].updatedAt)
  }

  @Test
  fun deletionPropagates_viaTombstones() = runBlocking {
    val programId = seedTrainingData(dbA)
    engineA.syncNow(); engineB.syncNow()
    assertEquals(1, dbB.backupDao().programs().size)

    // A deletes the program (local CASCADE takes children; triggers tombstone each row).
    runBlocking { dbA.syncDao().deleteProgramBySyncId(dbA.backupDao().programs()[0].syncId) }
    assertEquals(0, dbA.backupDao().programs().size)
    assertTrue(dbA.syncDao().tombstonesSince(0).isNotEmpty())

    engineA.syncNow()
    engineB.syncNow()
    assertEquals(0, dbB.backupDao().programs().size)
    assertEquals(0, dbB.backupDao().sessions().size)
    assertEquals(0, dbB.backupDao().setLogs().size)
  }

  @Test
  fun pullPreservesRemoteTimestamps_soEchoesDoNotPingPong() = runBlocking {
    seedTrainingData(dbA)
    engineA.syncNow()
    engineB.syncNow()

    // The row B applied must carry A's updatedAt, not a fresh local stamp — otherwise every
    // pull would look like a new local edit and devices would re-push forever.
    assertEquals(dbA.backupDao().programs()[0].updatedAt, dbB.backupDao().programs()[0].updatedAt)

    // And a second sync on B pushes nothing new (cursor advanced past the applied rows).
    val serverSizeBefore = server.rows.values.maxOf { it.seq }
    engineB.syncNow()
    assertEquals(serverSizeBefore, server.rows.values.maxOf { it.seq })
  }
}
