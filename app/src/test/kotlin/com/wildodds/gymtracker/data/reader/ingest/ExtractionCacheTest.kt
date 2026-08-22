package com.wildodds.gymtracker.data.reader.ingest

import com.wildodds.gymtracker.data.reader.ProgramReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction is cached, cancellation actually cancels, and progress is reported.
 *
 * These three are what make the long path bearable rather than fast: a 12-page PDF or an OCR pass
 * takes real time, and the difference between a good and a bad import experience is whether the
 * user can see what is happening, stop it, and not pay for it twice.
 */
class ExtractionCacheTest {

  private val doc = "Day 1\nBench Press 3x8\nBarbell Row 3x10\n"

  @Test fun `the same bytes are extracted once`() {
    val cache = ExtractionCache()
    val reader = ProgramReader(cache = cache)
    val bytes = doc.toByteArray()

    val first = reader.read(bytes, "block.txt")
    assertEquals(1, cache.size())
    val second = reader.read(bytes, "block.txt")

    // The cached read must produce the SAME answer, not merely a faster one.
    assertEquals(first.importResult.exerciseCount, second.importResult.exerciseCount)
    assertEquals(first.route, second.route)
    assertEquals(first.importResult.program.sessions.flatMap { s -> s.exercises.map { it.name } },
      second.importResult.program.sessions.flatMap { s -> s.exercises.map { it.name } })
  }

  @Test fun `different bytes are different entries`() {
    val cache = ExtractionCache()
    val reader = ProgramReader(cache = cache)
    reader.read(doc.toByteArray(), "a.txt")
    reader.read("Day 1\nBack Squat 5x5\n".toByteArray(), "b.txt")
    assertEquals(2, cache.size())
  }

  @Test fun `the same document under a different name is one entry`() {
    // Keyed on the bytes, not the name — the name is not the document.
    val cache = ExtractionCache()
    val reader = ProgramReader(cache = cache)
    reader.read(doc.toByteArray(), "a.txt")
    reader.read(doc.toByteArray(), "renamed.txt")
    assertEquals(1, cache.size())
  }

  @Test fun `the cache is bounded`() {
    val cache = ExtractionCache(maxChars = 4096)
    repeat(40) { i ->
      cache.put("hash-$i", ExtractedDocument(text = "x".repeat(500)), SourceKind.PLAIN_TEXT, "n")
    }
    assertTrue("cache grew unbounded: ${cache.size()}", cache.size() <= 8)
  }

  @Test fun `a document too large to be worth caching is not cached`() {
    val cache = ExtractionCache(maxChars = 1000)
    cache.put("big", ExtractedDocument(text = "x".repeat(5000)), SourceKind.PLAIN_TEXT, "n")
    assertNull("a huge document must not evict everything else for itself", cache.get("big"))
  }

  @Test fun `clearing empties it`() {
    val cache = ExtractionCache()
    cache.put("a", ExtractedDocument(text = "hi"), SourceKind.PLAIN_TEXT, "n")
    cache.clear()
    assertEquals(0, cache.size())
  }

  // ── progress and cancellation ────────────────────────────────────────────────

  @Test fun `progress is reported through a long read`() {
    val stages = ArrayList<String>()
    val progress = object : IngestProgress {
      override fun report(stage: String, current: Int, total: Int) { stages += stage }
    }
    val header = "Exercise,Sets,Reps\n"
    val big = header + (1..500).joinToString("\n") { "Bench Press,3,8" }
    ProgramReader().read(big.toByteArray(), "big.csv", progress = progress)
    assertTrue("no progress was reported", stages.isNotEmpty())
    assertTrue("the first stage should name what is happening: $stages",
      stages.first().isNotBlank())
  }

  @Test fun `cancellation stops the read with a typed error`() {
    val progress = object : IngestProgress {
      override fun report(stage: String, current: Int, total: Int) = Unit
      override fun isCancelled() = true
    }
    val bytes = ("Exercise,Sets,Reps\n" + (1..200).joinToString("\n") { "Bench Press,3,8" }).toByteArray()
    val e = runCatching { ProgramReader().read(bytes, "big.csv", progress = progress) }.exceptionOrNull()
    assertTrue("expected Cancelled, got $e", e is IngestError.Cancelled)
  }

  @Test fun `a cancelled read leaves nothing cached`() {
    val cache = ExtractionCache()
    val progress = object : IngestProgress {
      override fun report(stage: String, current: Int, total: Int) = Unit
      override fun isCancelled() = true
    }
    runCatching {
      ProgramReader(cache = cache).read(doc.toByteArray(), "a.txt", progress = progress)
    }
    assertEquals("a half-read document must not be cached as if it were complete", 0, cache.size())
  }

  @Test fun `a cached read still reports that it is re-parsing`() {
    val cache = ExtractionCache()
    val reader = ProgramReader(cache = cache)
    val bytes = doc.toByteArray()
    reader.read(bytes, "a.txt")

    val stages = ArrayList<String>()
    reader.read(bytes, "a.txt", progress = object : IngestProgress {
      override fun report(stage: String, current: Int, total: Int) { stages += stage }
    })
    assertNotNull(stages.firstOrNull { it.contains("re-parsing", true) || it.contains("read", true) })
  }
}
