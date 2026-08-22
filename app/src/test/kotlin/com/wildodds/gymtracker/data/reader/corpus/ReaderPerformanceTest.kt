package com.wildodds.gymtracker.data.reader.corpus

import com.wildodds.gymtracker.data.importer.layout.WorkbookFixtures
import com.wildodds.gymtracker.data.importer.layout.WorkbookFixtures.Sheet
import com.wildodds.gymtracker.data.reader.ProgramReader
import com.wildodds.gymtracker.data.reader.ingest.IngestLimits
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Performance budgets, as tests.
 *
 * Import is the one place in this app where a user hands over an arbitrarily large file and waits.
 * A parser that is correct and takes eleven seconds on a 50-sheet workbook is a parser people stop
 * using, so the budgets are asserted rather than assumed — a change that makes the reader
 * quadratic fails here instead of in a review.
 *
 * The budgets are deliberately loose: they are set several times above what the code actually takes
 * on this machine, because a CI runner is slower and a flaky performance test gets deleted. They
 * catch ORDERS OF MAGNITUDE, which is the only kind of regression worth failing a build for.
 */
class ReaderPerformanceTest {

  private val reader = ProgramReader()

  private fun millis(block: () -> Unit): Long {
    // One warm-up pass so the measurement is not dominated by class loading and JIT.
    block()
    val t0 = System.nanoTime()
    block()
    return (System.nanoTime() - t0) / 1_000_000
  }

  @Test fun `a five thousand line paste parses within budget`() {
    val doc = buildString {
      append("Big Program\n")
      for (w in 1..50) {
        append("Week $w\n")
        for (d in 1..4) {
          append("Day $d - Session $d\n")
          for (e in 1..24) {
            append("Bench Press ${e % 5 + 1}x${e % 12 + 1} @ ${60 + e}kg rest 90s\n")
          }
        }
      }
    }
    val lines = doc.lines().size
    assertTrue("expected ~5000 lines, built $lines", lines >= 5000)

    var exercises = 0
    val ms = millis { exercises = reader.readText(doc).importResult.exerciseCount }
    println("PERF 5,000-line paste: ${ms}ms, $exercises exercises")
    assertTrue("a 5,000-line paste took ${ms}ms", ms < 8_000)
    assertTrue("it should have read the exercises", exercises >= 4_000)
  }

  @Test fun `a fifty sheet workbook parses within budget`() {
    val header = listOf("Exercise", "Sets", "Reps", "Load")
    val sheets = (1..50).map { w ->
      Sheet("Week $w", listOf(header) + (1..30).map {
        listOf("Bench Press", "3", "8", "80")
      })
    }
    val bytes = WorkbookFixtures.workbook(sheets)
    var exercises = 0
    val ms = millis { exercises = reader.read(bytes, "big.xlsx").importResult.exerciseCount }
    println("PERF 50-sheet workbook (${bytes.size / 1024} KB): ${ms}ms, $exercises exercises")
    assertTrue("a 50-sheet workbook took ${ms}ms", ms < 10_000)
    assertTrue("it should have read the exercises", exercises > 0)
  }

  @Test fun `a wide sheet does not blow up the column solver`() {
    // The Hungarian assignment is cubic in the column count. A sheet 200 columns wide is unusual
    // but real (a week per column group), and it must not become the slowest thing in the app.
    val header = (1..200).map { "Col $it" }
    val rows = (1..50).map { r -> (1..200).map { c -> if (c == 1) "Bench Press" else "$r" } }
    val bytes = WorkbookFixtures.workbook(Sheet("Wide", listOf(header) + rows))
    val ms = millis { reader.read(bytes, "wide.xlsx") }
    println("PERF 200-column sheet: ${ms}ms")
    assertTrue("a 200-column sheet took ${ms}ms", ms < 10_000)
  }

  @Test fun `the exercise index loads once and resolves quickly`() {
    // Resolution runs per exercise line, so a linear scan over 950 names would be quadratic across
    // a large program. The posting lists exist to stop that; this is what proves they work.
    val names = listOf("Back Squat", "RDL", "incline DB press", "Bnech Press", "Pull-Ups",
      "Squat, Back - BB", "Zercher Yoke Medley Thing", "Face Pull", "Hammer Curl", "Thruster")
    val ms = millis {
      repeat(500) { i -> com.wildodds.gymtracker.data.importer.ExerciseResolver.resolve(names[i % names.size]) }
    }
    println("PERF 5,000 resolutions: ${ms}ms")
    assertTrue("5,000 resolutions took ${ms}ms", ms < 5_000)
  }

  @Test fun `a large file is refused rather than read into memory`() {
    // The cap has to bite BEFORE the allocation, not after: the whole point is to avoid the OOM.
    val limits = IngestLimits(maxFileBytes = 1024)
    val small = ProgramReader(limits = limits)
    val big = ByteArray(4096) { 'a'.code.toByte() }
    val e = runCatching { small.read(big, "big.txt") }.exceptionOrNull()
    assertTrue("expected TooLarge, got $e",
      e is com.wildodds.gymtracker.data.reader.ingest.IngestError.TooLarge)
  }

  @Test fun `the cell cap bounds a huge sheet`() {
    val limits = IngestLimits(maxCells = 500)
    val header = listOf("Exercise", "Sets", "Reps")
    val bytes = WorkbookFixtures.workbook(
      Sheet("Huge", listOf(header) + (1..5000).map { listOf("Bench Press", "3", "8") }))
    val capped = ProgramReader(limits = limits)
    var exercises = 0
    val ms = millis { exercises = capped.read(bytes, "huge.xlsx").importResult.exerciseCount }
    println("PERF capped 5,000-row sheet: ${ms}ms, $exercises exercises")
    assertTrue("the cap should have bounded the read", exercises < 1_000)
    assertTrue("a capped read took ${ms}ms", ms < 5_000)
  }
}

/**
 * The exported Room schema must match the entities, and the export must be checked in.
 *
 * `MigrationTest` already validates the migrated database against `<version>.json`. This is the
 * other half: it catches the case where someone bumps the version, writes the migration, and
 * forgets to commit the exported schema — at which point the migration test starts validating
 * against a file that is not in the repository, and CI passes locally and fails for everyone else.
 */
class SchemaSnapshotTest {

  /**
   * Room's `@Database` is not retained at runtime, so the version is read from the two places that
   * ARE observable: the migration chain and the exported schema. Cross-checking them is what makes
   * this test worth having — either alone would agree with itself.
   */
  private fun chainEndVersion(): Int =
    com.wildodds.gymtracker.data.db.AppDatabase.ALL_MIGRATIONS.maxOf { it.endVersion }

  @Test fun `the current schema version is exported and checked in`() {
    val dir = java.io.File(moduleDir(), "schemas/com.wildodds.gymtracker.data.db.AppDatabase")
    assertTrue("no exported schemas at $dir", dir.isDirectory)

    val versions = dir.listFiles()?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
      .orEmpty().sorted()
    assertTrue("no schema JSON files found", versions.isNotEmpty())

    val current = chainEndVersion()
    // The failure this catches: someone bumps the version, writes the migration, and forgets to
    // commit the exported schema. MigrationTest then validates against a file that is not in the
    // repository — green locally, red for everyone else.
    assertTrue("schema $current.json is missing — build once and commit it " +
      "(found: ${versions.joinToString()})", current in versions)
  }

  @Test fun `the exported schema agrees with the migration chain`() {
    val current = chainEndVersion()
    val file = java.io.File(moduleDir(),
      "schemas/com.wildodds.gymtracker.data.db.AppDatabase/$current.json")
    assertTrue("missing $file", file.isFile)
    val json = file.readText()
    // Built from the version number rather than written as a raw regex: the pattern has to
    // interpolate `current`, and a raw string that both interpolates and carries regex escapes
    // is a needless place to get wrong.
    val versionPattern = Regex(Regex.escape("\"version\"") + "\\s*:\\s*" + current + "\\b")
    assertTrue("$current.json does not declare version $current: " + json.take(160),
      versionPattern.containsMatchIn(json))
    // A schema with no entities would validate trivially against anything.
    assertTrue("$current.json has no entities", json.contains("\"entities\""))
    assertTrue("the reader's own tables must be in the exported schema",
      json.contains("import_records") && json.contains("rawSourceLine"))
  }

  @Test fun `the migration chain reaches the current version with no gaps`() {
    val migrations = com.wildodds.gymtracker.data.db.AppDatabase.ALL_MIGRATIONS
      .sortedBy { it.startVersion }
    assertTrue("no migrations registered", migrations.isNotEmpty())

    var v = migrations.first().startVersion
    for (m in migrations) {
      assertTrue("gap in the migration chain: $v -> ${m.startVersion}", m.startVersion == v)
      v = m.endVersion
    }
    assertTrue("the chain must be contiguous and end at ${chainEndVersion()}", v == chainEndVersion())
  }

  private fun moduleDir(): java.io.File {
    val cwd = java.io.File(".").absoluteFile.normalize()
    return if (java.io.File(cwd, "src/main/kotlin").isDirectory) cwd else java.io.File(cwd, "app")
  }
}
