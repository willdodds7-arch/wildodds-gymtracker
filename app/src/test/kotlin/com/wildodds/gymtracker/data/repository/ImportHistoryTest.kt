package com.wildodds.gymtracker.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.wildodds.gymtracker.data.db.AppDatabase
import com.wildodds.gymtracker.data.db.entity.ImportRecord
import com.wildodds.gymtracker.data.reader.ProgramReader
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Import as a reversible, repeatable operation.
 *
 * These three behaviours all exist for the same reason: an import is a READING of a document, and a
 * reading can be wrong. Undo makes a wrong reading cheap to discard, re-parse makes a future parser
 * fix reach programs that were imported before it, and the duplicate check stops the same file
 * quietly becoming two programs.
 */
@RunWith(RobolectricTestRunner::class)
class ImportHistoryTest {

  private lateinit var db: AppDatabase
  private lateinit var repo: GymRepository
  private lateinit var history: ImportHistory
  private val reader = ProgramReader()

  private val doc = """
    Winter Block
    Day 1 - Push
    Bench Press 3x8 @ 80kg
    Overhead Press 3x10
    Day 2 - Pull
    Barbell Row 4x8
  """.trimIndent()

  @Before fun setUp() {
    db = Room.inMemoryDatabaseBuilder(
      ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
    repo = GymRepository(db)
    history = ImportHistory(db)
  }

  @After fun tearDown() = db.close()

  private suspend fun commit(text: String = doc, name: String = "block.txt"): Long {
    val result = reader.readText(text, "Winter Block")
    return history.commit(repo, result.importResult.program, result,
      text.toByteArray(Charsets.UTF_8), name, activate = true)
  }

  // ── the record ───────────────────────────────────────────────────────────────

  @Test fun `an import records what it read and how`() = runBlocking {
    val id = commit()
    val record = history.recordFor(id)!!
    assertEquals(id, record.programId)
    assertEquals("block.txt", record.sourceName)
    assertEquals("text", record.route)
    assertEquals(ImportRecord.READER_VERSION, record.readerVersion)
    assertEquals(3, record.exerciseCount)
    assertTrue("the source must be kept so a re-parse can re-read it",
      record.sourceText.contains("Bench Press"))
  }

  @Test fun `the program and its record land together`() = runBlocking {
    val id = commit()
    assertNotNull(db.programDao().getProgramById(id))
    assertNotNull(history.recordFor(id))
  }

  // ── idempotency ──────────────────────────────────────────────────────────────

  @Test fun `re-importing the same document is recognised`() = runBlocking {
    val id = commit()
    val dup = history.checkForDuplicate(doc)
    assertTrue("expected a duplicate, got $dup", dup is ImportHistory.Duplicate.Seen)
    assertEquals(id, (dup as ImportHistory.Duplicate.Seen).programId)
  }

  @Test fun `the same document under a different filename is still recognised`() = runBlocking {
    commit(name = "block.txt")
    // Keyed on the source BYTES, because people re-download the same sheet under a new name.
    val dup = history.checkForDuplicate(doc)
    assertTrue(dup is ImportHistory.Duplicate.Seen)
  }

  @Test fun `a different document is not a duplicate`() = runBlocking {
    commit()
    val other = "Day 1\nBack Squat 5x5"
    assertTrue(history.checkForDuplicate(other) is ImportHistory.Duplicate.None)
  }

  @Test fun `a document whose program was deleted is no longer a duplicate`() = runBlocking {
    val id = commit()
    history.undo(id)
    // Importing it again is a fresh import, not a duplicate — offering to "replace" something that
    // is gone would be nonsense.
    assertTrue(history.checkForDuplicate(doc) is ImportHistory.Duplicate.None)
  }

  // ── undo ─────────────────────────────────────────────────────────────────────

  @Test fun `undo removes the program and everything under it`() = runBlocking {
    val id = commit()
    assertTrue(db.sessionDao().getAllSessionsOnce(id).isNotEmpty())

    assertTrue(history.undo(id))
    assertNull(db.programDao().getProgramById(id))
    assertTrue(db.sessionDao().getAllSessionsOnce(id).isEmpty())
  }

  @Test fun `undo keeps the source so the file can be imported again`() = runBlocking {
    val id = commit()
    history.undo(id)
    // The record survives, detached — which is what lets the user fix something and re-import.
    val again = commit()
    assertNotNull(db.programDao().getProgramById(again))
  }

  @Test fun `undoing a program that is already gone is a no-op rather than a crash`() = runBlocking {
    assertTrue(!history.undo(9999))
  }

  // ── re-parse ─────────────────────────────────────────────────────────────────

  @Test fun `re-parsing re-reads the source and replaces the program`() = runBlocking {
    val id = commit()
    val before = db.exerciseDao().getExercisesForSession(
      db.sessionDao().getAllSessionsOnce(id).first().id).size

    val newId = history.reparse(repo, reader, id)
    assertNotNull("re-parse should have produced a program", newId)
    assertNull("the old program is replaced, not duplicated", db.programDao().getProgramById(id))

    val after = db.exerciseDao().getExercisesForSession(
      db.sessionDao().getAllSessionsOnce(newId!!).first().id).size
    assertEquals("the same source must re-read the same way", before, after)
  }

  @Test fun `re-parsing keeps the program active if it was active`() = runBlocking {
    val id = commit()
    val newId = history.reparse(repo, reader, id)!!
    assertTrue(db.programDao().getProgramById(newId)!!.isActive)
  }

  @Test fun `re-parsing writes a fresh record at the current reader version`() = runBlocking {
    val id = commit()
    val newId = history.reparse(repo, reader, id)!!
    val record = history.recordFor(newId)!!
    assertEquals(ImportRecord.READER_VERSION, record.readerVersion)
    assertEquals(newId, record.programId)
  }

  @Test fun `an import with no stored source cannot be re-parsed`() = runBlocking {
    val id = commit()
    db.importRecordDao().insert(
      history.recordFor(id)!!.copy(id = 0, sourceText = "", truncated = true))
    // The most recent record for this program now has no source.
    assertNull(history.reparse(repo, reader, id))
  }

  @Test fun `stale imports are the ones an older reader produced`() = runBlocking {
    val id = commit()
    assertTrue("a fresh import is not stale", history.staleImports().none { it.programId == id })

    db.importRecordDao().insert(history.recordFor(id)!!.copy(
      id = 0, readerVersion = ImportRecord.READER_VERSION - 1))
    assertTrue("an older reader's import should be offered for re-parse",
      history.staleImports().any { it.programId == id })
  }

  // ── the hash ─────────────────────────────────────────────────────────────────

  @Test fun `the source hash is stable and content-addressed`() {
    assertEquals(ImportRecord.hashOf("abc"), ImportRecord.hashOf("abc"))
    assertTrue(ImportRecord.hashOf("abc") != ImportRecord.hashOf("abd"))
    assertEquals(64, ImportRecord.hashOf("abc").length)
  }
}
