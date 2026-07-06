package com.wildodds.gymtracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.wildodds.gymtracker.data.db.dao.*
import com.wildodds.gymtracker.data.db.entity.*

@Database(
  entities = [
  Program::class,
  Session::class,
  Exercise::class,
  WorkoutLog::class,
  SetLog::class,
  SessionCompletion::class,
  Habit::class,
  HabitCompletion::class,
  ProgramPhase::class,
  SessionTemplate::class,
  SessionTemplateExercise::class,
  Achievement::class
  ],
  version = 20,
  exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
  abstract fun programDao(): ProgramDao
  abstract fun sessionDao(): SessionDao
  abstract fun exerciseDao(): ExerciseDao
  abstract fun workoutLogDao(): WorkoutLogDao
  abstract fun setLogDao(): SetLogDao
  abstract fun sessionCompletionDao(): SessionCompletionDao
  abstract fun habitDao(): HabitDao
  abstract fun programPhaseDao(): ProgramPhaseDao
  abstract fun sessionTemplateDao(): SessionTemplateDao
  abstract fun backupDao(): BackupDao
  abstract fun achievementDao(): AchievementDao

  companion object {
  @Volatile private var INSTANCE: AppDatabase? = null

  private val MIGRATION_1_2 = object : Migration(1, 2) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE programs ADD COLUMN coverImage TEXT DEFAULT NULL")
  }
  }

  private val MIGRATION_2_3 = object : Migration(2, 3) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL(
  """CREATE TABLE IF NOT EXISTS habits (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name TEXT NOT NULL,
  emoji TEXT NOT NULL DEFAULT '',
  createdAt INTEGER NOT NULL
  )"""
  )
  db.execSQL(
  """CREATE TABLE IF NOT EXISTS habit_completions (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  habitId INTEGER NOT NULL,
  completedDate TEXT NOT NULL
  )"""
  )
  db.execSQL(
  """CREATE UNIQUE INDEX IF NOT EXISTS index_habit_completions_habitId_completedDate
  ON habit_completions (habitId, completedDate)"""
  )
  }
  }

  private val MIGRATION_3_4 = object : Migration(3, 4) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE programs ADD COLUMN isFlexible INTEGER NOT NULL DEFAULT 0")
  }
  }

  private val MIGRATION_4_5 = object : Migration(4, 5) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE programs ADD COLUMN category TEXT NOT NULL DEFAULT ''")
  }
  }

  private val MIGRATION_5_6 = object : Migration(5, 6) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE programs ADD COLUMN isActive INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE programs ADD COLUMN isUserCreated INTEGER NOT NULL DEFAULT 0")
  db.execSQL("UPDATE programs SET isActive = 1 WHERE id = (SELECT id FROM programs ORDER BY createdAt DESC LIMIT 1)")
  }
  }

  private val MIGRATION_7_8 = object : Migration(7, 8) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE programs ADD COLUMN trackRpe INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE programs ADD COLUMN trackOneRm INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE set_logs ADD COLUMN rpe REAL")
  db.execSQL("ALTER TABLE set_logs ADD COLUMN pct1rm REAL")
  }
  }

  private val MIGRATION_6_7 = object : Migration(6, 7) {
  override fun migrate(db: SupportSQLiteDatabase) {
  // Strip "Day N · " prefix (e.g. "Day 1 · Squat" → "Squat")
  db.execSQL("UPDATE sessions SET name = TRIM(SUBSTR(name, INSTR(name, ' · ') + 3)) WHERE INSTR(name, ' · ') > 0")
  // Strip remaining "Day N - " prefix (e.g. "Day 1 - Squat" → "Squat")
  db.execSQL("UPDATE sessions SET name = TRIM(SUBSTR(name, INSTR(name, ' - ') + 3)) WHERE name LIKE 'Day % - %'")
  // Strip weekday prefix (e.g. "Monday - Bench" → "Bench")
  db.execSQL("""UPDATE sessions SET name = TRIM(SUBSTR(name, INSTR(name, ' - ') + 3))
  WHERE name LIKE 'Monday - %' OR name LIKE 'Tuesday - %' OR name LIKE 'Wednesday - %'
  OR name LIKE 'Thursday - %' OR name LIKE 'Friday - %'
  OR name LIKE 'Saturday - %' OR name LIKE 'Sunday - %'
  OR name LIKE 'Mon - %' OR name LIKE 'Tue - %' OR name LIKE 'Wed - %'
  OR name LIKE 'Thu - %' OR name LIKE 'Fri - %'""")
  }
  }

  private val MIGRATION_8_9 = object : Migration(8, 9) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE exercises ADD COLUMN rpeTarget TEXT NOT NULL DEFAULT ''")
  db.execSQL("ALTER TABLE exercises ADD COLUMN pct1rmTarget TEXT NOT NULL DEFAULT ''")
  }
  }

  private val MIGRATION_9_10 = object : Migration(9, 10) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE programs ADD COLUMN isPaused INTEGER NOT NULL DEFAULT 0")
  }
  }

  private val MIGRATION_10_11 = object : Migration(10, 11) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE exercises ADD COLUMN supersetGroupId INTEGER DEFAULT NULL")
  }
  }

  private val MIGRATION_11_12 = object : Migration(11, 12) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE exercises ADD COLUMN unilateralMode INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE set_logs ADD COLUMN weightRightKg REAL DEFAULT NULL")
  db.execSQL("ALTER TABLE set_logs ADD COLUMN repsRight INTEGER DEFAULT NULL")
  }
  }

  private val MIGRATION_12_13 = object : Migration(12, 13) {
  override fun migrate(db: SupportSQLiteDatabase) {
  // Phases — additive; default phase 1 keeps existing programs identical.
  db.execSQL("ALTER TABLE sessions ADD COLUMN phaseNumber INTEGER NOT NULL DEFAULT 1")
  db.execSQL("ALTER TABLE programs ADD COLUMN currentPhase INTEGER NOT NULL DEFAULT 1")
  db.execSQL(
  """CREATE TABLE IF NOT EXISTS program_phases (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  programId INTEGER NOT NULL,
  phaseNumber INTEGER NOT NULL,
  name TEXT NOT NULL,
  durationWeeks INTEGER NOT NULL,
  focus TEXT NOT NULL DEFAULT '',
  FOREIGN KEY(programId) REFERENCES programs(id) ON DELETE CASCADE
  )"""
  )
  db.execSQL("CREATE INDEX IF NOT EXISTS index_program_phases_programId ON program_phases (programId)")

  // Session library
  db.execSQL(
  """CREATE TABLE IF NOT EXISTS session_templates (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  name TEXT NOT NULL,
  muscleGroups TEXT NOT NULL DEFAULT '',
  notes TEXT NOT NULL DEFAULT '',
  createdAt INTEGER NOT NULL,
  source TEXT NOT NULL DEFAULT 'Custom'
  )"""
  )
  db.execSQL(
  """CREATE TABLE IF NOT EXISTS session_template_exercises (
  id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  templateId INTEGER NOT NULL,
  name TEXT NOT NULL,
  sets INTEGER NOT NULL,
  repsTarget TEXT NOT NULL,
  notes TEXT NOT NULL DEFAULT '',
  orderIndex INTEGER NOT NULL,
  rpeTarget TEXT NOT NULL DEFAULT '',
  pct1rmTarget TEXT NOT NULL DEFAULT '',
  FOREIGN KEY(templateId) REFERENCES session_templates(id) ON DELETE CASCADE
  )"""
  )
  db.execSQL("CREATE INDEX IF NOT EXISTS index_session_template_exercises_templateId ON session_template_exercises (templateId)")
  }
  }

  private val MIGRATION_13_14 = object : Migration(13, 14) {
  override fun migrate(db: SupportSQLiteDatabase) {
  // Post-session summary — additive, all nullable so existing completions are unaffected.
  db.execSQL("ALTER TABLE session_completions ADD COLUMN durationSeconds INTEGER")
  db.execSQL("ALTER TABLE session_completions ADD COLUMN strainRating INTEGER")
  db.execSQL("ALTER TABLE session_completions ADD COLUMN avgHeartRate INTEGER")
  db.execSQL("ALTER TABLE session_completions ADD COLUMN peakHeartRate INTEGER")
  db.execSQL("ALTER TABLE session_completions ADD COLUMN hrSeries TEXT")
  }
  }

  private val MIGRATION_14_15 = object : Migration(14, 15) {
  override fun migrate(db: SupportSQLiteDatabase) {
  db.execSQL("ALTER TABLE session_completions ADD COLUMN fatigueScore INTEGER")
  }
  }

  private val MIGRATION_15_16 = object : Migration(15, 16) {
  override fun migrate(db: SupportSQLiteDatabase) {
  // Sync bookkeeping (Phase 5B) — a new side table; existing data is untouched.
  db.execSQL(
  """CREATE TABLE IF NOT EXISTS sync_meta (
  entityType TEXT NOT NULL,
  entityId TEXT NOT NULL,
  updatedAt INTEGER NOT NULL,
  dirty INTEGER NOT NULL DEFAULT 1,
  deleted INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(entityType, entityId)
  )"""
  )
  }
  }

  private val MIGRATION_16_17 = object : Migration(16, 17) {
  override fun migrate(db: SupportSQLiteDatabase) {
  // Unlocked achievements (Phase 6A) — id is the catalog definition id (idempotent by PK).
  db.execSQL(
  """CREATE TABLE IF NOT EXISTS achievements (
  id TEXT PRIMARY KEY NOT NULL,
  unlockedAt INTEGER NOT NULL
  )"""
  )
  }
  }

  private val MIGRATION_17_18 = object : Migration(17, 18) {
  override fun migrate(db: SupportSQLiteDatabase) {
  // Program browse metadata: description, coach (name + bio) and tags (days/week, split, style).
  db.execSQL("ALTER TABLE programs ADD COLUMN description TEXT NOT NULL DEFAULT ''")
  db.execSQL("ALTER TABLE programs ADD COLUMN coach TEXT NOT NULL DEFAULT ''")
  db.execSQL("ALTER TABLE programs ADD COLUMN coachBio TEXT NOT NULL DEFAULT ''")
  db.execSQL("ALTER TABLE programs ADD COLUMN daysPerWeek INTEGER NOT NULL DEFAULT 0")
  db.execSQL("ALTER TABLE programs ADD COLUMN split TEXT NOT NULL DEFAULT ''")
  db.execSQL("ALTER TABLE programs ADD COLUMN style TEXT NOT NULL DEFAULT ''")
  // Online mode removed: drop the now-defunct sync bookkeeping table.
  db.execSQL("DROP TABLE IF EXISTS sync_meta")
  }
  }

  private val MIGRATION_18_19 = object : Migration(18, 19) {
  override fun migrate(db: SupportSQLiteDatabase) {
  // Running programs are flagged so they route to the Running page, not the lifting library.
  db.execSQL("ALTER TABLE programs ADD COLUMN isRunning INTEGER NOT NULL DEFAULT 0")
  // Standalone running log.
  db.execSQL("""
  CREATE TABLE IF NOT EXISTS run_logs (
  id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
  date INTEGER NOT NULL,
  type TEXT NOT NULL,
  distanceKm REAL,
  durationSeconds INTEGER,
  avgHeartRate INTEGER,
  maxHeartRate INTEGER,
  perceivedEffort INTEGER,
  elevationGainM INTEGER,
  notes TEXT NOT NULL DEFAULT '',
  programName TEXT,
  createdAt INTEGER NOT NULL
  )
  """.trimIndent())
  }
  }

  private val MIGRATION_19_20 = object : Migration(19, 20) {
  override fun migrate(db: SupportSQLiteDatabase) {
  // Running-tab feature removed (v2 online-first direction). The standalone running log is
  // dropped outright. `programs.isRunning` is deliberately LEFT IN PLACE rather than dropped:
  // SQLite's ALTER TABLE ... DROP COLUMN only landed in SQLite 3.35 (~Android 12L), and this
  // app's minSdk is 26 — a raw DROP COLUMN would crash the app on older devices. The safe
  // alternative (rebuild the whole `programs` table without the column) isn't worth the risk
  // for one inert, always-false boolean; every code path that read it has been removed instead.
  db.execSQL("DROP TABLE IF EXISTS run_logs")
  }
  }

  /** Every migration, in order, as applied by the app. Exposed so tests can run the
   *  identical 1→N chain against a seed database. */
  val ALL_MIGRATIONS: Array<Migration> = arrayOf(
  MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7,
  MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
  MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19,
  MIGRATION_19_20
  )

  fun getInstance(context: Context): AppDatabase =
  INSTANCE ?: synchronized(this) {
  INSTANCE ?: Room.databaseBuilder(
  context.applicationContext,
  AppDatabase::class.java,
  "gym_tracker.db"
  )
  .addMigrations(*ALL_MIGRATIONS)
  .build()
  .also { INSTANCE = it }
  }
  }
}
