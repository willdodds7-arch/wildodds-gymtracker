package com.wildodds.gymtracker.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.wildodds.gymtracker.data.db.entity.ImportRecord

@Dao
interface ImportRecordDao {

  @Insert
  suspend fun insert(record: ImportRecord): Long

  /**
   * The most recent import of this exact source, if any — the idempotency check.
   *
   * Only records whose program still EXISTS count: once the user deletes the program, importing the
   * same file again is a fresh import, not a duplicate, and offering to "replace" something that is
   * gone would be nonsense.
   */
  @Query("""SELECT * FROM import_records
    WHERE sourceHash = :hash AND programId IS NOT NULL
      AND programId IN (SELECT id FROM programs)
    ORDER BY createdAt DESC, id DESC LIMIT 1""")
  suspend fun findBySourceHash(hash: String): ImportRecord?

  /**
   * The latest record for a program. Ordered by id as well as time: two records written in the same
   * millisecond would otherwise come back in an arbitrary order, and "the latest record" would mean
   * different things on different runs.
   */
  @Query("SELECT * FROM import_records WHERE programId = :programId ORDER BY createdAt DESC, id DESC LIMIT 1")
  suspend fun findByProgram(programId: Long): ImportRecord?

  /**
   * Imports worth re-parsing: their source is available AND they were read by an OLDER reader.
   * Re-running the current reader over its own output would reach the same answer.
   */
  @Query("""SELECT * FROM import_records
    WHERE sourceText != '' AND readerVersion < :currentVersion
      AND programId IS NOT NULL AND programId IN (SELECT id FROM programs)
    ORDER BY createdAt DESC""")
  suspend fun staleImports(currentVersion: Int): List<ImportRecord>

  /** The program row is gone but the record is kept, so the source survives for a re-import. */
  @Query("UPDATE import_records SET programId = NULL WHERE programId = :programId")
  suspend fun detachProgram(programId: Long)

  @Query("DELETE FROM import_records WHERE id = :id")
  suspend fun delete(id: Long)
}
