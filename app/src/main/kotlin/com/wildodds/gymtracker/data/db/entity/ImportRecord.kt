package com.wildodds.gymtracker.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * What was imported, from what, and by which parser.
 *
 * Three things become possible only because this row exists, and all three were in the spec for the
 * same underlying reason — an import is a READING of a document, and a reading can be wrong:
 *
 *  - **Undo.** "That's not my program" is a one-tap fix rather than deleting rows by hand.
 *  - **Re-parse.** Every future parser fix retroactively improves programs users already imported,
 *    because the source text is still here to re-read. Without it, a parser bug is permanent for
 *    everyone who imported before the fix.
 *  - **Idempotency.** Re-importing the same document offers merge / replace / duplicate rather than
 *    silently creating a second copy, because [sourceHash] recognises it.
 *
 * [sourceText] is the EXTRACTED text, not the original bytes: it is what the parser actually read,
 * it is what a re-parse needs, and storing a user's original 20 MB PDF in the database to achieve
 * the same thing would be a poor trade. Capped, and the cap is recorded in [truncated] so a re-parse
 * knows it is working from a partial source rather than assuming otherwise.
 */
@Entity(
  tableName = "import_records",
  indices = [Index("programId"), Index("sourceHash")]
)
data class ImportRecord(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  /** The program this import created. Null once the program has been deleted. */
  val programId: Long?,
  /** SHA-256 of the SOURCE BYTES, so the same file is recognised however it is renamed. */
  val sourceHash: String,
  val sourceName: String = "",
  /** Name of a [com.wildodds.gymtracker.data.reader.ingest.SourceKind]. */
  val sourceKind: String = "",
  /** Which route read it: an adapter id, "grid:<layout>", or "text". */
  val route: String = "",
  /**
   * The reader version that produced this import. A re-parse is only worth offering when the
   * current reader is NEWER — otherwise it would re-do the same work and reach the same answer.
   */
  val readerVersion: Int = 0,
  val exerciseCount: Int = 0,
  val overallConfidence: Double = 0.0,
  /** The extracted text the parser read. Empty when the source was too large to keep. */
  val sourceText: String = "",
  @ColumnInfo(defaultValue = "0") val truncated: Boolean = false,
  val createdAt: Long = System.currentTimeMillis()
) {
  companion object {
    /**
     * The reader's version. **Bump this whenever a change would make an existing import parse
     * differently** — that is exactly what makes "re-parse this import" worth offering.
     */
    const val READER_VERSION = 2

    /** Above this, the source text is dropped rather than stored; re-parse is then unavailable. */
    const val MAX_SOURCE_CHARS = 512 * 1024

    fun hashOf(bytes: ByteArray): String {
      val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
      return digest.joinToString("") { "%02x".format(it) }
    }

    fun hashOf(text: String): String = hashOf(text.toByteArray(Charsets.UTF_8))
  }
}
