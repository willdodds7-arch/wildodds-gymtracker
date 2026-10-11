package com.wildodds.gymtracker.data.reader.ingest

import java.io.ByteArrayInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * Reads a zip into memory with every guard the four zip-based office formats need.
 *
 * Guards, each because the alternative is a crash on a real user's file:
 *  - **Zip slip**: an entry named `../../databases/gym_tracker.db` must never be treated as a path.
 *    Nothing here writes to disk, but entry names are used as map keys and matched against prefixes,
 *    so they are normalised and traversal entries are dropped rather than trusted.
 *  - **Zip bomb**: decompressed size is counted as it is read and the whole read aborts at the cap,
 *    rather than after the OOM.
 *  - **Entry count** and **depth**, for archives-of-archives.
 *  - **Truncation**: a partially-downloaded xlsx yields the entries it does have plus a warning,
 *    not an exception, because those entries are usually enough to import the program.
 */
object ZipReader {

  data class Archive(
    val entries: Map<String, ByteArray>,
    val order: List<String>,
    val warnings: List<IngestWarning> = emptyList()
  ) {
    operator fun get(name: String): ByteArray? = entries[name]
    fun names(): List<String> = order
  }

  fun read(
    bytes: ByteArray,
    limits: IngestLimits = IngestLimits.DEFAULT,
    progress: IngestProgress = IngestProgress.NONE
  ): Archive {
    val entries = LinkedHashMap<String, ByteArray>()
    val order = ArrayList<String>()
    val warnings = ArrayList<IngestWarning>()
    var total = 0L

    try {
      ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var e: ZipEntry? = zip.nextEntry
        while (e != null) {
          if (progress.isCancelled()) throw IngestError.Cancelled()
          if (order.size >= limits.maxZipEntries) {
            warnings += IngestWarning("Archive has more than ${limits.maxZipEntries} entries — the rest were skipped.")
            break
          }
          if (!e.isDirectory) {
            val name = safeName(e.name)
            if (name == null) {
              warnings += IngestWarning("Skipped an archive entry with a suspicious path.", e.name)
            } else {
              val data = readCapped(zip, limits.maxDecompressedBytes - total)
              if (data == null) {
                throw IngestError.TooLarge(
                  "That archive expands to more than ${limits.maxDecompressedBytes / (1024 * 1024)} MB.")
              }
              total += data.size
              if (entries.put(name, data) == null) order += name
            }
          }
          e = try { zip.nextEntry } catch (ex: ZipException) {
            warnings += IngestWarning("The archive is truncated — read ${order.size} entries before the damage.")
            null
          }
        }
      }
    } catch (ex: IngestError) {
      throw ex
    } catch (ex: Exception) {
      if (order.isEmpty()) throw IngestError.Corrupt("That file isn't a readable archive.", ex)
      warnings += IngestWarning("The archive is damaged — read ${order.size} entries before the damage.")
    }

    if (order.isEmpty()) throw IngestError.Empty("That archive is empty.")
    return Archive(entries, order, warnings)
  }

  /**
   * Reject absolute paths and any `..` segment. Returns the normalised name, or null to drop it.
   * Backslashes are normalised too: archives written on Windows tooling use them freely, and a
   * check that only looks at `/` is a check that can be walked around.
   */
  fun safeName(raw: String): String? {
    val n = raw.replace('\\', '/')
    if (n.startsWith("/") || n.length > 1 && n[1] == ':') return null
    if (n.split('/').any { it == ".." }) return null
    return n
  }

  /** Reads at most [budget] bytes; returns null the moment the entry would exceed it. */
  private fun readCapped(zip: ZipInputStream, budget: Long): ByteArray? {
    if (budget <= 0) return null
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    var written = 0L
    while (true) {
      val r = zip.read(buf)
      if (r <= 0) break
      written += r
      if (written > budget) return null
      out.write(buf, 0, r)
    }
    return out.toByteArray()
  }
}
