package com.wildodds.gymtracker.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies that the full additive migration chain (v1 → v13) applies cleanly and that the
 * resulting schema exactly matches the exported `13.json` (via [MigrationTestHelper]).
 *
 * Schema export was historically off, so no `1.json … 12.json` exist to seed early versions
 * with. We therefore hand-build the original v1 database (the six core training tables — none
 * of the migrations create them, so they must have existed at v1) and let the helper run the
 * real [AppDatabase.ALL_MIGRATIONS] forward and validate the end state against `13.json`.
 *
 * Going forward this is self-maintaining: once 13.json (and future <n>.json) are checked in,
 * a v13→v14 migration can be validated with the standard createDatabase(13)/runMigrations(14).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

  @get:Rule
  val helper = MigrationTestHelper(
  InstrumentationRegistry.getInstrumentation(),
  AppDatabase::class.java
  )

  @Test
  fun migratesCleanlyFromV1ToLatest() {
  // 1. Seed a v1 database by hand at the path the helper will reopen.
  seedVersion1Database()

  // 2. Run every migration in order and validate the final schema against the latest schema JSON.
  helper.runMigrationsAndValidate(TEST_DB, LATEST_VERSION, true, *AppDatabase.ALL_MIGRATIONS).close()
  }

  /**
   * v23→v24 adds per-set prescription + exercise alternatives. It must be purely additive: the new
   * tables exist and accept rows, and — critically — every pre-existing program/session/exercise
   * row survives untouched, since this migration ships to users with real training history.
   */
  @Test
  fun migration23To24AddsPlannedSetsWithoutTouchingExistingData() {
  seedVersion1Database()
  val upToV23 = AppDatabase.ALL_MIGRATIONS.filter { it.startVersion in 1 until 23 }.toTypedArray()
  val db = helper.runMigrationsAndValidate(TEST_DB, 23, true, *upToV23)
  db.execSQL("INSERT INTO programs (id, name, totalWeeks, createdAt) VALUES (1, 'Old', 4, 1)")
  db.execSQL("INSERT INTO sessions (id, programId, weekNumber, dayNumber, name, muscleGroups) " +
  "VALUES (1, 1, 1, 1, 'Push', 'Chest')")
  db.execSQL("INSERT INTO exercises (id, sessionId, name, sets, repsTarget, notes, orderIndex) " +
  "VALUES (1, 1, 'Bench Press', 3, '8-12', '', 0)")
  db.close()

  val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 23 }
  val migrated = helper.runMigrationsAndValidate(TEST_DB, 24, true, migration)

  // Existing data is intact and unchanged.
  migrated.query("SELECT name, sets, repsTarget FROM exercises WHERE id = 1").use { c ->
  c.moveToFirst()
  assertEquals("Bench Press", c.getString(0))
  assertEquals(3, c.getInt(1))
  assertEquals("8-12", c.getString(2))
  }

  // The new tables exist and take rows.
  migrated.execSQL("INSERT INTO planned_sets (exerciseId, setNumber, type, load, reps, restSeconds) " +
  "VALUES (1, 1, 'WARMUP', '20', '10', 60)")
  migrated.execSQL("INSERT INTO exercise_alternatives (exerciseId, name, orderIndex, note) " +
  "VALUES (1, 'Dumbbell Bench Press', 0, 'if the rack is busy')")
  migrated.query("SELECT COUNT(*) FROM planned_sets").use { c ->
  c.moveToFirst(); assertEquals(1, c.getInt(0))
  }
  migrated.query("SELECT COUNT(*) FROM exercise_alternatives").use { c ->
  c.moveToFirst(); assertEquals(1, c.getInt(0))
  }
  migrated.close()
  }

  /** Deleting an exercise must take its planned sets and alternatives with it (FK cascade). */
  @Test
  fun migration23To24CascadesPlannedSetsOnExerciseDelete() {
  seedVersion1Database()
  val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, *AppDatabase.ALL_MIGRATIONS)
  db.execSQL("PRAGMA foreign_keys = ON")
  db.execSQL("INSERT INTO programs (id, name, totalWeeks, createdAt) VALUES (1, 'P', 4, 1)")
  db.execSQL("INSERT INTO sessions (id, programId, weekNumber, dayNumber, name, muscleGroups) " +
  "VALUES (1, 1, 1, 1, 'Push', '')")
  db.execSQL("INSERT INTO exercises (id, sessionId, name, sets, repsTarget, notes, orderIndex) " +
  "VALUES (1, 1, 'Bench Press', 3, '8-12', '', 0)")
  db.execSQL("INSERT INTO planned_sets (exerciseId, setNumber, reps) VALUES (1, 1, '8')")
  db.execSQL("DELETE FROM exercises WHERE id = 1")

  db.query("SELECT COUNT(*) FROM planned_sets").use { c ->
  c.moveToFirst(); assertEquals(0, c.getInt(0))
  }
  db.close()
  }

  /** v24→v25 adds the evolving-set percentage column. Additive and nullable. */
  @Test
  fun migration24To25AddsLoadPercentWithoutTouchingExistingPlannedSets() {
  seedVersion1Database()
  val upTo24 = AppDatabase.ALL_MIGRATIONS.filter { it.startVersion in 1 until 24 }.toTypedArray()
  val db = helper.runMigrationsAndValidate(TEST_DB, 24, true, *upTo24)
  db.execSQL("INSERT INTO programs (id, name, totalWeeks, createdAt) VALUES (1, 'P', 4, 1)")
  db.execSQL("INSERT INTO sessions (id, programId, weekNumber, dayNumber, name, muscleGroups) " +
  "VALUES (1, 1, 1, 1, 'Push', '')")
  db.execSQL("INSERT INTO exercises (id, sessionId, name, sets, repsTarget, notes, orderIndex) " +
  "VALUES (1, 1, 'Bench Press', 3, '8-12', '', 0)")
  db.execSQL("INSERT INTO planned_sets (exerciseId, setNumber, type, load, reps) " +
  "VALUES (1, 1, 'WORKING', '60', '8')")
  db.close()

  val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 24 }
  val migrated = helper.runMigrationsAndValidate(TEST_DB, 25, true, migration)

  // The pre-existing row keeps its literal load and reads NULL for the new column.
  migrated.query("SELECT load, reps, loadPercent FROM planned_sets WHERE setNumber = 1").use { c ->
  c.moveToFirst()
  assertEquals("60", c.getString(0))
  assertEquals("8", c.getString(1))
  assertEquals(true, c.isNull(2))
  }

  // And a percentage-based row now stores.
  migrated.execSQL("INSERT INTO planned_sets (exerciseId, setNumber, type, reps, loadPercent) " +
  "VALUES (1, 2, 'WARMUP', '8', 50)")
  migrated.query("SELECT loadPercent FROM planned_sets WHERE setNumber = 2").use { c ->
  c.moveToFirst(); assertEquals(50, c.getInt(0))
  }
  migrated.close()
  }

  /** v20→v21 backfills sync metadata: every pre-existing row must get a unique, non-blank syncId. */
  @Test
  fun migration20To21BackfillsSyncIds() {
  seedVersion1Database()
  val upToV20 = AppDatabase.ALL_MIGRATIONS.filter { it.startVersion in 1 until 20 }.toTypedArray()
  val db = helper.runMigrationsAndValidate(TEST_DB, 20, true, *upToV20)
  db.execSQL("INSERT INTO programs (name, totalWeeks, createdAt) VALUES ('A', 4, 1)")
  db.execSQL("INSERT INTO programs (name, totalWeeks, createdAt) VALUES ('B', 4, 2)")
  db.close()

  val migration20to21 = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 20 }
  val migrated = helper.runMigrationsAndValidate(TEST_DB, 21, true, migration20to21)
  val cursor = migrated.query(
  "SELECT COUNT(*), COUNT(DISTINCT syncId) FROM programs WHERE syncId != '' AND updatedAt > 0"
  )
  cursor.moveToFirst()
  assertEquals(2, cursor.getInt(0))
  assertEquals(2, cursor.getInt(1)) // distinct — no two rows share a syncId
  cursor.close()
  migrated.close()
  }

  /**
   * v19→v20 drops the Running-tab's standalone log table. [migratesCleanlyFromV1ToLatest] already
   * proves this indirectly (strict schema validation fails if an untracked table like `run_logs`
   * survives), but this test isolates just that one migration step and asserts on the table
   * directly, with a row present beforehand, so the "drops it, doesn't just no-op" behaviour has
   * its own explicit regression test.
   */
  @Test
  fun migration19To20DropsRunLogsTable() {
  seedVersion1Database()
  val upToV19 = AppDatabase.ALL_MIGRATIONS.filter { it.startVersion in 1 until 19 }.toTypedArray()
  val db = helper.runMigrationsAndValidate(TEST_DB, 19, true, *upToV19)
  db.execSQL("INSERT INTO run_logs (date, type, notes, createdAt) VALUES (0, 'easy', '', 0)")
  db.close()

  val migration19to20 = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 19 }
  val migrated = helper.runMigrationsAndValidate(TEST_DB, 20, true, migration19to20)
  val cursor = migrated.query("SELECT name FROM sqlite_master WHERE type='table' AND name='run_logs'")
  assertEquals(0, cursor.count)
  cursor.close()
  migrated.close()
  }

  /** Creates [TEST_DB] at user_version 1 with the original core schema. */
  /**
   * v25→v26 makes the reader's prescription detail first-class (rest, tempo, superset, load kind,
   * per-set JSON, modifiers, the verbatim source line) and gives a session a type so a rest day can
   * exist. It ships to users with real training history, so: purely additive, nothing backfilled,
   * and every pre-existing row must read back byte-identical with NULLs in the new columns.
   */
  @Test
  fun migration25To26IsAdditiveAndBackfillsNothing() {
  seedVersion1Database()
  val upTo25 = AppDatabase.ALL_MIGRATIONS.filter { it.startVersion in 1 until 25 }.toTypedArray()
  val db = helper.runMigrationsAndValidate(TEST_DB, 25, true, *upTo25)
  db.execSQL("INSERT INTO programs (id, name, totalWeeks, createdAt) VALUES (1, 'Old', 4, 1)")
  db.execSQL("INSERT INTO sessions (id, programId, weekNumber, dayNumber, name, muscleGroups) " +
  "VALUES (1, 1, 1, 1, 'Push', 'Chest')")
  db.execSQL("INSERT INTO exercises (id, sessionId, name, sets, repsTarget, notes, orderIndex) " +
  "VALUES (1, 1, 'Bench Press', 3, '8-12', 'tuck the elbows', 0)")
  db.close()

  val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 25 }
  val migrated = helper.runMigrationsAndValidate(TEST_DB, 26, true, migration)

  // Existing data is intact and unchanged.
  migrated.query("SELECT name, sets, repsTarget, notes FROM exercises WHERE id = 1").use { c ->
  c.moveToFirst()
  assertEquals("Bench Press", c.getString(0))
  assertEquals(3, c.getInt(1))
  assertEquals("8-12", c.getString(2))
  assertEquals("tuck the elbows", c.getString(3))
  }

  // NOTHING is backfilled: null still means "unknown", not "zero" and not "90 seconds".
  migrated.query("""SELECT setsStated, restSeconds, restRangeSecondsMax, tempo, supersetGroup,
  supersetOrder, loadKind, loadValue, loadValueMax, loadUnit, rawSourceLine
  FROM exercises WHERE id = 1""").use { c ->
  c.moveToFirst()
  for (i in 0 until c.columnCount) {
  assertTrue("column ${c.getColumnName(i)} must stay NULL on a pre-v26 row", c.isNull(i))
  }
  }
  // The two non-null new columns take their documented defaults.
  migrated.query("SELECT isFinalSetAmrap, perSet, modifiers FROM exercises WHERE id = 1").use { c ->
  c.moveToFirst()
  assertEquals(0, c.getInt(0))
  assertEquals("", c.getString(1))
  assertEquals("", c.getString(2))
  }
  // Every pre-existing session is a training session, so nothing disappears from the day list.
  migrated.query("SELECT sessionType FROM sessions WHERE id = 1").use { c ->
  c.moveToFirst(); assertEquals("TRAINING", c.getString(0))
  }

  // And the new columns actually take the values the reader writes.
  migrated.execSQL("""INSERT INTO exercises
  (id, sessionId, name, sets, repsTarget, notes, orderIndex, setsStated, restSeconds,
  restRangeSecondsMax, tempo, supersetGroup, supersetOrder, loadKind, loadValue, loadValueMax,
  loadUnit, perSet, isFinalSetAmrap, modifiers, rawSourceLine)
  VALUES (2, 1, 'Front Squat', 3, '5', '', 1, 3, 90, 120, '31X0', 'A', 1, 'PCT_TM', 75.0, 85.0,
  'PERCENT', '[{"setNumber":1,"reps":"5"}]', 1, 'each_side,paused', 'A1) Front Squat 3x5 @75% TM')""")
  migrated.execSQL("INSERT INTO sessions (id, programId, weekNumber, dayNumber, name, muscleGroups, sessionType) " +
  "VALUES (2, 1, 1, 2, 'Rest', '', 'REST')")
  migrated.query("SELECT COUNT(*) FROM sessions WHERE sessionType = 'REST'").use { c ->
  c.moveToFirst(); assertEquals(1, c.getInt(0))
  }
  migrated.query("SELECT rawSourceLine, modifiers FROM exercises WHERE id = 2").use { c ->
  c.moveToFirst()
  assertEquals("A1) Front Squat 3x5 @75% TM", c.getString(0))
  assertEquals("each_side,paused", c.getString(1))
  }
  migrated.close()
  }

  /**
   * v26→v27 adds the import-record table. Purely additive: one new table, nothing altered, and
   * every pre-existing program keeps working with no record — which reads correctly as "we do not
   * know where this program came from".
   */
  @Test
  fun migration26To27AddsImportRecordsWithoutTouchingExistingData() {
  seedVersion1Database()
  val upTo26 = AppDatabase.ALL_MIGRATIONS.filter { it.startVersion in 1 until 26 }.toTypedArray()
  val db = helper.runMigrationsAndValidate(TEST_DB, 26, true, *upTo26)
  db.execSQL("INSERT INTO programs (id, name, totalWeeks, createdAt) VALUES (1, 'Old', 4, 1)")
  db.execSQL("INSERT INTO sessions (id, programId, weekNumber, dayNumber, name, muscleGroups) " +
  "VALUES (1, 1, 1, 1, 'Push', 'Chest')")
  db.execSQL("INSERT INTO exercises (id, sessionId, name, sets, repsTarget, notes, orderIndex) " +
  "VALUES (1, 1, 'Bench Press', 3, '8-12', '', 0)")
  db.close()

  val migration = AppDatabase.ALL_MIGRATIONS.first { it.startVersion == 26 }
  val migrated = helper.runMigrationsAndValidate(TEST_DB, 27, true, migration)

  migrated.query("SELECT name, sets FROM exercises WHERE id = 1").use { c ->
  c.moveToFirst()
  assertEquals("Bench Press", c.getString(0))
  assertEquals(3, c.getInt(1))
  }
  migrated.query("SELECT COUNT(*) FROM import_records").use { c ->
  c.moveToFirst(); assertEquals(0, c.getInt(0))
  }
  migrated.execSQL("""INSERT INTO import_records
  (programId, sourceHash, sourceName, sourceKind, route, readerVersion, exerciseCount,
  overallConfidence, sourceText, truncated, createdAt)
  VALUES (1, 'abc123', 'block.csv', 'DELIMITED', 'strong', 2, 12, 0.94, 'Exercise,Sets', 0, 1)""")
  migrated.query("SELECT sourceHash, route FROM import_records WHERE programId = 1").use { c ->
  c.moveToFirst()
  assertEquals("abc123", c.getString(0))
  assertEquals("strong", c.getString(1))
  }
  migrated.close()
  }

  private fun seedVersion1Database() {
  val context = ApplicationProvider.getApplicationContext<android.content.Context>()
  context.deleteDatabase(TEST_DB)
  val config = SupportSQLiteOpenHelper.Configuration.builder(context)
  .name(TEST_DB)
  .callback(object : SupportSQLiteOpenHelper.Callback(1) {
  override fun onCreate(db: SupportSQLiteDatabase) {
  V1_SCHEMA.forEach { db.execSQL(it) }
  }
  override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
  // Not exercised — we only ever open at version 1 here.
  }
  })
  .build()
  val openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
  openHelper.writableDatabase.close()  // forces onCreate + user_version = 1
  openHelper.close()
  }

  companion object {
  private const val TEST_DB = "migration-test.db"

  /**
   * Must track [AppDatabase]'s `version`. Bump it in the same change as a new migration —
   * otherwise the 1→N test silently stops covering everything past the old number.
   */
  private const val LATEST_VERSION = 27

  /**
   * The schema as it existed at version 1 — i.e. the v13 entities minus every column/table
   * that a later migration adds (coverImage/isFlexible/… on programs, phaseNumber on
   * sessions, rpeTarget/supersetGroupId/unilateralMode on exercises, rpe/per-side columns on
   * set_logs, and the habits/phases/library tables created by migrations 2→3 and 12→13).
   * Foreign keys and indices are unchanged since v1, so they are reproduced exactly.
   */
  private val V1_SCHEMA = listOf(
  "CREATE TABLE IF NOT EXISTS `programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `totalWeeks` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",

  "CREATE TABLE IF NOT EXISTS `sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `programId` INTEGER NOT NULL, `weekNumber` INTEGER NOT NULL, `dayNumber` INTEGER NOT NULL, `name` TEXT NOT NULL, `muscleGroups` TEXT NOT NULL, FOREIGN KEY(`programId`) REFERENCES `programs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
  "CREATE INDEX IF NOT EXISTS `index_sessions_programId` ON `sessions` (`programId`)",

  "CREATE TABLE IF NOT EXISTS `exercises` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `name` TEXT NOT NULL, `sets` INTEGER NOT NULL, `repsTarget` TEXT NOT NULL, `notes` TEXT NOT NULL, `orderIndex` INTEGER NOT NULL, FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
  "CREATE INDEX IF NOT EXISTS `index_exercises_sessionId` ON `exercises` (`sessionId`)",

  "CREATE TABLE IF NOT EXISTS `workout_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `exerciseId` INTEGER NOT NULL, `sessionId` INTEGER NOT NULL, `weekNumber` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, `progressionChoice` TEXT, FOREIGN KEY(`exerciseId`) REFERENCES `exercises`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
  "CREATE INDEX IF NOT EXISTS `index_workout_logs_exerciseId` ON `workout_logs` (`exerciseId`)",
  "CREATE INDEX IF NOT EXISTS `index_workout_logs_sessionId` ON `workout_logs` (`sessionId`)",

  "CREATE TABLE IF NOT EXISTS `set_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `workoutLogId` INTEGER NOT NULL, `setNumber` INTEGER NOT NULL, `weightKg` REAL, `reps` INTEGER, FOREIGN KEY(`workoutLogId`) REFERENCES `workout_logs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
  "CREATE INDEX IF NOT EXISTS `index_set_logs_workoutLogId` ON `set_logs` (`workoutLogId`)",

  "CREATE TABLE IF NOT EXISTS `session_completions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` INTEGER NOT NULL, `weekNumber` INTEGER NOT NULL, `completedAt` INTEGER NOT NULL, FOREIGN KEY(`sessionId`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
  "CREATE INDEX IF NOT EXISTS `index_session_completions_sessionId` ON `session_completions` (`sessionId`)"
  )
  }
}
