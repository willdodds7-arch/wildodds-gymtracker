package com.wildodds.gymtracker.data.reader.ingest

import com.wildodds.gymtracker.data.db.entity.ImportRecord

/**
 * Caches an extracted document against the hash of its source bytes.
 *
 * Extraction is the expensive half of import — OCR of a photo takes seconds, a 12-page PDF takes
 * hundreds of milliseconds — and it is done more than once per import in practice. The user edits
 * the program name and re-parses; they correct an exercise and want to see the effect; they hit
 * "re-parse with the current reader". Re-rasterising the same PDF for each of those is pure waste,
 * and on a photo it is waste the user watches happen.
 *
 * Keyed on the SOURCE HASH rather than the file name, for the same reason the idempotency check is:
 * the name is not the document. Bounded by total cached characters rather than entry count, because
 * one 8 MB spreadsheet and eight hundred pasted programs are the same problem.
 *
 * Deliberately in-memory and per-process: the persistent copy of an import's source lives in
 * [ImportRecord], which is a different job (surviving a restart so a future parser can re-read it).
 * Duplicating that here would mean two things to keep in step.
 */
class ExtractionCache(private val maxChars: Int = 4 * 1024 * 1024) {

  private data class Entry(val doc: ExtractedDocument, val kind: SourceKind, val name: String)

  // Access-ordered LRU: `removeEldestEntry` evicts on size, and the char budget is checked after
  // each put, because one huge document can exceed the budget on its own.
  private val map = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
      size > MAX_ENTRIES
  }

  private var chars = 0

  @Synchronized
  fun get(sourceHash: String): Triple<ExtractedDocument, SourceKind, String>? =
    map[sourceHash]?.let { Triple(it.doc, it.kind, it.name) }

  @Synchronized
  fun put(sourceHash: String, doc: ExtractedDocument, kind: SourceKind, name: String) {
    // A document too large to be worth caching is not cached at all, rather than evicting
    // everything else to make room for something that will be evicted next.
    if (doc.text.length > maxChars / 2) return
    map[sourceHash] = Entry(doc, kind, name)
    chars = map.values.sumOf { it.doc.text.length }
    val it = map.entries.iterator()
    while (chars > maxChars && it.hasNext()) {
      val e = it.next()
      chars -= e.value.doc.text.length
      it.remove()
    }
  }

  @Synchronized
  fun clear() { map.clear(); chars = 0 }

  @Synchronized
  fun size(): Int = map.size

  companion object {
    private const val MAX_ENTRIES = 8

    fun hash(bytes: ByteArray): String = ImportRecord.hashOf(bytes)
  }
}
