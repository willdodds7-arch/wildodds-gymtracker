package com.wildodds.gymtracker.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
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
  helper.runMigrationsAndValidate(TEST_DB, 21, true, *AppDatabase.ALL_MIGRATIONS).close()
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
