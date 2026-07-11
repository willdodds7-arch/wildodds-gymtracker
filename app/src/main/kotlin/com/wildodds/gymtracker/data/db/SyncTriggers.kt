package com.wildodds.gymtracker.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Change tracking for sync, done entirely in SQLite triggers so that NO repository write path
 * needs to remember it:
 *
 *  - AFTER INSERT: rows born locally (syncId = '') get a fresh random syncId + updatedAt = now.
 *    Rows written by sync-pull arrive with both already set and are left untouched.
 *  - AFTER UPDATE: bumps updatedAt — but only when the writer didn't change updatedAt itself
 *    (WHEN NEW.updatedAt = OLD.updatedAt). Sync-pull sets updatedAt to the remote value as part
 *    of its update, so the guard skips it and the remote LWW timestamp is preserved.
 *  - AFTER DELETE: writes a tombstone so the deletion soft-deletes on the server and propagates.
 *
 * SQLite's recursive_triggers is OFF by default, so a trigger's own UPDATE doesn't re-fire it.
 * Installed from AppDatabase's onOpen callback (idempotent CREATE IF NOT EXISTS) — migrations
 * alone wouldn't cover fresh installs, where Room creates tables without running migrations.
 */
object SyncTriggers {

  /** Table names of every syncable entity, in parent→child dependency order. */
  val SYNCED_TABLES = listOf(
    "programs", "program_phases", "sessions", "exercises",
    "workout_logs", "set_logs", "session_completions"
  )

  // True millisecond precision. strftime('%s') is whole seconds — with that, an edit and a
  // delete inside the same second tie on updatedAt and LWW drops the later one.
  private const val NOW_MS = "(CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER))"

  fun install(db: SupportSQLiteDatabase) {
    SYNCED_TABLES.forEach { t ->
      db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS sync_ins_$t AFTER INSERT ON $t
        WHEN NEW.syncId = ''
        BEGIN
          UPDATE $t SET syncId = lower(hex(randomblob(16))), updatedAt = $NOW_MS WHERE id = NEW.id;
        END"""
      )
      db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS sync_upd_$t AFTER UPDATE ON $t
        WHEN NEW.updatedAt = OLD.updatedAt
        BEGIN
          UPDATE $t SET updatedAt = $NOW_MS WHERE id = NEW.id;
        END"""
      )
      db.execSQL(
        """CREATE TRIGGER IF NOT EXISTS sync_del_$t AFTER DELETE ON $t
        WHEN OLD.syncId != ''
        BEGIN
          INSERT OR REPLACE INTO sync_tombstones(entityType, syncId, deletedAt)
          VALUES ('$t', OLD.syncId, $NOW_MS);
        END"""
      )
    }
  }
}
