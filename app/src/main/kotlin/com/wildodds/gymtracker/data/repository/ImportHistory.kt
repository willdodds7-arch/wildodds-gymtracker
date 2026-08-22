package com.wildodds.gymtracker.data.repository

import androidx.room.withTransaction
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.ImportRecord
import com.wildodds.gymtracker.data.parser.ParsedProgram
import com.wildodds.gymtracker.data.reader.ProgramReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Import as a **reversible, repeatable** operation rather than a one-way write.
 *
 * An import is a READING of a document, and a reading can be wrong — which makes three things
 * necessary rather than nice:
 *
 *  - [undo] — "that's not my program" is one tap, not a manual delete.
 *  - [reparse] — the source is kept, so every future parser fix retroactively improves programs
 *    users already imported. Without this, a parser bug is permanent for everyone who imported
 *    before the fix, which is a strange thing for a subsystem whose whole premise is that it will
 *    keep getting better.
 *  - [checkForDuplicate] — the same file imported twice offers merge / replace / duplicate instead
 *    of silently creating a second copy.
 *
 * The import itself stays transactional: the program and its record land together or not at all.
 */
class ImportHistory(private val db: AppDatabase) {

  private val records = db.importRecordDao()

  /** What to do about a document that has been imported before. */
  sealed class Duplicate {
    /** Not seen before — import normally. */
    object None : Duplicate()
    data class Seen(
      val record: ImportRecord,
      val programName: String,
      val programId: Long
    ) : Duplicate()
  }

  /**
   * Has this exact source been imported already? Keyed on the SOURCE BYTES, so the same file is
   * recognised however it has been renamed — which is the case that actually happens, because
   * people re-download the same sheet.
   */
  suspend fun checkForDuplicate(sourceBytes: ByteArray): Duplicate = withContext(Dispatchers.IO) {
    val hash = ImportRecord.hashOf(sourceBytes)
    val existing = records.findBySourceHash(hash) ?: return@withContext Duplicate.None
    val programId = existing.programId ?: return@withContext Duplicate.None
    val program = db.programDao().getProgramById(programId) ?: return@withContext Duplicate.None
    Duplicate.Seen(existing, program.name, programId)
  }

  suspend fun checkForDuplicate(sourceText: String): Duplicate =
    checkForDuplicate(sourceText.toByteArray(Charsets.UTF_8))

  /**
   * Commits a program AND its import record in one transaction.
   *
   * Both or neither: a program with no record cannot be undone or re-parsed, and a record with no
   * program is a dangling promise. Getting that pair wrong is exactly the kind of half-state that
   * is invisible until someone tries to use the feature.
   */
  suspend fun commit(
    repo: GymRepository,
    program: ParsedProgram,
    result: ProgramReader.Result,
    sourceBytes: ByteArray,
    sourceName: String,
    activate: Boolean
  ): Long = db.withTransaction {
    val programId = repo.importProgram(program, activate)
    val text = result.document.text
    val keep = text.length <= ImportRecord.MAX_SOURCE_CHARS
    records.insert(ImportRecord(
      programId = programId,
      sourceHash = ImportRecord.hashOf(sourceBytes),
      sourceName = sourceName,
      sourceKind = result.kind.name,
      route = result.route,
      readerVersion = ImportRecord.READER_VERSION,
      exerciseCount = result.importResult.exerciseCount,
      overallConfidence = result.importResult.overallConfidence,
      // Kept so a re-parse has something to re-read. Dropped above the cap rather than truncated
      // silently — a partial source would re-parse into a partial program.
      sourceText = if (keep) text else "",
      truncated = !keep
    ))
    programId
  }

  /**
   * Undo an import: delete the program (cascading its sessions, exercises and logs) and detach the
   * record, keeping the source so the user can import it again after fixing something.
   */
  suspend fun undo(programId: Long): Boolean = withContext(Dispatchers.IO) {
    db.withTransaction {
      val program = db.programDao().getProgramById(programId) ?: return@withTransaction false
      db.programDao().deleteById(program.id)
      records.detachProgram(programId)
      true
    }
  }

  /**
   * Re-read a past import with the CURRENT reader and replace the program in place.
   *
   * The program id changes (the old row is deleted and a new one written), which the caller has to
   * handle — but the alternative is mutating rows under a running session, and a program the user
   * might be halfway through is not something to edit underneath them. The record is rewritten to
   * point at the new program with the current reader version.
   *
   * Returns null when there is nothing to re-read.
   */
  suspend fun reparse(
    repo: GymRepository,
    reader: ProgramReader,
    programId: Long,
    aliasLookup: (String) -> String? = { null }
  ): Long? = withContext(Dispatchers.IO) {
    val record = records.findByProgram(programId) ?: return@withContext null
    if (record.sourceText.isBlank()) return@withContext null
    val wasActive = db.programDao().getProgramById(programId)?.isActive ?: false

    val result = reader.readText(record.sourceText, record.sourceName, aliasLookup)
    if (result.importResult.exerciseCount == 0) return@withContext null

    db.withTransaction {
      db.programDao().deleteById(programId)
      records.detachProgram(programId)
      val newId = repo.importProgram(result.importResult.program, activate = wasActive)
      records.insert(record.copy(
        id = 0,
        programId = newId,
        route = result.route,
        readerVersion = ImportRecord.READER_VERSION,
        exerciseCount = result.importResult.exerciseCount,
        overallConfidence = result.importResult.overallConfidence,
        createdAt = System.currentTimeMillis()
      ))
      newId
    }
  }

  /**
   * Imports that a newer reader would read differently. This is what turns "we improved the parser"
   * into something a user can act on, rather than a note in a changelog.
   */
  suspend fun staleImports(): List<ImportRecord> = withContext(Dispatchers.IO) {
    records.staleImports(ImportRecord.READER_VERSION)
  }

  suspend fun recordFor(programId: Long): ImportRecord? = withContext(Dispatchers.IO) {
    records.findByProgram(programId)
  }
}
